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

package org.apache.http;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.impl.nio.bootstrap.HttpServer;
import org.apache.http.impl.nio.bootstrap.ServerBootstrap;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.conn.NoopIOSessionStrategy;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.nio.reactor.ListenerEndpoint;
import org.junit.After;
import org.junit.Before;

public abstract class HttpAsyncTestBase {

    public enum ProtocolScheme { http, https };

    protected final ProtocolScheme scheme;

    protected ServerBootstrap serverBootstrap;
    protected HttpServer server;
    protected HttpAsyncClientBuilder clientBuilder;
    protected PoolingNHttpClientConnectionManager connMgr;
    protected CloseableHttpAsyncClient httpclient;

    public HttpAsyncTestBase(final ProtocolScheme scheme) {
        this.scheme = scheme;
    }

    public HttpAsyncTestBase() {
        this(ProtocolScheme.http);
    }

    public String getSchemeName() {
        return this.scheme.name();
    }

    public HttpHost start() throws Exception {
        this.server = this.serverBootstrap.create();
        this.server.start();

        this.httpclient = this.clientBuilder.build();
        this.httpclient.start();

        final ListenerEndpoint endpoint = this.server.getEndpoint();
        endpoint.waitFor();

        final InetSocketAddress address = (InetSocketAddress) endpoint.getAddress();
        return new HttpHost("localhost", address.getPort(), this.scheme.name());
    }

    @Before
    public void setUp() throws Exception {
        this.serverBootstrap = ServerBootstrap.bootstrap();
        final IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
                .setSoTimeout(15000)
                .build();
        this.serverBootstrap.setServerInfo("TEST/1.1");
        this.serverBootstrap.setIOReactorConfig(ioReactorConfig);
        this.serverBootstrap.setExceptionLogger(ExceptionLogger.STD_ERR);
        if (this.scheme.equals(ProtocolScheme.https)) {
            this.serverBootstrap.setSslContext(SSLTestContexts.createServerSSLContext());
        }

        this.clientBuilder = HttpAsyncClientBuilder.create();
        final RegistryBuilder<SchemeIOSessionStrategy> builder = RegistryBuilder.create();
        builder.register("http", NoopIOSessionStrategy.INSTANCE);
        if (this.scheme.equals(ProtocolScheme.https)) {
            builder.register("https", new SSLIOSessionStrategy(SSLTestContexts.createClientSSLContext()));
        }
        final Registry<SchemeIOSessionStrategy> registry =  builder.build();
        final DefaultConnectingIOReactor ioReactor = new DefaultConnectingIOReactor(ioReactorConfig);
        this.connMgr = new PoolingNHttpClientConnectionManager(ioReactor, registry);
        this.clientBuilder.setConnectionManager(this.connMgr);
    }

    @After
    public void shutDown() throws Exception {
        if (this.httpclient != null) {
            this.httpclient.close();
        }
        if (this.server != null) {
            this.server.shutdown(10, TimeUnit.SECONDS);
        }
    }

}
