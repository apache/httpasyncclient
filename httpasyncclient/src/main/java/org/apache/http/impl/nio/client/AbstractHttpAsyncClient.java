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
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.ClientContext;
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
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.ImmutableHttpProcessor;

public abstract class AbstractHttpAsyncClient implements HttpAsyncClient {

    private final Log log = LogFactory.getLog(getClass());;
    private final ConnectingIOReactor ioReactor;
    private final ClientConnectionManager connmgr;
    private final HttpAsyncResponseSet pendingResponses;

    private Thread reactorThread;
    private BasicHttpProcessor mutableProcessor;
    private ImmutableHttpProcessor protocolProcessor;
    private ConnectionReuseStrategy reuseStrategy;
    private ConnectionKeepAliveStrategy keepAliveStrategy;
    private RedirectStrategy redirectStrategy;
    private HttpRoutePlanner routePlanner;
    private HttpParams params;

    protected AbstractHttpAsyncClient(
            final ConnectingIOReactor ioReactor,
            final ClientConnectionManager connmgr,
            final HttpParams params) {
        super();
        this.ioReactor = ioReactor;
        this.connmgr = connmgr;
        this.pendingResponses = new HttpAsyncResponseSet();
        this.params = params;
    }

    protected AbstractHttpAsyncClient(
            final HttpParams params) throws IOReactorException {
        super();
        this.ioReactor = new DefaultConnectingIOReactor(2, params);
        this.connmgr = new PoolingClientConnectionManager(this.ioReactor);
        this.pendingResponses = new HttpAsyncResponseSet();
        this.params = params;
    }

    protected abstract HttpParams createHttpParams();

    protected abstract BasicHttpProcessor createHttpProcessor();

    protected HttpContext createHttpContext() {
        HttpContext context = new BasicHttpContext();
        context.setAttribute(
                ClientContext.SCHEME_REGISTRY,
                getConnectionManager().getSchemeRegistry());
        return context;
    }

    protected ConnectionReuseStrategy createConnectionReuseStrategy() {
        return new DefaultConnectionReuseStrategy();
    }

    protected ConnectionKeepAliveStrategy createConnectionKeepAliveStrategy() {
        return new DefaultConnectionKeepAliveStrategy();
    }

    protected HttpRoutePlanner createHttpRoutePlanner() {
        return new DefaultHttpAsyncRoutePlanner(getConnectionManager().getSchemeRegistry());
    }

    public synchronized final HttpParams getParams() {
        if (this.params == null) {
            this.params = createHttpParams();
        }
        return this.params;
    }

    public synchronized ClientConnectionManager getConnectionManager() {
        return this.connmgr;
    }

    public synchronized final ConnectionReuseStrategy getConnectionReuseStrategy() {
        if (this.reuseStrategy == null) {
            this.reuseStrategy = createConnectionReuseStrategy();
        }
        return this.reuseStrategy;
    }

    public synchronized void setReuseStrategy(final ConnectionReuseStrategy reuseStrategy) {
        this.reuseStrategy = reuseStrategy;
    }

    public synchronized final ConnectionKeepAliveStrategy getConnectionKeepAliveStrategy() {
        if (this.keepAliveStrategy == null) {
            this.keepAliveStrategy = createConnectionKeepAliveStrategy();
        }
        return this.keepAliveStrategy;
    }

    public synchronized void setKeepAliveStrategy(final ConnectionKeepAliveStrategy keepAliveStrategy) {
        this.keepAliveStrategy = keepAliveStrategy;
    }

    public synchronized final RedirectStrategy getRedirectStrategy() {
        if (this.redirectStrategy == null) {
            this.redirectStrategy = new DefaultRedirectStrategy();
        }
        return this.redirectStrategy;
    }

    public synchronized void setRedirectStrategy(final RedirectStrategy redirectStrategy) {
        this.redirectStrategy = redirectStrategy;
    }

    public synchronized final HttpRoutePlanner getRoutePlanner() {
        if (this.routePlanner == null) {
            this.routePlanner = createHttpRoutePlanner();
        }
        return this.routePlanner;
    }

    public synchronized void setRoutePlanner(final HttpRoutePlanner routePlanner) {
        this.routePlanner = routePlanner;
    }

    protected synchronized final BasicHttpProcessor getHttpProcessor() {
        if (this.mutableProcessor == null) {
            this.mutableProcessor = createHttpProcessor();
        }
        return this.mutableProcessor;
    }

    private synchronized final HttpProcessor getProtocolProcessor() {
        if (this.protocolProcessor == null) {
            // Get mutable HTTP processor
            BasicHttpProcessor proc = getHttpProcessor();
            // and create an immutable copy of it
            int reqc = proc.getRequestInterceptorCount();
            HttpRequestInterceptor[] reqinterceptors = new HttpRequestInterceptor[reqc];
            for (int i = 0; i < reqc; i++) {
                reqinterceptors[i] = proc.getRequestInterceptor(i);
            }
            int resc = proc.getResponseInterceptorCount();
            HttpResponseInterceptor[] resinterceptors = new HttpResponseInterceptor[resc];
            for (int i = 0; i < resc; i++) {
                resinterceptors[i] = proc.getResponseInterceptor(i);
            }
            this.protocolProcessor = new ImmutableHttpProcessor(reqinterceptors, resinterceptors);
        }
        return this.protocolProcessor;
    }

    public synchronized int getResponseInterceptorCount() {
        return getHttpProcessor().getResponseInterceptorCount();
    }

    public synchronized HttpResponseInterceptor getResponseInterceptor(int index) {
        return getHttpProcessor().getResponseInterceptor(index);
    }

    public synchronized HttpRequestInterceptor getRequestInterceptor(int index) {
        return getHttpProcessor().getRequestInterceptor(index);
    }

    public synchronized int getRequestInterceptorCount() {
        return getHttpProcessor().getRequestInterceptorCount();
    }

    public synchronized void addResponseInterceptor(final HttpResponseInterceptor itcp) {
        getHttpProcessor().addInterceptor(itcp);
        this.protocolProcessor = null;
    }

    public synchronized void addResponseInterceptor(final HttpResponseInterceptor itcp, int index) {
        getHttpProcessor().addInterceptor(itcp, index);
        this.protocolProcessor = null;
    }

    public synchronized void clearResponseInterceptors() {
        getHttpProcessor().clearResponseInterceptors();
        this.protocolProcessor = null;
    }

    public synchronized void removeResponseInterceptorByClass(Class<? extends HttpResponseInterceptor> clazz) {
        getHttpProcessor().removeResponseInterceptorByClass(clazz);
        this.protocolProcessor = null;
    }

    public synchronized void addRequestInterceptor(final HttpRequestInterceptor itcp) {
        getHttpProcessor().addInterceptor(itcp);
        this.protocolProcessor = null;
    }

    public synchronized void addRequestInterceptor(final HttpRequestInterceptor itcp, int index) {
        getHttpProcessor().addInterceptor(itcp, index);
        this.protocolProcessor = null;
    }

    public synchronized void clearRequestInterceptors() {
        getHttpProcessor().clearRequestInterceptors();
        this.protocolProcessor = null;
    }

    public synchronized void removeRequestInterceptorByClass(Class<? extends HttpRequestInterceptor> clazz) {
        getHttpProcessor().removeRequestInterceptorByClass(clazz);
        this.protocolProcessor = null;
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
                    getProtocolProcessor(),
                    getRoutePlanner(),
                    getConnectionReuseStrategy(),
                    getConnectionKeepAliveStrategy(),
                    getRedirectStrategy(),
                    getParams());
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
