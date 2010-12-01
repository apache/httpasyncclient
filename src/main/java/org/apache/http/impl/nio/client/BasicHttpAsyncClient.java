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

import java.io.IOException;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.nio.conn.BasicIOSessionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.client.HttpAsyncExchangeHandler;
import org.apache.http.nio.concurrent.FutureCallback;
import org.apache.http.nio.conn.IOSessionManager;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.params.SyncBasicHttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestExpectContinue;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;

public class BasicHttpAsyncClient implements HttpAsyncClient {

    private final Log log;
    private final HttpParams params;
    private final ConnectingIOReactor ioReactor;
    private final IOSessionManager<HttpRoute> sessmrg;

    private Thread reactorThread;

    public BasicHttpAsyncClient(
            final ConnectingIOReactor ioReactor,
            final IOSessionManager<HttpRoute> sessmrg,
            final HttpParams params) throws IOReactorException {
        super();
        this.log = LogFactory.getLog(getClass());
        if (params != null) {
            this.params = params;
        } else {
            this.params = createDefaultHttpParams();
        }
        this.ioReactor = ioReactor;
        this.sessmrg = sessmrg;
    }

    public BasicHttpAsyncClient(final HttpParams params) throws IOReactorException {
        super();
        this.log = LogFactory.getLog(getClass());
        if (params != null) {
            this.params = params;
        } else {
            this.params = createDefaultHttpParams();
        }
        this.ioReactor = new DefaultConnectingIOReactor(2, this.params);
        this.sessmrg = new BasicIOSessionManager(this.ioReactor);
    }

    protected HttpParams createDefaultHttpParams() {
        HttpParams params = new SyncBasicHttpParams();
        params
            .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 5000)
            .setIntParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 10000)
            .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
            .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true)
            .setParameter(CoreProtocolPNames.USER_AGENT, "HttpComponents/1.1");
        return params;
    }

    protected HttpProcessor createHttpProcessor() {
        HttpRequestInterceptor[] interceptors = new HttpRequestInterceptor[] {
                new RequestContent(),
                new RequestTargetHost(),
                new RequestConnControl(),
                new RequestUserAgent(),
                new RequestExpectContinue()
        };
        ImmutableHttpProcessor httpProcessor = new ImmutableHttpProcessor(interceptors);
        return httpProcessor;
    }

    protected ConnectionReuseStrategy createConnectionReuseStrategy() {
        return new DefaultConnectionReuseStrategy();
    }

    public IOSessionManager<HttpRoute> getSessionManager() {
        return this.sessmrg;
    }

    private void doExecute() {
        NHttpClientProtocolHandler handler = new NHttpClientProtocolHandler(
                createConnectionReuseStrategy());
        IOEventDispatch ioEventDispatch = new InternalClientEventDispatch(handler, this.params);
        try {
            this.ioReactor.execute(ioEventDispatch);
        } catch (IOException ex) {
            this.log.error("I/O reactor terminated abnormally", ex);
        }
    }

    public IOReactorStatus getStatus() {
        return this.ioReactor.getStatus();
    }

    public synchronized void start() {
        this.reactorThread = new Thread() {

            @Override
            public void run() {
                doExecute();
            }

        };
        this.reactorThread.start();
    }

    public synchronized void shutdown() throws InterruptedException {
        this.sessmrg.shutdown();
        try {
            this.ioReactor.shutdown(5000);
        } catch (IOException ex) {
            this.log.error("I/O error shutting down", ex);
        }
        if (this.reactorThread != null) {
            this.reactorThread.join();
        }
    }

    public <T> Future<T> execute(
            final HttpAsyncExchangeHandler<T> handler, final FutureCallback<T> callback) {
        HttpAsyncExchange<T> httpexchange = new HttpAsyncExchange<T>(
                handler,
                callback,
                this.sessmrg,
                createHttpProcessor(),
                new BasicHttpContext(),
                this.params);
        return httpexchange.getResultFuture();
    }

    public Future<HttpResponse> execute(
            final HttpHost target, final HttpRequest request, final FutureCallback<HttpResponse> callback) {
        BasicHttpAsyncExchangeHandler handler = new BasicHttpAsyncExchangeHandler(target, request);
        return execute(handler, callback);
    }

}
