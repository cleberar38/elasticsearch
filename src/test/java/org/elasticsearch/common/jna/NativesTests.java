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

package org.elasticsearch.common.jna;

import org.apache.lucene.util.Constants;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.junit.Test;

import static org.hamcrest.Matchers.equalTo;

public class NativesTests extends ElasticsearchTestCase {

    @Test
    public void testMlockall() {
        if (Constants.MAC_OS_X) {
            assertFalse("Memory locking is not available on OS X platforms", Natives.LOCAL_MLOCKALL);
        }
    }
    
    @Test
    public void testConsoleCtrlHandler() {
        if (Constants.WINDOWS) {
            assertNotNull(Kernel32Library.getInstance());
            assertThat(Kernel32Library.getInstance().getCallbacks().size(), equalTo(1));
        } else {
            assertNotNull(Kernel32Library.getInstance());
            assertThat(Kernel32Library.getInstance().getCallbacks().size(), equalTo(0));
        }
    }
}
