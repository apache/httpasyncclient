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
package org.apache.http.localserver;

import java.util.List;

import org.apache.http.HttpHost;
import org.apache.http.impl.nio.client.DefaultHttpAsyncClient;
import org.apache.http.impl.nio.conn.PoolingClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.ExceptionEvent;
import org.apache.http.nio.conn.ClientConnectionManager;
import org.apache.http.nio.conn.scheme.Scheme;
import org.apache.http.nio.conn.scheme.SchemeRegistry;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.params.BasicHttpParams;
import org.junit.After;
import org.junit.Before;

public abstract class AsyncHttpTestBase {

    protected LocalTestServer localServer;
    protected HttpHost target;
    protected DefaultConnectingIOReactor ioreactor;
    protected PoolingClientConnectionManager sessionManager;
    protected DefaultHttpAsyncClient httpclient;

    protected LocalTestServer createServer() throws Exception {
        LocalTestServer localServer = new LocalTestServer(null, null);
        localServer.registerDefaultHandlers();
        return localServer;
    }

    protected DefaultConnectingIOReactor createIOReactor() throws Exception {
        return new DefaultConnectingIOReactor(2, new BasicHttpParams());
    }

    protected PoolingClientConnectionManager createConnectionManager(
            final ConnectingIOReactor ioreactor) throws Exception {
        SchemeRegistry schemeRegistry = new SchemeRegistry();
        schemeRegistry.register(new Scheme("http", 80, null));
        return new PoolingClientConnectionManager(ioreactor, schemeRegistry);
    }

    protected DefaultHttpAsyncClient createClient(
            final ClientConnectionManager sessionManager) throws Exception {
        return new DefaultHttpAsyncClient(sessionManager);
    }

    @Before
    public void startServer() throws Exception {
        this.localServer = createServer();
        this.localServer.start();
        int port = this.localServer.getServiceAddress().getPort();
        this.target = new HttpHost("localhost", port);
    }

    @Before
    public void startClient() throws Exception {
        this.ioreactor = createIOReactor();
        this.sessionManager = createConnectionManager(this.ioreactor);
        this.httpclient = createClient(this.sessionManager);
        this.httpclient.start();
    }

    @After
    public void stopServer() throws Exception {
        if (this.localServer != null) {
            this.localServer.stop();
            this.localServer = null;
        }
    }

    @After
    public void stopClient() throws Exception {
        if (this.httpclient != null) {
            this.httpclient.shutdown();
            if (this.ioreactor.getStatus() != IOReactorStatus.SHUT_DOWN) {
                System.err.println("I/O reactor failed to shut down cleanly");
            } else {
                List<ExceptionEvent> exs = this.ioreactor.getAuditLog();
                if (exs != null && !exs.isEmpty()) {
                    System.err.println("I/O reactor terminated abnormally");
                    for (ExceptionEvent ex: exs) {
                        System.err.println("-------------------------");
                        ex.getCause().printStackTrace();
                    }
                }
            }
            this.httpclient = null;
        }
    }

}
