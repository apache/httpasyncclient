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
import java.net.URI;
import java.util.Iterator;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.nio.conn.DefaultHttpAsyncRoutePlanner;
import org.apache.http.impl.nio.conn.PoolingClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.client.HttpAsyncRequestProducer;
import org.apache.http.nio.client.HttpAsyncResponseConsumer;
import org.apache.http.nio.concurrent.BasicFuture;
import org.apache.http.nio.concurrent.FutureCallback;
import org.apache.http.nio.conn.ClientConnectionManager;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.params.SyncBasicHttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
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
    private final ClientConnectionManager connmgr;
    private final HttpAsyncResponseSet pendingResponses;

    private Thread reactorThread;

    public BasicHttpAsyncClient(
            final ConnectingIOReactor ioReactor,
            final ClientConnectionManager connmgr,
            final HttpParams params) {
        super();
        this.log = LogFactory.getLog(getClass());
        if (params != null) {
            this.params = params;
        } else {
            this.params = createDefaultHttpParams();
        }
        this.ioReactor = ioReactor;
        this.connmgr = connmgr;
        this.pendingResponses = new HttpAsyncResponseSet();
    }

    public BasicHttpAsyncClient(
            final HttpParams params) throws IOReactorException {
        super();
        this.log = LogFactory.getLog(getClass());
        if (params != null) {
            this.params = params;
        } else {
            this.params = createDefaultHttpParams();
        }
        this.ioReactor = new DefaultConnectingIOReactor(2, this.params);
        this.connmgr = new PoolingClientConnectionManager(this.ioReactor);
        this.pendingResponses = new HttpAsyncResponseSet();
    }

    public BasicHttpAsyncClient() throws IOReactorException {
        this(null);
    }

    public BasicHttpAsyncClient(
            final ConnectingIOReactor ioReactor,
            final ClientConnectionManager connmgr) throws IOReactorException {
        this(ioReactor, connmgr, null);
    }

    protected ClientConnectionManager getConnectionManager() {
        return this.connmgr;
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

    protected HttpRoutePlanner createHttpRoutePlanner() {
        return new DefaultHttpAsyncRoutePlanner(this.connmgr.getSchemeRegistry());
    }

    protected ConnectionReuseStrategy createConnectionReuseStrategy() {
        return new DefaultConnectionReuseStrategy();
    }

    protected ConnectionKeepAliveStrategy createConnectionKeepAliveStrategy() {
        return new DefaultConnectionKeepAliveStrategy();
    }

    protected RedirectStrategy createRedirectStrategy() {
        return new DefaultRedirectStrategy();
    }

    private void doExecute() {
        NHttpClientProtocolHandler handler = new NHttpClientProtocolHandler();
        try {
            IOEventDispatch ioEventDispatch = new InternalClientEventDispatch(handler);
            this.ioReactor.execute(ioEventDispatch);
        } catch (Exception ex) {
            this.log.error("I/O reactor terminated abnormally", ex);
            Iterator<HttpAsyncResponseConsumer<?>> it = this.pendingResponses.iterator();
            while (it.hasNext()) {
                HttpAsyncResponseConsumer<?> responseConsumer = it.next();
                responseConsumer.failed(ex);
                it.remove();
            }
        }
    }

    public HttpParams getParams() {
        return this.params;
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
        this.connmgr.shutdown();
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
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final HttpContext context,
            final FutureCallback<T> callback) {
        this.pendingResponses.add(responseConsumer);
        DefaultAsyncRequestDirector<T> httpexchange;
        synchronized (this) {
            httpexchange = new DefaultAsyncRequestDirector<T>(
                    this.log,
                    requestProducer,
                    responseConsumer,
                    context,
                    new ResponseCompletedCallback<T>(callback,
                            responseConsumer, this.pendingResponses),
                    this.connmgr,
                    createHttpProcessor(),
                    createHttpRoutePlanner(),
                    createConnectionReuseStrategy(),
                    createConnectionKeepAliveStrategy(),
                    createRedirectStrategy(),
                    this.params);
        }
        httpexchange.start();
        return httpexchange.getResultFuture();
    }

    public <T> Future<T> execute(
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final FutureCallback<T> callback) {
        return execute(requestProducer, responseConsumer, new BasicHttpContext(), callback);
    }

    public Future<HttpResponse> execute(
            final HttpHost target, final HttpRequest request, final HttpContext context,
            final FutureCallback<HttpResponse> callback) {
        return execute(
                new BasicHttpAsyncRequestProducer(target, request),
                new BasicHttpAsyncResponseConsumer(),
                context, callback);
    }

    public Future<HttpResponse> execute(
            final HttpHost target, final HttpRequest request,
            final FutureCallback<HttpResponse> callback) {
        return execute(target, request, new BasicHttpContext(), callback);
    }

    private HttpHost determineTarget(final HttpUriRequest request) throws ClientProtocolException {
        // A null target may be acceptable if there is a default target.
        // Otherwise, the null target is detected in the director.
        HttpHost target = null;

        URI requestURI = request.getURI();
        if (requestURI.isAbsolute()) {
            if (requestURI.getHost() == null) {
                throw new ClientProtocolException(
                        "URI does not specify a valid host name: " + requestURI);
            }
            target = new HttpHost(requestURI.getHost(), requestURI.getPort(), requestURI.getScheme());
            // TODO use URIUtils#extractTarget once it becomes available
        }
        return target;
    }

    public Future<HttpResponse> execute(
            final HttpUriRequest request,
            final FutureCallback<HttpResponse> callback) {
        return execute(request, new BasicHttpContext(), callback);
    }

    public Future<HttpResponse> execute(
            final HttpUriRequest request,
            final HttpContext context,
            final FutureCallback<HttpResponse> callback) {
        HttpHost target;
        try {
            target = determineTarget(request);
        } catch (ClientProtocolException ex) {
            BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(callback);
            future.failed(ex);
            return future;
        }
        return execute(target, request, context, callback);
    }

}
