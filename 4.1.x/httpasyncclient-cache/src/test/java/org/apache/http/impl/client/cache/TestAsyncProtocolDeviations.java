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

/**
 * We are a conditionally-compliant HTTP/1.1 client with a cache. However, a lot of the rules for
 * proxies apply to us, as far as proper operation of the requests that pass through us. Generally
 * speaking, we want to make sure that any response returned from our HttpClient.execute() methods
 * is conditionally compliant with the rules for an HTTP/1.1 server, and that any requests we pass
 * downstream to the backend HttpClient are are conditionally compliant with the rules for an
 * HTTP/1.1 client.
 * <p>
 * There are some cases where strictly behaving as a compliant caching proxy would result in strange
 * behavior, since we're attached as part of a client and are expected to be a drop-in replacement.
 * The test cases captured here document the places where we differ from the HTTP RFC.
 */
public class TestAsyncProtocolDeviations extends TestProtocolDeviations {

    @Override
    protected ClientExecChain createCachingExecChain(
            final ClientExecChain backend,
            final HttpCache cache,
            final CacheConfig config) {
        return new CachingHttpAsyncClientExecChain(backend, cache, config);
    }
}
