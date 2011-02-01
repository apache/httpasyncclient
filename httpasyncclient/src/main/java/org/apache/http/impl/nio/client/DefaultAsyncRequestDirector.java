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
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ProtocolException;
import org.apache.http.ProtocolVersion;
import org.apache.http.auth.AuthScheme;
import org.apache.http.auth.AuthState;
import org.apache.http.client.RedirectException;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.params.HttpClientParams;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.routing.BasicRouteDirector;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.HttpRouteDirector;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.impl.client.ClientParamsStack;
import org.apache.http.impl.client.EntityEnclosingRequestWrapper;
import org.apache.http.impl.client.RequestWrapper;
import org.apache.http.impl.client.RoutedRequest;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.HttpAsyncExchangeHandler;
import org.apache.http.nio.client.HttpAsyncRequestProducer;
import org.apache.http.nio.client.HttpAsyncResponseConsumer;
import org.apache.http.nio.concurrent.FutureCallback;
import org.apache.http.nio.conn.ClientConnectionManager;
import org.apache.http.nio.conn.ManagedClientConnection;
import org.apache.http.nio.conn.scheme.Scheme;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;

class DefaultAsyncRequestDirector<T> implements HttpAsyncExchangeHandler<T> {

    public static final String HTTP_EXCHANGE_HANDLER = "http.nio.async-exchange-handler";

    private final Log log;

    private final HttpAsyncRequestProducer requestProducer;
    private final HttpAsyncResponseConsumer<T> responseConsumer;
    private final HttpContext localContext;
    private final ResultCallback<T> resultCallback;
    private final ClientConnectionManager connmgr;
    private final HttpProcessor httppocessor;
    private final HttpRoutePlanner routePlanner;
    private final HttpRouteDirector routeDirector;
    private final ConnectionReuseStrategy reuseStrategy;
    private final ConnectionKeepAliveStrategy keepaliveStrategy;
    private final RedirectStrategy redirectStrategy;
    private final AuthState targetAuthState;
    private final AuthState proxyAuthState;
    private final HttpParams clientParams;

    private RoutedRequest mainRequest;
    private RoutedRequest followup;
    private HttpResponse finalResponse;

    private ClientParamsStack params;
    private RequestWrapper currentRequest;
    private HttpResponse currentResponse;
    private boolean routeEstablished;
    private Future<ManagedClientConnection> connFuture;
    private ManagedClientConnection managedConn;
    private int redirectCount;
    private ByteBuffer tmpbuf;

    public DefaultAsyncRequestDirector(
            final Log log,
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final HttpContext localContext,
            final ResultCallback<T> callback,
            final ClientConnectionManager connmgr,
            final HttpProcessor httppocessor,
            final HttpRoutePlanner routePlanner,
            final ConnectionReuseStrategy reuseStrategy,
            final ConnectionKeepAliveStrategy keepaliveStrategy,
            final RedirectStrategy redirectStrategy,
            final HttpParams clientParams) {
        super();
        this.log = log;
        this.requestProducer = requestProducer;
        this.responseConsumer = responseConsumer;
        this.localContext = localContext;
        this.resultCallback = callback;
        this.connmgr = connmgr;
        this.httppocessor = httppocessor;
        this.routePlanner = routePlanner;
        this.reuseStrategy = reuseStrategy;
        this.keepaliveStrategy = keepaliveStrategy;
        this.redirectStrategy = redirectStrategy;
        this.routeDirector = new BasicRouteDirector();
        this.targetAuthState = new AuthState();
        this.proxyAuthState = new AuthState();
        this.clientParams = clientParams;
    }

    public synchronized void start() {
        try {
            HttpHost target = this.requestProducer.getTarget();
            HttpRequest request = this.requestProducer.generateRequest();
            this.params = new ClientParamsStack(null, this.clientParams, request.getParams(), null);
            RequestWrapper wrapper = wrapRequest(request);
            wrapper.setParams(this.params);
            HttpRoute route = determineRoute(target, wrapper, this.localContext);
            this.mainRequest = new RoutedRequest(wrapper, route);
            requestConnection();
        } catch (Exception ex) {
            failed(ex);
        }
    }

    public HttpHost getTarget() {
        return this.requestProducer.getTarget();
    }

