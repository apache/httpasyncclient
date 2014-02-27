/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.impl.client.cache;

import org.apache.http.impl.execchain.ClientExecChain;
import org.junit.Ignore;
import org.junit.Test;

public class TestHttpAsyncCacheJiraNumber1147 extends TestHttpCacheJiraNumber1147 {

    @Override
    protected ClientExecChain createCachingExecChain(
            final ClientExecChain backend,
            final BasicHttpCache cache,
            final CacheConfig config) {
        return new CachingHttpAsyncClientExecChain(backend, cache, config);
    }

    @Override @Test @Ignore // TODO: re-enable the test after HttpClient 4.3.4 release
    public void testIssue1147() throws Exception {
        super.testIssue1147();
    }
}
