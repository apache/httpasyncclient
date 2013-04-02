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

import java.io.IOException;

import org.apache.http.config.ConnectionConfig;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.localserver.HttpServerNio;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.reactor.IOReactorExceptionHandler;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.junit.After;

public abstract class HttpAsyncTestBase {

    protected HttpServerNio server;
    protected IOReactorConfig serverReactorConfig;
    protected ConnectionConfig serverConnectionConfig;
    protected HttpProcessor serverHttpProc;
    protected DefaultConnectingIOReactor clientIOReactor;
    protected IOReactorConfig clientReactorConfig;
    protected ConnectionConfig clientrConnectionConfig;
    protected PoolingNHttpClientConnectionManager connMgr;
    protected CloseableHttpAsyncClient httpclient;

    protected abstract NHttpConnectionFactory<DefaultNHttpServerConnection> createServerConnectionFactory(
            ConnectionConfig config) throws Exception;

    protected abstract String getSchemeName();

    public static class SimpleIOReactorExceptionHandler implements IOReactorExceptionHandler {

        public boolean handle(final RuntimeException ex) {
            ex.printStackTrace(System.out);
            return false;
        }

        public boolean handle(final IOException ex) {
            ex.printStackTrace(System.out);
            return false;
        }

    }

    public void initServer() throws Exception {
        this.server = new HttpServerNio(
                this.serverReactorConfig, createServerConnectionFactory(this.serverConnectionConfig));
        this.server.setExceptionHandler(new SimpleIOReactorExceptionHandler());
        this.serverHttpProc = new ImmutableHttpProcessor(new HttpResponseInterceptor[] {
                new ResponseDate(),
                new ResponseServer("TEST-SERVER/1.1"),
                new ResponseContent(),
                new ResponseConnControl()
        });
    }

    public void initConnectionManager() throws Exception {
        this.clientIOReactor = new DefaultConnectingIOReactor(this.clientReactorConfig);
        this.connMgr = new PoolingNHttpClientConnectionManager(this.clientIOReactor);
     }

    @After
    public void shutDownClient() throws Exception {
        if (this.httpclient != null) {
            this.httpclient.close();
        }
    }

    @After
    public void shutDownServer() throws Exception {
        if (this.server != null) {
            this.server.shutdown();
        }
    }

}
