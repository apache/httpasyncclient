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

import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.util.Args;

/**
 * Factory methods for {@link org.apache.http.impl.nio.client.CloseableHttpAsyncClient} and
 * {@link org.apache.http.impl.nio.client.CloseableHttpPipeliningClient} instances.
 *
 * @since 4.0
 */
public class HttpAsyncClients {

    private HttpAsyncClients() {
        super();
    }

    /**
     * Creates builder object for construction of custom
     * {@link CloseableHttpAsyncClient} instances.
     */
    public static HttpAsyncClientBuilder custom() {
        return HttpAsyncClientBuilder.create();
    }

    /**
     * Creates {@link CloseableHttpAsyncClient} instance with default
     * configuration.
     */
    public static CloseableHttpAsyncClient createDefault() {
        return HttpAsyncClientBuilder.create().build();
    }

    /**
     * Creates {@link CloseableHttpAsyncClient} instance with default
     * configuration based on ssytem properties.
     */
    public static CloseableHttpAsyncClient createSystem() {
        return HttpAsyncClientBuilder.create()
                .useSystemProperties()
                .build();
    }

    /**
     * Creates {@link CloseableHttpAsyncClient} instance that supports esential HTTP protocol
     * aspects only. This client does not support HTTP state management, authentication
     * and automatic redirects.
     */
    public static CloseableHttpAsyncClient createMinimal() {
        return MinimalHttpAsyncClientBuilder.create()
                .disableCookieManagement()
                .build();
    }

    /**
     * Creates {@link CloseableHttpAsyncClient} instance that supports esential HTTP protocol
     * aspects only. This client does not support HTTP state management, authentication
     * and automatic redirects.
     */
    public static CloseableHttpAsyncClient createMinimal(final ConnectingIOReactor ioreactor) {
        Args.notNull(ioreactor, "I/O reactor");
        return createMinimal(new PoolingNHttpClientConnectionManager(ioreactor), false);
    }

    /**
     * Creates {@link CloseableHttpAsyncClient} instance that supports esential HTTP protocol
     * aspects only. This client does not support HTTP state management, authentication
     * and automatic redirects.
     */
    public static CloseableHttpAsyncClient createMinimal(final NHttpClientConnectionManager connManager) {
        return createMinimal(connManager, false);
    }

    /**
     * Creates {@link CloseableHttpAsyncClient} instance that supports esential HTTP protocol
     * aspects only. This client does not support HTTP state management, authentication
     * and automatic redirects.
     * <p>
     * Please note that clients with a shared connection manager make no attempts to control
     * its life cycle and dealocation of resources. It is a responibility of the caller to
     * ensure that the shared connection manager is properly started and shut down when no
     * longer needed.
     *
     * @since 4.1
     */
    public static CloseableHttpAsyncClient createMinimal(
            final NHttpClientConnectionManager connManager, final boolean shared) {
        Args.notNull(connManager, "Connection manager");
        return MinimalHttpAsyncClientBuilder.create()
                .setConnectionManager(connManager)
                .setConnectionManagerShared(shared)
                .disableCookieManagement()
                .build();
    }

    /**
     * Creates {@link CloseableHttpPipeliningClient} instance that supports pipelined request
     * execution. This client does not support authentication and automatic redirects.
     *
     * @since 4.1
     */
    public static CloseableHttpPipeliningClient createPipelining() {
        return MinimalHttpAsyncClientBuilder.create().build();
    }

    /**
     * Creates {@link CloseableHttpPipeliningClient} instance that supports pipelined request
     * execution. This client does not support authentication and automatic redirects.
     *
     * @since 4.1
     */
    public static CloseableHttpPipeliningClient createPipelining(final ConnectingIOReactor ioreactor) {
        return createPipelining(new PoolingNHttpClientConnectionManager(ioreactor), false);
    }

    /**
     * Creates {@link CloseableHttpPipeliningClient} instance that supports pipelined request
     * execution. This client does not support authentication and automatic redirects.
     *
     * @since 4.1
     */
    public static CloseableHttpPipeliningClient createPipelining(final NHttpClientConnectionManager connManager) {
        return createPipelining(connManager, false);
    }

    /**
     * Creates {@link CloseableHttpPipeliningClient} instance that supports pipelined request
     * execution. This client does not support authentication and automatic redirects.
     * <p>
     * Please note that clients with a shared connection manager make no attempts to control
     * its life cycle and dealocation of resources. It is a responibility of the caller to
     * ensure that the shared connection manager is properly started and shut down when no
     * longer needed.
     *
     * @since 4.1
     */
    public static CloseableHttpPipeliningClient createPipelining(
            final NHttpClientConnectionManager connManager, final boolean shared) {
        Args.notNull(connManager, "Connection manager");
        return MinimalHttpAsyncClientBuilder.create()
                .setConnectionManager(connManager)
                .setConnectionManagerShared(shared)
                .build();
    }

}
