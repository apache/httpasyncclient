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

package org.apache.http.impl.nio.client;

import org.apache.http.annotation.Immutable;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.util.Args;

@Immutable
public class HttpAsyncClients {

    private HttpAsyncClients() {
        super();
    }

    public static HttpAsyncClientBuilder custom() {
        return HttpAsyncClientBuilder.create();
    }

    public static CloseableHttpAsyncClient createDefault() {
        return HttpAsyncClientBuilder.create().build();
    }

    public static CloseableHttpAsyncClient createSystem() {
        return HttpAsyncClientBuilder.create().useSystemProperties().build();
    }

    public static CloseableHttpAsyncClient createMinimal() {
        return new MinimalHttpAsyncClient(
                new PoolingNHttpClientConnectionManager(IOReactorUtils.create(IOReactorConfig.DEFAULT)));
    }

    public static CloseableHttpAsyncClient createMinimal(final ConnectingIOReactor ioreactor) {
        Args.notNull(ioreactor, "I/O reactor");
        return new MinimalHttpAsyncClient(
                new PoolingNHttpClientConnectionManager(ioreactor));
    }

    public static CloseableHttpAsyncClient createMinimal(final NHttpClientConnectionManager connManager) {
        Args.notNull(connManager, "Connection manager");
        return new MinimalHttpAsyncClient(connManager);
    }

}
