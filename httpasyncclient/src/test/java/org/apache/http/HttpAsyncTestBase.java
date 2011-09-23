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

import org.apache.http.HttpResponseInterceptor;
import org.apache.http.impl.nio.client.DefaultHttpAsyncClient;
import org.apache.http.impl.nio.conn.PoolingAsyncClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.localserver.HttpServerNio;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.NHttpServerIOTarget;
import org.apache.http.nio.conn.scheme.AsyncScheme;
import org.apache.http.nio.conn.scheme.AsyncSchemeRegistry;
import org.apache.http.nio.reactor.IOReactorExceptionHandler;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.params.SyncBasicHttpParams;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.junit.After;

public abstract class HttpAsyncTestBase {

    protected HttpParams serverParams;
    protected HttpServerNio server;
    protected HttpProcessor serverHttpProc;
    protected DefaultConnectingIOReactor ioreactor;
    protected PoolingAsyncClientConnectionManager connMgr;
    protected DefaultHttpAsyncClient httpclient;

    protected abstract NHttpConnectionFactory<NHttpServerIOTarget> createServerConnectionFactory(
            HttpParams params) throws Exception;

    protected abstract String getSchemeName();

    class SimpleIOReactorExceptionHandler implements IOReactorExceptionHandler {

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
        this.serverParams = new SyncBasicHttpParams();
        this.serverParams
            .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 60000)
            .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
            .setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, false)
            .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true)
            .setParameter(CoreProtocolPNames.ORIGIN_SERVER, "TEST-SERVER/1.1");
        this.server = new HttpServerNio(createServerConnectionFactory(this.serverParams));
        this.server.setExceptionHandler(new SimpleIOReactorExceptionHandler());
        this.serverHttpProc = new ImmutableHttpProcessor(new HttpResponseInterceptor[] {
                new ResponseDate(),
                new ResponseServer(),
                new ResponseContent(),
                new ResponseConnControl()
        });
    }

    public void initClient() throws Exception {
        this.ioreactor = new DefaultConnectingIOReactor();
        AsyncSchemeRegistry schemeRegistry = new AsyncSchemeRegistry();
        schemeRegistry.register(new AsyncScheme("http", 80, null));
        this.connMgr = new PoolingAsyncClientConnectionManager(this.ioreactor, schemeRegistry);
        this.httpclient = new DefaultHttpAsyncClient(this.connMgr);
        this.httpclient.getParams()
            .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 60000)
            .setIntParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 60000)
            .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
            .setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, false)
            .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true)
            .setParameter(CoreProtocolPNames.USER_AGENT, "TEST-CLIENT/1.1");
    }

    @After
    public void shutDownClient() throws Exception {
        if (this.httpclient != null) {
            this.httpclient.shutdown();
        }
    }

    @After
    public void shutDownServer() throws Exception {
        if (this.server != null) {
            this.server.shutdown();
        }
    }

}
