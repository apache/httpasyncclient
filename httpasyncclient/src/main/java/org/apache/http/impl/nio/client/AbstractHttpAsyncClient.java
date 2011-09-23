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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.auth.AuthSchemeRegistry;
import org.apache.http.client.AuthenticationHandler;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.UserTokenHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.params.AuthPolicy;
import org.apache.http.client.params.CookiePolicy;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.cookie.CookieSpecRegistry;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.auth.BasicSchemeFactory;
import org.apache.http.impl.auth.DigestSchemeFactory;
import org.apache.http.impl.auth.NTLMSchemeFactory;
import org.apache.http.impl.auth.NegotiateSchemeFactory;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.DefaultProxyAuthenticationHandler;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.client.DefaultTargetAuthenticationHandler;
import org.apache.http.impl.client.DefaultUserTokenHandler;
import org.apache.http.impl.cookie.BestMatchSpecFactory;
import org.apache.http.impl.cookie.BrowserCompatSpecFactory;
import org.apache.http.impl.cookie.IgnoreSpecFactory;
import org.apache.http.impl.cookie.NetscapeDraftSpecFactory;
import org.apache.http.impl.cookie.RFC2109SpecFactory;
import org.apache.http.impl.cookie.RFC2965SpecFactory;
import org.apache.http.impl.nio.DefaultClientIODispatch;
import org.apache.http.impl.nio.conn.DefaultHttpAsyncRoutePlanner;
import org.apache.http.impl.nio.conn.PoolingAsyncClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.client.methods.HttpAsyncMethods;
import org.apache.http.nio.conn.ClientAsyncConnectionManager;
import org.apache.http.nio.protocol.HttpAsyncClientExchangeHandler;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.DefaultedHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.ImmutableHttpProcessor;

public abstract class AbstractHttpAsyncClient implements HttpAsyncClient {

    private final Log log = LogFactory.getLog(getClass());;
    private final ClientAsyncConnectionManager connmgr;
    private final Queue<HttpAsyncClientExchangeHandler<?>> queue;

    private Thread reactorThread;
    private BasicHttpProcessor mutableProcessor;
    private ImmutableHttpProcessor protocolProcessor;
    private ConnectionReuseStrategy reuseStrategy;
    private ConnectionKeepAliveStrategy keepAliveStrategy;
    private RedirectStrategy redirectStrategy;
    private CookieSpecRegistry supportedCookieSpecs;
    private CookieStore cookieStore;
    private AuthSchemeRegistry supportedAuthSchemes;
    private AuthenticationHandler targetAuthHandler;
    private AuthenticationHandler proxyAuthHandler;
    private CredentialsProvider credsProvider;
    private HttpRoutePlanner routePlanner;
    private UserTokenHandler userTokenHandler;
    private HttpParams params;

    private volatile boolean terminated;

    protected AbstractHttpAsyncClient(final ClientAsyncConnectionManager connmgr) {
        super();
        this.connmgr = connmgr;
        this.queue = new ConcurrentLinkedQueue<HttpAsyncClientExchangeHandler<?>>();
    }

    protected AbstractHttpAsyncClient(final IOReactorConfig config) throws IOReactorException {
        super();
        DefaultConnectingIOReactor defaultioreactor = new DefaultConnectingIOReactor(config);
        defaultioreactor.setExceptionHandler(new InternalIOReactorExceptionHandler(this.log));
        this.connmgr = new PoolingAsyncClientConnectionManager(defaultioreactor);
        this.queue = new ConcurrentLinkedQueue<HttpAsyncClientExchangeHandler<?>>();
    }

    protected abstract HttpParams createHttpParams();

    protected abstract BasicHttpProcessor createHttpProcessor();

    protected HttpContext createHttpContext() {
        HttpContext context = new BasicHttpContext();
        context.setAttribute(
                ClientContext.SCHEME_REGISTRY,
                getConnectionManager().getSchemeRegistry());
        context.setAttribute(
                ClientContext.AUTHSCHEME_REGISTRY,
                getAuthSchemes());
        context.setAttribute(
                ClientContext.COOKIESPEC_REGISTRY,
                getCookieSpecs());
        context.setAttribute(
                ClientContext.COOKIE_STORE,
                getCookieStore());
        context.setAttribute(
                ClientContext.CREDS_PROVIDER,
                getCredentialsProvider());
        return context;
    }

