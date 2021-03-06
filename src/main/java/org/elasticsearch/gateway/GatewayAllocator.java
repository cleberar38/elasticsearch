/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.gateway;

import com.carrotsearch.hppc.ObjectLongHashMap;
import com.carrotsearch.hppc.ObjectHashSet;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.carrotsearch.hppc.predicates.ObjectPredicate;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.lucene.util.CollectionUtil;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.MutableShardRouting;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.FailedRerouteAllocation;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.StartedRerouteAllocation;
import org.elasticsearch.cluster.routing.allocation.decider.Decision;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.StoreFileMetaData;
import org.elasticsearch.indices.store.TransportNodesListShardStoreMetaData;
import org.elasticsearch.transport.ConnectTransportException;

import java.util.*;
import java.util.concurrent.ConcurrentMap;

/**
 *
 */
public class GatewayAllocator extends AbstractComponent {

    public static final String INDEX_RECOVERY_INITIAL_SHARDS = "index.recovery.initial_shards";

    private final TransportNodesListGatewayStartedShards listGatewayStartedShards;

    private final TransportNodesListShardStoreMetaData listShardStoreMetaData;

    private final ConcurrentMap<ShardId, Map<DiscoveryNode, TransportNodesListShardStoreMetaData.StoreFilesMetaData>> cachedStores = ConcurrentCollections.newConcurrentMap();

    private final ConcurrentMap<ShardId, ObjectLongHashMap<DiscoveryNode>> cachedShardsState = ConcurrentCollections.newConcurrentMap();

    private final TimeValue listTimeout;

    private final String initialShards;

    @Inject
    public GatewayAllocator(Settings settings,
                            TransportNodesListGatewayStartedShards listGatewayStartedShards, TransportNodesListShardStoreMetaData listShardStoreMetaData) {
        super(settings);
        this.listGatewayStartedShards = listGatewayStartedShards;
        this.listShardStoreMetaData = listShardStoreMetaData;

        this.listTimeout = settings.getAsTime("gateway.list_timeout", settings.getAsTime("gateway.local.list_timeout", TimeValue.timeValueSeconds(30)));
        this.initialShards = settings.get("gateway.initial_shards", settings.get("gateway.local.initial_shards", "quorum"));

        logger.debug("using initial_shards [{}], list_timeout [{}]", initialShards, listTimeout);
    }

    public void applyStartedShards(StartedRerouteAllocation allocation) {
        for (ShardRouting shardRouting : allocation.startedShards()) {
            cachedStores.remove(shardRouting.shardId());
            cachedShardsState.remove(shardRouting.shardId());
        }
    }

    public void applyFailedShards(FailedRerouteAllocation allocation) {
        for (ShardRouting failedShard : allocation.failedShards()) {
            cachedStores.remove(failedShard.shardId());
            cachedShardsState.remove(failedShard.shardId());
        }
    }

    /**
     * Return {@code true} if the index is configured to allow shards to be
     * recovered on any node
     */
    private boolean recoverOnAnyNode(@IndexSettings Settings idxSettings) {
        return IndexMetaData.isOnSharedFilesystem(idxSettings) &&
                idxSettings.getAsBoolean(IndexMetaData.SETTING_SHARED_FS_ALLOW_RECOVERY_ON_ANY_NODE, false);
    }

    public boolean allocateUnassigned(RoutingAllocation allocation) {
        boolean changed = false;
        DiscoveryNodes nodes = allocation.nodes();
        RoutingNodes routingNodes = allocation.routingNodes();

        // First, handle primaries, they must find a place to be allocated on here
        MetaData metaData = routingNodes.metaData();
        Iterator<MutableShardRouting> unassignedIterator = routingNodes.unassigned().iterator();
        while (unassignedIterator.hasNext()) {
            MutableShardRouting shard = unassignedIterator.next();

            if (!shard.primary()) {
                continue;
            }

            // this is an API allocation, ignore since we know there is no data...
            if (!routingNodes.routingTable().index(shard.index()).shard(shard.id()).primaryAllocatedPostApi()) {
                continue;
            }

            ObjectLongHashMap<DiscoveryNode> nodesState = buildShardStates(nodes, shard, metaData.index(shard.index()));

            int numberOfAllocationsFound = 0;
            long highestVersion = -1;
            final Map<DiscoveryNode, Long> nodesWithVersion = Maps.newHashMap();

            assert !nodesState.containsKey(null);
            final Object[] keys = nodesState.keys;
            final long[] values = nodesState.values;
            IndexMetaData indexMetaData = routingNodes.metaData().index(shard.index());
            Settings idxSettings = indexMetaData.settings();
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] == null) {
                    continue;
                }