    public synchronized HttpRequest generateRequest() throws IOException, HttpException {
        HttpRoute route = this.mainRequest.getRoute();
        if (!this.routeEstablished) {
            int step;
            do {
                HttpRoute fact = this.managedConn.getRoute();
                step = this.routeDirector.nextStep(route, fact);
                switch (step) {
                case HttpRouteDirector.CONNECT_TARGET:
                case HttpRouteDirector.CONNECT_PROXY:
                    break;
                case HttpRouteDirector.TUNNEL_TARGET:
                    this.log.debug("Tunnel required");
                    HttpRequest connect = createConnectRequest(route);
                    this.currentRequest = wrapRequest(connect);
                    this.currentRequest.setParams(this.params);
                    break;
                case HttpRouteDirector.TUNNEL_PROXY:
                    throw new HttpException("Proxy chains are not supported");
                case HttpRouteDirector.LAYER_PROTOCOL:
                    managedConn.layerProtocol(this.localContext, this.params);
                    break;
                case HttpRouteDirector.UNREACHABLE:
                    throw new HttpException("Unable to establish route: " +
                            "planned = " + route + "; current = " + fact);
                case HttpRouteDirector.COMPLETE:
                    this.routeEstablished = true;
                    break;
                default:
                    throw new IllegalStateException("Unknown step indicator "
                            + step + " from RouteDirector.");
                }
            } while (step > HttpRouteDirector.COMPLETE && this.currentRequest == null);
        }

        HttpHost target = (HttpHost) this.params.getParameter(ClientPNames.VIRTUAL_HOST);
        if (target == null) {
            target = route.getTargetHost();
        }
        HttpHost proxy = route.getProxyHost();

        if (this.currentRequest == null) {
            this.currentRequest = this.mainRequest.getRequest();
            // Re-write request URI if needed
            rewriteRequestURI(this.currentRequest, route);
        }
        // Reset headers on the request wrapper
        this.currentRequest.resetHeaders();

        this.localContext.setAttribute(ExecutionContext.HTTP_REQUEST, this.currentRequest);
        this.localContext.setAttribute(ExecutionContext.HTTP_TARGET_HOST, target);
        this.localContext.setAttribute(ExecutionContext.HTTP_PROXY_HOST, proxy);
        this.localContext.setAttribute(ExecutionContext.HTTP_CONNECTION, this.managedConn);
        this.httppocessor.process(this.currentRequest, this.localContext);
        if (this.log.isDebugEnabled()) {
            this.log.debug("Request submitted: " + this.currentRequest.getRequestLine());
        }
        return this.currentRequest;
    }

    public synchronized void produceContent(
            final ContentEncoder encoder, final IOControl ioctrl) throws IOException {
        this.requestProducer.produceContent(encoder, ioctrl);
        if (encoder.isCompleted()) {
            this.requestProducer.resetRequest();
        }
    }

    public boolean isRepeatable() {
        return this.requestProducer.isRepeatable();
    }

    public void resetRequest() {
        this.requestProducer.resetRequest();
    }