    protected ConnectionReuseStrategy createConnectionReuseStrategy() {
        return new DefaultConnectionReuseStrategy();
    }

    protected ConnectionKeepAliveStrategy createConnectionKeepAliveStrategy() {
        return new DefaultConnectionKeepAliveStrategy();
    }

    protected AuthSchemeRegistry createAuthSchemeRegistry() {
        AuthSchemeRegistry registry = new AuthSchemeRegistry();
        registry.register(
                AuthPolicy.BASIC,
                new BasicSchemeFactory());
        registry.register(
                AuthPolicy.DIGEST,
                new DigestSchemeFactory());
        registry.register(
                AuthPolicy.NTLM,
                new NTLMSchemeFactory());
        registry.register(
                AuthPolicy.SPNEGO,
                new NegotiateSchemeFactory());
        return registry;
    }

    protected CookieSpecRegistry createCookieSpecRegistry() {
        CookieSpecRegistry registry = new CookieSpecRegistry();
        registry.register(
                CookiePolicy.BEST_MATCH,
                new BestMatchSpecFactory());
        registry.register(
                CookiePolicy.BROWSER_COMPATIBILITY,
                new BrowserCompatSpecFactory());
        registry.register(
                CookiePolicy.NETSCAPE,
                new NetscapeDraftSpecFactory());
        registry.register(
                CookiePolicy.RFC_2109,
                new RFC2109SpecFactory());
        registry.register(
                CookiePolicy.RFC_2965,
                new RFC2965SpecFactory());
        registry.register(
                CookiePolicy.IGNORE_COOKIES,
                new IgnoreSpecFactory());
        return registry;
    }

    protected AuthenticationHandler createTargetAuthenticationHandler() {
        return new DefaultTargetAuthenticationHandler();
    }

    protected AuthenticationHandler createProxyAuthenticationHandler() {
        return new DefaultProxyAuthenticationHandler();
    }

    protected CookieStore createCookieStore() {
        return new BasicCookieStore();
    }

    protected CredentialsProvider createCredentialsProvider() {
        return new BasicCredentialsProvider();
    }

    protected HttpRoutePlanner createHttpRoutePlanner() {
        return new DefaultHttpAsyncRoutePlanner(getConnectionManager().getSchemeRegistry());
    }

    protected UserTokenHandler createUserTokenHandler() {
        return new DefaultUserTokenHandler();
    }

    public synchronized final HttpParams getParams() {
        if (this.params == null) {
            this.params = createHttpParams();
        }
        return this.params;
    }

    public synchronized void setParams(final HttpParams params) {
        this.params = params;
    }