                DiscoveryNode node = (DiscoveryNode) keys[i];
                long version = values[i];
                // since we don't check in NO allocation, we need to double check here
                if (allocation.shouldIgnoreShardForNode(shard.shardId(), node.id())) {
                    continue;
                }
                if (recoverOnAnyNode(idxSettings)) {
                    numberOfAllocationsFound++;
                    if (version > highestVersion) {
                        highestVersion = version;
                    }
                    // We always put the node without clearing the map
                    nodesWithVersion.put(node, version);
                } else if (version != -1) {
                    numberOfAllocationsFound++;
                    // If we've found a new "best" candidate, clear the
                    // current candidates and add it
                    if (version > highestVersion) {
                        highestVersion = version;
                        nodesWithVersion.clear();
                        nodesWithVersion.put(node, version);
                    } else if (version == highestVersion) {
                        // If the candidate is the same, add it to the
                        // list, but keep the current candidate
                        nodesWithVersion.put(node, version);
                    }
                }
            }
            // Now that we have a map of nodes to versions along with the
            // number of allocations found (and not ignored), we need to sort
            // it so the node with the highest version is at the beginning
            List<DiscoveryNode> nodesWithHighestVersion = Lists.newArrayList();
            nodesWithHighestVersion.addAll(nodesWithVersion.keySet());
            CollectionUtil.timSort(nodesWithHighestVersion, new Comparator<DiscoveryNode>() {
                @Override
                public int compare(DiscoveryNode o1, DiscoveryNode o2) {
                    return Long.compare(nodesWithVersion.get(o2), nodesWithVersion.get(o1));
                }
            });

            if (logger.isDebugEnabled()) {
                logger.debug("[{}][{}] found {} allocations of {}, highest version: [{}]",
                        shard.index(), shard.id(), numberOfAllocationsFound, shard, highestVersion);
            }
            if (logger.isTraceEnabled()) {
                StringBuilder sb = new StringBuilder("[");
                for (DiscoveryNode n : nodesWithHighestVersion) {
                    sb.append("[");
                    sb.append(n.getName());
                    sb.append("]");
                    sb.append(" -> ");
                    sb.append(nodesWithVersion.get(n));
                    sb.append(", ");
                }
                sb.append("]");
                logger.trace("{} candidates for allocation: {}", shard, sb.toString());
            }

            // check if the counts meets the minimum set
            int requiredAllocation = 1;
            // if we restore from a repository one copy is more then enough
            if (shard.restoreSource() == null) {
                try {
                    String initialShards = indexMetaData.settings().get(INDEX_RECOVERY_INITIAL_SHARDS, settings.get(INDEX_RECOVERY_INITIAL_SHARDS, this.initialShards));
                    if ("quorum".equals(initialShards)) {
                        if (indexMetaData.numberOfReplicas() > 1) {
                            requiredAllocation = ((1 + indexMetaData.numberOfReplicas()) / 2) + 1;
                        }
                    } else if ("quorum-1".equals(initialShards) || "half".equals(initialShards)) {
                        if (indexMetaData.numberOfReplicas() > 2) {
                            requiredAllocation = ((1 + indexMetaData.numberOfReplicas()) / 2);
                        }
                    } else if ("one".equals(initialShards)) {
                        requiredAllocation = 1;
                    } else if ("full".equals(initialShards) || "all".equals(initialShards)) {
                        requiredAllocation = indexMetaData.numberOfReplicas() + 1;
                    } else if ("full-1".equals(initialShards) || "all-1".equals(initialShards)) {
                        if (indexMetaData.numberOfReplicas() > 1) {
                            requiredAllocation = indexMetaData.numberOfReplicas();
                        }
                    } else {
                        requiredAllocation = Integer.parseInt(initialShards);
                    }
                } catch (Exception e) {
                    logger.warn("[{}][{}] failed to derived initial_shards from value {}, ignore allocation for {}", shard.index(), shard.id(), initialShards, shard);
                }
            }

            // not enough found for this shard, continue...
            if (numberOfAllocationsFound < requiredAllocation) {
                // if we are restoring this shard we still can allocate
                if (shard.restoreSource() == null) {
                    // we can't really allocate, so ignore it and continue
                    unassignedIterator.remove();
                    routingNodes.ignoredUnassigned().add(shard);
                    if (logger.isDebugEnabled()) {
                        logger.debug("[{}][{}]: not allocating, number_of_allocated_shards_found [{}], required_number [{}]", shard.index(), shard.id(), numberOfAllocationsFound, requiredAllocation);
                    }
                } else if (logger.isDebugEnabled()) {
                    logger.debug("[{}][{}]: missing local data, will restore from [{}]", shard.index(), shard.id(), shard.restoreSource());
                }
                continue;
            }

            Set<DiscoveryNode> throttledNodes = Sets.newHashSet();
            Set<DiscoveryNode> noNodes = Sets.newHashSet();
            for (DiscoveryNode discoNode : nodesWithHighestVersion) {
                RoutingNode node = routingNodes.node(discoNode.id());
                if (node == null) {
                    continue;
                }

                Decision decision = allocation.deciders().canAllocate(shard, node, allocation);
                if (decision.type() == Decision.Type.THROTTLE) {
                    throttledNodes.add(discoNode);
                } else if (decision.type() == Decision.Type.NO) {
                    noNodes.add(discoNode);
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("[{}][{}]: allocating [{}] to [{}] on primary allocation", shard.index(), shard.id(), shard, discoNode);
                    }
                    // we found a match
                    changed = true;
                    // make sure we create one with the version from the recovered state
                    allocation.routingNodes().assign(new MutableShardRouting(shard, highestVersion), node.nodeId());
                    unassignedIterator.remove();

                    // found a node, so no throttling, no "no", and break out of the loop
                    throttledNodes.clear();
                    noNodes.clear();
                    break;
                }
            }
            if (throttledNodes.isEmpty()) {
                // if we have a node that we "can't" allocate to, force allocation, since this is our master data!
                if (!noNodes.isEmpty()) {
                    DiscoveryNode discoNode = noNodes.iterator().next();
                    RoutingNode node = routingNodes.node(discoNode.id());
                    if (logger.isDebugEnabled()) {
                        logger.debug("[{}][{}]: forcing allocating [{}] to [{}] on primary allocation", shard.index(), shard.id(), shard, discoNode);
                    }
                    // we found a match
                    changed = true;
                    // make sure we create one with the version from the recovered state
                    allocation.routingNodes().assign(new MutableShardRouting(shard, highestVersion), node.nodeId());
                    unassignedIterator.remove();
                }
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("[{}][{}]: throttling allocation [{}] to [{}] on primary allocation", shard.index(), shard.id(), shard, throttledNodes);
                }
                // we are throttling this, but we have enough to allocate to this node, ignore it for now
                unassignedIterator.remove();
                routingNodes.ignoredUnassigned().add(shard);
            }
        }

        if (!routingNodes.hasUnassigned()) {
            return changed;
        }

        // Now, handle replicas, try to assign them to nodes that are similar to the one the primary was allocated on
        unassignedIterator = routingNodes.unassigned().iterator();
        while (unassignedIterator.hasNext()) {
            MutableShardRouting shard = unassignedIterator.next();

            // pre-check if it can be allocated to any node that currently exists, so we won't list the store for it for nothing
            boolean canBeAllocatedToAtLeastOneNode = false;
            for (ObjectCursor<DiscoveryNode> cursor : nodes.dataNodes().values()) {
                RoutingNode node = routingNodes.node(cursor.value.id());
                if (node == null) {
                    continue;
                }
                // if we can't allocate it on a node, ignore it, for example, this handles
                // cases for only allocating a replica after a primary
                Decision decision = allocation.deciders().canAllocate(shard, node, allocation);
                if (decision.type() == Decision.Type.YES) {
                    canBeAllocatedToAtLeastOneNode = true;
                    break;
                }
            }

            if (!canBeAllocatedToAtLeastOneNode) {
                continue;
            }

            Map<DiscoveryNode, TransportNodesListShardStoreMetaData.StoreFilesMetaData> shardStores = buildShardStores(nodes, shard);

            long lastSizeMatched = 0;
            DiscoveryNode lastDiscoNodeMatched = null;
            RoutingNode lastNodeMatched = null;

            for (Map.Entry<DiscoveryNode, TransportNodesListShardStoreMetaData.StoreFilesMetaData> nodeStoreEntry : shardStores.entrySet()) {
                DiscoveryNode discoNode = nodeStoreEntry.getKey();
                TransportNodesListShardStoreMetaData.StoreFilesMetaData storeFilesMetaData = nodeStoreEntry.getValue();
                logger.trace("{}: checking node [{}]", shard, discoNode);

                if (storeFilesMetaData == null) {
                    // already allocated on that node...
                    continue;
                }

                RoutingNode node = routingNodes.node(discoNode.id());
                if (node == null) {
                    continue;
                }

                // check if we can allocate on that node...
                // we only check for NO, since if this node is THROTTLING and it has enough "same data"
                // then we will try and assign it next time
                Decision decision = allocation.deciders().canAllocate(shard, node, allocation);
                if (decision.type() == Decision.Type.NO) {
                    continue;
                }

                // if it is already allocated, we can't assign to it...
                if (storeFilesMetaData.allocated()) {
                    continue;
                }

                if (!shard.primary()) {
                    MutableShardRouting primaryShard = routingNodes.activePrimary(shard);
                    if (primaryShard != null) {
                        assert primaryShard.active();
                        DiscoveryNode primaryNode = nodes.get(primaryShard.currentNodeId());
                        if (primaryNode != null) {
                            TransportNodesListShardStoreMetaData.StoreFilesMetaData primaryNodeStore = shardStores.get(primaryNode);
                            if (primaryNodeStore != null && primaryNodeStore.allocated()) {
                                long sizeMatched = 0;

                                String primarySyncId = primaryNodeStore.syncId();
                                String replicaSyncId = storeFilesMetaData.syncId();
                                // see if we have a sync id we can make use of
                                if (replicaSyncId != null && replicaSyncId.equals(primarySyncId)) {
                                    logger.trace("{}: node [{}] has same sync id {} as primary", shard, discoNode.name(), replicaSyncId);
                                    lastNodeMatched = node;
                                    lastSizeMatched = Long.MAX_VALUE;
                                    lastDiscoNodeMatched = discoNode;
                                } else {
                                    for (StoreFileMetaData storeFileMetaData : storeFilesMetaData) {
                                        String metaDataFileName = storeFileMetaData.name();
                                        if (primaryNodeStore.fileExists(metaDataFileName) && primaryNodeStore.file(metaDataFileName).isSame(storeFileMetaData)) {
                                            sizeMatched += storeFileMetaData.length();
                                        }
                                    }
                                    logger.trace("{}: node [{}] has [{}/{}] bytes of re-usable data",
                                            shard, discoNode.name(), new ByteSizeValue(sizeMatched), sizeMatched);
                                    if (sizeMatched > lastSizeMatched) {
                                        lastSizeMatched = sizeMatched;
                                        lastDiscoNodeMatched = discoNode;
                                        lastNodeMatched = node;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (lastNodeMatched != null) {
                // we only check on THROTTLE since we checked before before on NO
                Decision decision = allocation.deciders().canAllocate(shard, lastNodeMatched, allocation);
                if (decision.type() == Decision.Type.THROTTLE) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("[{}][{}]: throttling allocation [{}] to [{}] in order to reuse its unallocated persistent store with total_size [{}]", shard.index(), shard.id(), shard, lastDiscoNodeMatched, new ByteSizeValue(lastSizeMatched));
                    }
                    // we are throttling this, but we have enough to allocate to this node, ignore it for now
                    unassignedIterator.remove();
                    routingNodes.ignoredUnassigned().add(shard);
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("[{}][{}]: allocating [{}] to [{}] in order to reuse its unallocated persistent store with total_size [{}]", shard.index(), shard.id(), shard, lastDiscoNodeMatched, new ByteSizeValue(lastSizeMatched));
                    }
                    // we found a match
                    changed = true;
                    allocation.routingNodes().assign(shard, lastNodeMatched.nodeId());
                    unassignedIterator.remove();
                }
            }
        }
        return changed;
    }

    /**
     * Build a map of DiscoveryNodes to shard state number for the given shard.
     * A state of -1 means the shard does not exist on the node, where any
     * shard state >= 0 is the state version of the shard on that node's disk.
     *
     * A shard on shared storage will return at least shard state 0 for all
     * nodes, indicating that the shard can be allocated to any node.
     */
    private ObjectLongHashMap<DiscoveryNode> buildShardStates(final DiscoveryNodes nodes, MutableShardRouting shard, IndexMetaData indexMetaData) {
        ObjectLongHashMap<DiscoveryNode> shardStates = cachedShardsState.get(shard.shardId());
        ObjectHashSet<String> nodeIds;
        if (shardStates == null) {
            shardStates = new ObjectLongHashMap<>();
            cachedShardsState.put(shard.shardId(), shardStates);
            nodeIds = new ObjectHashSet<>(nodes.dataNodes().keys());
        } else {
            // clean nodes that have failed
            shardStates.keys().removeAll(new ObjectPredicate<DiscoveryNode>() {
                @Override
                public boolean apply(DiscoveryNode node) {
                    return !nodes.nodeExists(node.id());
                }
            });
            nodeIds = new ObjectHashSet<>();
            // we have stored cached from before, see if the nodes changed, if they have, go fetch again
            for (ObjectCursor<DiscoveryNode> cursor : nodes.dataNodes().values()) {
                DiscoveryNode node = cursor.value;
                if (!shardStates.containsKey(node)) {
                    nodeIds.add(node.id());
                }
            }
        }
        if (nodeIds.isEmpty()) {
            return shardStates;
        }

        String[] nodesIdsArray = nodeIds.toArray(String.class);
        TransportNodesListGatewayStartedShards.NodesGatewayStartedShards response = listGatewayStartedShards.list(shard.shardId(), indexMetaData.getUUID(), nodesIdsArray, listTimeout).actionGet();
        logListActionFailures(shard, "state", response.failures());

        for (TransportNodesListGatewayStartedShards.NodeGatewayStartedShards nodeShardState : response) {
            long version = nodeShardState.version();
            // -1 version means it does not exists, which is what the API returns, and what we expect to
            logger.trace("[{}] on node [{}] has version [{}] of shard",
                    shard, nodeShardState.getNode(), version);
            shardStates.put(nodeShardState.getNode(), version);
        }
        return shardStates;
    }

    private void logListActionFailures(MutableShardRouting shard, String actionType, FailedNodeException[] failures) {
        for (final FailedNodeException failure : failures) {
            Throwable cause = ExceptionsHelper.unwrapCause(failure);
            if (cause instanceof ConnectTransportException) {
                continue;
            }
            // we log warn here. debug logs with full stack traces will be logged if debug logging is turned on for TransportNodeListGatewayStartedShards
            logger.warn("{}: failed to list shard {} on node [{}]", failure, shard.shardId(), actionType, failure.nodeId());
        }
    }

    private Map<DiscoveryNode, TransportNodesListShardStoreMetaData.StoreFilesMetaData> buildShardStores(DiscoveryNodes nodes, MutableShardRouting shard) {
        Map<DiscoveryNode, TransportNodesListShardStoreMetaData.StoreFilesMetaData> shardStores = cachedStores.get(shard.shardId());
        ObjectHashSet<String> nodesIds;
        if (shardStores == null) {
            shardStores = Maps.newHashMap();
            cachedStores.put(shard.shardId(), shardStores);
            nodesIds = new ObjectHashSet<>(nodes.dataNodes().keys());
        } else {
            nodesIds = new ObjectHashSet<>();
            // clean nodes that have failed
            for (Iterator<DiscoveryNode> it = shardStores.keySet().iterator(); it.hasNext(); ) {
                DiscoveryNode node = it.next();
                if (!nodes.nodeExists(node.id())) {
                    it.remove();
                }
            }

            for (ObjectCursor<DiscoveryNode> cursor : nodes.dataNodes().values()) {
                DiscoveryNode node = cursor.value;
                if (!shardStores.containsKey(node)) {
                    nodesIds.add(node.id());
                }
            }
        }

        if (!nodesIds.isEmpty()) {
            String[] nodesIdsArray = nodesIds.toArray(String.class);
            TransportNodesListShardStoreMetaData.NodesStoreFilesMetaData nodesStoreFilesMetaData = listShardStoreMetaData.list(shard.shardId(), false, nodesIdsArray, listTimeout).actionGet();
            logListActionFailures(shard, "stores", nodesStoreFilesMetaData.failures());

            for (TransportNodesListShardStoreMetaData.NodeStoreFilesMetaData nodeStoreFilesMetaData : nodesStoreFilesMetaData) {
                if (nodeStoreFilesMetaData.storeFilesMetaData() != null) {
                    shardStores.put(nodeStoreFilesMetaData.getNode(), nodeStoreFilesMetaData.storeFilesMetaData());
                }
            }
        }

        return shardStores;
    }
}