    public synchronized void responseReceived(
            final HttpResponse response) throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("Response: " + response.getStatusLine());
        }
        this.currentResponse = response;
        this.currentResponse.setParams(this.params);
        this.localContext.setAttribute(ExecutionContext.HTTP_RESPONSE, this.currentResponse);
        this.httppocessor.process(this.currentResponse, this.localContext);

        int status = this.currentResponse.getStatusLine().getStatusCode();

        if (!this.routeEstablished) {
            String method = this.currentRequest.getMethod();
            if (method.equalsIgnoreCase("CONNECT") && status == HttpStatus.SC_OK) {
                this.managedConn.tunnelTarget(this.params);
            } else {
                this.finalResponse = response;
            }
        } else {
            this.followup = handleResponse();
            if (this.followup == null) {
                this.finalResponse = response;
            }
        }
        if (this.finalResponse != null) {
            this.responseConsumer.responseReceived(response);
        }
    }

    public synchronized void consumeContent(
            final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
        if (this.finalResponse != null) {
            this.responseConsumer.consumeContent(decoder, ioctrl);
        } else {
            if (this.tmpbuf == null) {
                this.tmpbuf = ByteBuffer.allocate(2048);
            }
            this.tmpbuf.clear();
            decoder.read(this.tmpbuf);
        }
    }

    private void releaseResources() {
        if (this.managedConn != null) {
            try {
                this.managedConn.getContext().removeAttribute(HTTP_EXCHANGE_HANDLER);
                this.managedConn.releaseConnection();
            } catch (IOException ioex) {
                this.log.debug("I/O error releasing connection", ioex);
            }
        }
        this.managedConn = null;
        if (this.connFuture != null) {
            this.connFuture.cancel(true);
            this.connFuture = null;
        }
        this.requestProducer.resetRequest();
    }

    public synchronized void failed(final Exception ex) {
        try {
            this.responseConsumer.failed(ex);
        } finally {
            try {
                this.resultCallback.failed(ex, this);
            } finally {
                releaseResources();
            }
        }
    }

    public synchronized boolean keepAlive(final HttpResponse response) {
        return this.reuseStrategy.keepAlive(response, this.localContext);
    }

    public synchronized void responseCompleted() {
        this.log.debug("Response fully read");
        try {
            if (this.managedConn.isOpen()) {
                long duration = this.keepaliveStrategy.getKeepAliveDuration(
                        this.currentResponse, this.localContext);
                if (this.log.isDebugEnabled()) {
                    String s;
                    if (duration > 0) {
                        s = "for " + duration + " " + TimeUnit.MILLISECONDS;
                    } else {
                        s = "indefinitely";
                    }
                    this.log.debug("Connection can be kept alive " + s);
                }
                this.managedConn.setIdleDuration(duration, TimeUnit.MILLISECONDS);
            }

            if (this.finalResponse != null) {
                this.responseConsumer.responseCompleted();
                this.log.debug("Response processed");
                this.resultCallback.completed(this.responseConsumer.getResult(), this);
                releaseResources();
            } else {
                if (this.followup != null) {
                    HttpRoute actualRoute = this.mainRequest.getRoute();
                    HttpRoute newRoute = this.followup.getRoute();
                    if (!actualRoute.equals(newRoute)) {
                        releaseResources();
                    }
                    this.mainRequest = this.followup;
                }
                if (this.managedConn != null) {
                    this.managedConn.requestOutput();
                } else {
                    requestConnection();
                }
            }
            this.followup = null;
            this.currentRequest = null;
            this.currentResponse = null;
        } catch (RuntimeException runex) {
            failed(runex);
            throw runex;
        }
    }

    public synchronized void cancel() {
        this.log.debug("HTTP exchange cancelled");
        try {
            this.responseConsumer.cancel();
            this.resultCallback.cancelled(this);
            releaseResources();
        } catch (RuntimeException runex) {
            failed(runex);
            throw runex;
        }
    }

    public boolean isDone() {
        return this.resultCallback.isDone();
    }

    public T getResult() {
        return this.responseConsumer.getResult();
    }

    private synchronized void connectionRequestCompleted(final ManagedClientConnection conn) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("Connection request suceeded: " + conn);
        }
        try {
            HttpRoute route = this.mainRequest.getRoute();
            if (!conn.isOpen()) {
                conn.open(route, this.localContext, this.params);
            }
            this.managedConn = conn;
            this.managedConn.getContext().setAttribute(HTTP_EXCHANGE_HANDLER, this);
            this.managedConn.requestOutput();
            this.routeEstablished = route.equals(conn.getRoute());
        } catch (IOException ex) {
            failed(ex);
        } catch (RuntimeException runex) {
            failed(runex);
            throw runex;
        }
    }

    private synchronized void connectionRequestFailed(final Exception ex) {
        this.log.debug("Connection request failed", ex);
        try {
            this.requestProducer.resetRequest();
            this.responseConsumer.failed(ex);
        } finally {
            this.resultCallback.failed(ex, this);
        }
    }

    private synchronized void connectionRequestCancelled() {
        this.log.debug("Connection request cancelled");
        try {
            this.requestProducer.resetRequest();
            this.responseConsumer.cancel();
        } finally {
            this.resultCallback.cancelled(this);
        }
    }

    class InternalFutureCallback implements FutureCallback<ManagedClientConnection> {

        public void completed(final ManagedClientConnection session) {
            connectionRequestCompleted(session);
        }

        public void failed(final Exception ex) {
            connectionRequestFailed(ex);
        }

        public void cancelled() {
            connectionRequestCancelled();
        }

    }

    private void requestConnection() {
        HttpRoute route = this.mainRequest.getRoute();
        long connectTimeout = HttpConnectionParams.getConnectionTimeout(this.params);
        Object userToken = this.localContext.getAttribute(ClientContext.USER_TOKEN);
        this.connFuture = this.connmgr.leaseConnection(
                route, userToken,
                connectTimeout, TimeUnit.MILLISECONDS,
                new InternalFutureCallback());
    }

    protected HttpRoute determineRoute(
            HttpHost target,
            final HttpRequest request,
            final HttpContext context) throws HttpException {
        if (target == null) {
            target = (HttpHost) request.getParams().getParameter(ClientPNames.DEFAULT_HOST);
        }
        if (target == null) {
            throw new IllegalStateException("Target host could not be resolved");
        }
        return this.routePlanner.determineRoute(target, request, context);
    }

    private RequestWrapper wrapRequest(final HttpRequest request) throws ProtocolException {
        if (request instanceof HttpEntityEnclosingRequest) {
            return new EntityEnclosingRequestWrapper((HttpEntityEnclosingRequest) request);
        } else {
            return new RequestWrapper(request);
        }
    }

    protected void rewriteRequestURI(
            final RequestWrapper request, final HttpRoute route) throws ProtocolException {
        try {
            URI uri = request.getURI();
            if (route.getProxyHost() != null && !route.isTunnelled()) {
                // Make sure the request URI is absolute
                if (!uri.isAbsolute()) {
                    HttpHost target = route.getTargetHost();
                    uri = URIUtils.rewriteURI(uri, target);
                    request.setURI(uri);
                }
            } else {
                // Make sure the request URI is relative
                if (uri.isAbsolute()) {
                    uri = URIUtils.rewriteURI(uri, null);
                    request.setURI(uri);
                }
            }
        } catch (URISyntaxException ex) {
            throw new ProtocolException("Invalid URI: " + request.getRequestLine().getUri(), ex);
        }
    }

    private HttpRequest createConnectRequest(final HttpRoute route) {
        // see RFC 2817, section 5.2 and
        // INTERNET-DRAFT: Tunneling TCP based protocols through
        // Web proxy servers
        HttpHost target = route.getTargetHost();
        String host = target.getHostName();
        int port = target.getPort();
        if (port < 0) {
            Scheme scheme = this.connmgr.getSchemeRegistry().getScheme(target.getSchemeName());
            port = scheme.getDefaultPort();
        }
        StringBuilder buffer = new StringBuilder(host.length() + 6);
        buffer.append(host);
        buffer.append(':');
        buffer.append(Integer.toString(port));
        ProtocolVersion ver = HttpProtocolParams.getVersion(this.params);
        HttpRequest req = new BasicHttpRequest("CONNECT", buffer.toString(), ver);
        return req;
    }

    private RoutedRequest handleResponse() throws HttpException {
        HttpRoute route = this.mainRequest.getRoute();
        RequestWrapper request = this.mainRequest.getRequest();
        if (HttpClientParams.isRedirecting(this.params) && this.redirectStrategy.isRedirected(
                this.currentRequest, this.currentResponse, this.localContext)) {

            int maxRedirects = this.params.getIntParameter(ClientPNames.MAX_REDIRECTS, 100);
            if (this.redirectCount >= maxRedirects) {
                throw new RedirectException("Maximum redirects ("
                        + maxRedirects + ") exceeded");
            }
            this.redirectCount++;

            HttpUriRequest redirect = this.redirectStrategy.getRedirect(
                    this.currentRequest, this.currentResponse, this.localContext);
            HttpRequest orig = request.getOriginal();
            redirect.setHeaders(orig.getAllHeaders());

            URI uri = redirect.getURI();
            if (uri.getHost() == null) {
                throw new ProtocolException("Redirect URI does not specify a valid host name: " + uri);
            }
            HttpHost newTarget = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());

            // Unset auth scope
            this.targetAuthState.setAuthScope(null);
            this.proxyAuthState.setAuthScope(null);

            // Invalidate auth states if redirecting to another host
            if (!route.getTargetHost().equals(newTarget)) {
                this.targetAuthState.invalidate();
                AuthScheme authScheme = this.proxyAuthState.getAuthScheme();
                if (authScheme != null && authScheme.isConnectionBased()) {
                    this.proxyAuthState.invalidate();
                }
            }

            RequestWrapper newRequest = wrapRequest(redirect);
            newRequest.setParams(this.params);

            HttpRoute newRoute = determineRoute(newTarget, newRequest, this.localContext);

            if (this.log.isDebugEnabled()) {
                this.log.debug("Redirecting to '" + uri + "' via " + newRoute);
            }
            return new RoutedRequest(newRequest, newRoute);
        }
        return null;
    }
}