    public synchronized ClientAsyncConnectionManager getConnectionManager() {
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

    public synchronized final AuthSchemeRegistry getAuthSchemes() {
        if (this.supportedAuthSchemes == null) {
            this.supportedAuthSchemes = createAuthSchemeRegistry();
        }
        return this.supportedAuthSchemes;
    }

    public synchronized void setAuthSchemes(final AuthSchemeRegistry authSchemeRegistry) {
        this.supportedAuthSchemes = authSchemeRegistry;
    }

    public synchronized final CookieSpecRegistry getCookieSpecs() {
        if (this.supportedCookieSpecs == null) {
            this.supportedCookieSpecs = createCookieSpecRegistry();
        }
        return this.supportedCookieSpecs;
    }

    public synchronized void setCookieSpecs(final CookieSpecRegistry cookieSpecRegistry) {
        this.supportedCookieSpecs = cookieSpecRegistry;
    }

    public synchronized final AuthenticationHandler getTargetAuthenticationHandler() {
        if (this.targetAuthHandler == null) {
            this.targetAuthHandler = createTargetAuthenticationHandler();
        }
        return this.targetAuthHandler;
    }

    public synchronized void setTargetAuthenticationHandler(
            final AuthenticationHandler targetAuthHandler) {
        this.targetAuthHandler = targetAuthHandler;
    }

    public synchronized final AuthenticationHandler getProxyAuthenticationHandler() {
        if (this.proxyAuthHandler == null) {
            this.proxyAuthHandler = createProxyAuthenticationHandler();
        }
        return this.proxyAuthHandler;
    }

    public synchronized void setProxyAuthenticationHandler(
            final AuthenticationHandler proxyAuthHandler) {
        this.proxyAuthHandler = proxyAuthHandler;
    }

    public synchronized final CookieStore getCookieStore() {
        if (this.cookieStore == null) {
            this.cookieStore = createCookieStore();
        }
        return this.cookieStore;
    }

    public synchronized void setCookieStore(final CookieStore cookieStore) {
        this.cookieStore = cookieStore;
    }

    public synchronized final CredentialsProvider getCredentialsProvider() {
        if (this.credsProvider == null) {
            this.credsProvider = createCredentialsProvider();
        }
        return this.credsProvider;
    }

    public synchronized void setCredentialsProvider(final CredentialsProvider credsProvider) {
        this.credsProvider = credsProvider;
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

    public synchronized final UserTokenHandler getUserTokenHandler() {
        if (this.userTokenHandler == null) {
            this.userTokenHandler = createUserTokenHandler();
        }
        return this.userTokenHandler;
    }


    public synchronized void setUserTokenHandler(final UserTokenHandler userTokenHandler) {
        this.userTokenHandler = userTokenHandler;
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
        LoggingClientProtocolHandler handler = new LoggingClientProtocolHandler();
        try {
            IOEventDispatch ioEventDispatch = new DefaultClientIODispatch(handler, getParams());
            this.connmgr.execute(ioEventDispatch);
        } catch (Exception ex) {
            this.log.error("I/O reactor terminated abnormally", ex);
        } finally {
            this.terminated = true;
            while (!this.queue.isEmpty()) {
                HttpAsyncClientExchangeHandler<?> exchangeHandler = this.queue.remove();
                exchangeHandler.cancel();
            }
        }
    }

    public IOReactorStatus getStatus() {
        return this.connmgr.getStatus();
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
        try {
            this.connmgr.shutdown(5000);
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
        if (this.terminated) {
            throw new IllegalStateException("Client has been shut down");
        }
        BasicFuture<T> future = new BasicFuture<T>(callback);
        ResultCallback<T> resultCallback = new DefaultResultCallback<T>(future, this.queue);
        DefaultAsyncRequestDirector<T> httpexchange;
        synchronized (this) {
            HttpContext defaultContext = createHttpContext();
            HttpContext execContext;
            if (context == null) {
                execContext = defaultContext;
            } else {
                execContext = new DefaultedHttpContext(context, defaultContext);
            }
            httpexchange = new DefaultAsyncRequestDirector<T>(
                    this.log,
                    requestProducer,
                    responseConsumer,
                    execContext,
                    resultCallback,
                    this.connmgr,
                    getProtocolProcessor(),
                    getRoutePlanner(),
                    getConnectionReuseStrategy(),
                    getConnectionKeepAliveStrategy(),
                    getRedirectStrategy(),
                    getTargetAuthenticationHandler(),
                    getProxyAuthenticationHandler(),
                    getUserTokenHandler(),
                    getParams());
        }
        this.queue.add(httpexchange);
        httpexchange.start();
        return future;
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
                HttpAsyncMethods.create(target, request),
                HttpAsyncMethods.createConsumer(),
                context, callback);
    }

    public Future<HttpResponse> execute(
            final HttpHost target, final HttpRequest request,
            final FutureCallback<HttpResponse> callback) {
        return execute(target, request, new BasicHttpContext(), callback);
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

    private HttpHost determineTarget(final HttpUriRequest request) throws ClientProtocolException {
        // A null target may be acceptable if there is a default target.
        // Otherwise, the null target is detected in the director.
        HttpHost target = null;

        URI requestURI = request.getURI();
        if (requestURI.isAbsolute()) {
            target = URIUtils.extractHost(requestURI);
            if (target == null) {
                throw new ClientProtocolException(
                        "URI does not specify a valid host name: " + requestURI);
            }
        }
        return target;
    }

}
