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
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.ProtocolException;
import org.apache.http.auth.AUTH;
import org.apache.http.auth.AuthProtocolState;
import org.apache.http.auth.AuthScheme;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.AuthState;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthenticationStrategy;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.NonRepeatableRequestException;
import org.apache.http.client.RedirectException;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.UserTokenHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.Configurable;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.protocol.RequestClientConnControl;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.conn.routing.BasicRouteDirector;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.HttpRouteDirector;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.impl.auth.HttpAuthenticator;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.RequestTargetHost;

class MainClientExec implements InternalClientExec {

    private final Log log = LogFactory.getLog(getClass());

    private final HttpProcessor httpProcessor;
    private final HttpProcessor proxyHttpProcessor;
    private final HttpRoutePlanner routePlanner;
    private final AuthenticationStrategy targetAuthStrategy;
    private final AuthenticationStrategy proxyAuthStrategy;
    private final UserTokenHandler userTokenHandler;
    private final RedirectStrategy redirectStrategy;
    private final HttpRouteDirector routeDirector;
    private final HttpAuthenticator authenticator;

    public MainClientExec(
            final HttpProcessor httpProcessor,
            final HttpRoutePlanner routePlanner,
            final RedirectStrategy redirectStrategy,
            final AuthenticationStrategy targetAuthStrategy,
            final AuthenticationStrategy proxyAuthStrategy,
            final UserTokenHandler userTokenHandler) {
        super();
        this.httpProcessor = httpProcessor;
        this.proxyHttpProcessor = new ImmutableHttpProcessor(
                new RequestTargetHost(), new RequestClientConnControl());
        this.routePlanner = routePlanner;
        this.redirectStrategy = redirectStrategy;
        this.targetAuthStrategy = targetAuthStrategy;
        this.proxyAuthStrategy = proxyAuthStrategy;
        this.userTokenHandler = userTokenHandler;
        this.routeDirector = new BasicRouteDirector();
        this.authenticator = new HttpAuthenticator(log);
    }

    @Override
    public void prepare(
            final HttpHost target,
            final HttpRequest original,
            final InternalState state,
            final AbstractClientExchangeHandler<?> handler) throws HttpException, IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + state.getId() + "] start execution");
        }

        final HttpClientContext localContext = state.getLocalContext();

        if (original instanceof Configurable) {
            final RequestConfig config = ((Configurable) original).getConfig();
            if (config != null) {
                localContext.setRequestConfig(config);
            }
        }

        final List<URI> redirectLocations = localContext.getRedirectLocations();
        if (redirectLocations != null) {
            redirectLocations.clear();
        }

        final HttpRequestWrapper request = HttpRequestWrapper.wrap(original);
        final HttpRoute route = this.routePlanner.determineRoute(target, request, localContext);

        handler.setRoute(route);

        state.setMainRequest(request);
        handler.setCurrentRequest(request);

        prepareRequest(state, handler);
    }

    @Override
    public HttpRequest generateRequest(
            final InternalState state,
            final AbstractClientExchangeHandler<?> handler) throws IOException, HttpException {

        final HttpRoute route = handler.getRoute();

        handler.verifytRoute();

        if (!handler.isRouteEstablished()) {
            int step;
            loop:
            do {
                final HttpRoute fact = handler.getActualRoute();
                step = this.routeDirector.nextStep(route, fact);
                switch (step) {
                case HttpRouteDirector.CONNECT_TARGET:
                    handler.onRouteToTarget();
                    break;
                case HttpRouteDirector.CONNECT_PROXY:
                    handler.onRouteToProxy();
                    break;
                case HttpRouteDirector.TUNNEL_TARGET:
                    if (this.log.isDebugEnabled()) {
                        this.log.debug("[exchange: " + state.getId() + "] Tunnel required");
                    }
                    final HttpRequest connect = createConnectRequest(route, state);
                    handler.setCurrentRequest(HttpRequestWrapper.wrap(connect));
                    break loop;
                case HttpRouteDirector.TUNNEL_PROXY:
                    throw new HttpException("Proxy chains are not supported");
                case HttpRouteDirector.LAYER_PROTOCOL:
                    handler.onRouteUpgrade();
                    break;
                case HttpRouteDirector.UNREACHABLE:
                    throw new HttpException("Unable to establish route: " +
                            "planned = " + route + "; current = " + fact);
                case HttpRouteDirector.COMPLETE:
                    handler.onRouteComplete();
                    this.log.debug("Connection route established");
                    break;
                default:
                    throw new IllegalStateException("Unknown step indicator "
                            + step + " from RouteDirector.");
                }
            } while (step > HttpRouteDirector.COMPLETE);
        }

        final HttpClientContext localContext = state.getLocalContext();
        HttpRequestWrapper currentRequest = handler.getCurrentRequest();
        if (currentRequest == null) {
            currentRequest = state.getMainRequest();
            handler.setCurrentRequest(currentRequest);
        }

        if (handler.isRouteEstablished()) {
            state.incrementExecCount();
            if (state.getExecCount() > 1) {
                final HttpAsyncRequestProducer requestProducer = state.getRequestProducer();
                if (!requestProducer.isRepeatable() && state.isRequestContentProduced()) {
                    throw new NonRepeatableRequestException("Cannot retry request " +
                            "with a non-repeatable request entity.");
                }
                requestProducer.resetRequest();
            }
            if (this.log.isDebugEnabled()) {
                this.log.debug("[exchange: " + state.getId() + "] Attempt " + state.getExecCount() +
                    " to execute request");
            }

            if (!currentRequest.containsHeader(AUTH.WWW_AUTH_RESP)) {
                final AuthState targetAuthState = localContext.getTargetAuthState();
                if (this.log.isDebugEnabled()) {
                    this.log.debug("Target auth state: " + targetAuthState.getState());
                }
                this.authenticator.generateAuthResponse(currentRequest, targetAuthState, localContext);
            }
            if (!currentRequest.containsHeader(AUTH.PROXY_AUTH_RESP) && !route.isTunnelled()) {
                final AuthState proxyAuthState = localContext.getProxyAuthState();
                if (this.log.isDebugEnabled()) {
                    this.log.debug("Proxy auth state: " + proxyAuthState.getState());
                }
                this.authenticator.generateAuthResponse(currentRequest, proxyAuthState, localContext);
            }
        } else {
            if (!currentRequest.containsHeader(AUTH.PROXY_AUTH_RESP)) {
                final AuthState proxyAuthState = localContext.getProxyAuthState();
                if (this.log.isDebugEnabled()) {
                    this.log.debug("Proxy auth state: " + proxyAuthState.getState());
                }
                this.authenticator.generateAuthResponse(currentRequest, proxyAuthState, localContext);
            }
        }

        final NHttpClientConnection managedConn = handler.getConnection();
        localContext.setAttribute(HttpCoreContext.HTTP_CONNECTION, managedConn);
        final RequestConfig config = localContext.getRequestConfig();
        if (config.getSocketTimeout() > 0) {
            managedConn.setSocketTimeout(config.getSocketTimeout());
        }
        return currentRequest;
    }

    @Override
    public void produceContent(
            final InternalState state,
            final ContentEncoder encoder,
            final IOControl ioctrl) throws IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + state.getId() + "] produce content");
        }
        final HttpAsyncRequestProducer requestProducer = state.getRequestProducer();
        state.setRequestContentProduced();
        requestProducer.produceContent(encoder, ioctrl);
        if (encoder.isCompleted()) {
            requestProducer.resetRequest();
        }
    }

    @Override
    public void requestCompleted(
            final InternalState state,
            final AbstractClientExchangeHandler<?> handler) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + state.getId() + "] Request completed");
        }
        final HttpClientContext localContext = state.getLocalContext();
        final HttpAsyncRequestProducer requestProducer = state.getRequestProducer();
        requestProducer.requestCompleted(localContext);
    }

    @Override
    public void responseReceived(
            final HttpResponse response,
            final InternalState state,
            final AbstractClientExchangeHandler<?> handler) throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + state.getId() + "] Response received " + response.getStatusLine());
        }
        final HttpClientContext context = state.getLocalContext();
        context.setAttribute(HttpClientContext.HTTP_RESPONSE, response);
        this.httpProcessor.process(response, context);

        handler.setCurrentResponse(response);

        if (!handler.isRouteEstablished()) {
            final int status = response.getStatusLine().getStatusCode();
            if (status < 200) {
                throw new HttpException("Unexpected response to CONNECT request: " +
                        response.getStatusLine());
            }
            if (status == HttpStatus.SC_OK) {
                handler.onRouteTunnelToTarget();
                handler.setCurrentRequest(null);
            } else {
                if (!handleConnectResponse(state, handler)) {
                    state.setFinalResponse(response);
                }
            }
        } else {
            if (!handleResponse(state, handler)) {
                state.setFinalResponse(response);
            }
        }
        if (state.getFinalResponse() != null) {
            final HttpAsyncResponseConsumer<?> responseConsumer = state.getResponseConsumer();
            responseConsumer.responseReceived(response);
        }
    }

    @Override
    public void consumeContent(
            final InternalState state,
            final ContentDecoder decoder,
            final IOControl ioctrl) throws IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + state.getId() + "] Consume content");
        }
        if (state.getFinalResponse() != null) {
            final HttpAsyncResponseConsumer<?> responseConsumer = state.getResponseConsumer();
            responseConsumer.consumeContent(decoder, ioctrl);
        } else {
            final ByteBuffer tmpbuf = state.getTmpbuf();
            tmpbuf.clear();
            decoder.read(tmpbuf);
        }
    }

    @Override
    public void responseCompleted(
            final InternalState state,
            final AbstractClientExchangeHandler<?> handler) throws IOException, HttpException {
        final HttpClientContext localContext = state.getLocalContext();
        final HttpResponse currentResponse = handler.getCurrentResponse();

        if (!handler.isRouteEstablished()) {
            final int status = currentResponse.getStatusLine().getStatusCode();
            if (status == HttpStatus.SC_OK) {
                handler.setCurrentResponse(null);
                return;
            }
        }

        final boolean keepAlive = handler.manageConnectionPersistence();
        if (!keepAlive) {
            handler.releaseConnection();
            final AuthState proxyAuthState = localContext.getProxyAuthState();
            if (proxyAuthState.getState() == AuthProtocolState.SUCCESS
                    && proxyAuthState.getAuthScheme() != null
                    && proxyAuthState.getAuthScheme().isConnectionBased()) {
                if (this.log.isDebugEnabled()) {
                    this.log.debug("[exchange: " + state.getId() + "] Resetting proxy auth state");
                }
                proxyAuthState.reset();
            }
            final AuthState targetAuthState = localContext.getTargetAuthState();
            if (targetAuthState.getState() == AuthProtocolState.SUCCESS
                    && targetAuthState.getAuthScheme() != null
                    && targetAuthState.getAuthScheme().isConnectionBased()) {
                if (this.log.isDebugEnabled()) {
                    this.log.debug("[exchange: " + state.getId() + "] Resetting target auth state");
                }
                targetAuthState.reset();
            }
        }

        Object userToken = localContext.getUserToken();
        if (userToken == null) {
            userToken = this.userTokenHandler.getUserToken(localContext);
            localContext.setAttribute(HttpClientContext.USER_TOKEN, userToken);
        }

        if (state.getFinalResponse() != null) {
            final HttpAsyncResponseConsumer<?> responseConsumer = state.getResponseConsumer();
            responseConsumer.responseCompleted(localContext);
            if (this.log.isDebugEnabled()) {
                this.log.debug("[exchange: " + state.getId() + "] Response processed");
            }
            handler.releaseConnection();
        } else {
            if (state.getRedirect() != null) {
                final HttpUriRequest redirect = state.getRedirect();
                final URI uri = redirect.getURI();
                if (this.log.isDebugEnabled()) {
                    this.log.debug("[exchange: " + state.getId() + "] Redirecting to '" + uri + "'");
                }
                state.setRedirect(null);

                final HttpHost newTarget = URIUtils.extractHost(uri);
                if (newTarget == null) {
                    throw new ProtocolException("Redirect URI does not specify a valid host name: " + uri);
                }

                // Reset auth states if redirecting to another host
                final HttpRoute route = handler.getRoute();
                if (!route.getTargetHost().equals(newTarget)) {
                    final AuthState targetAuthState = localContext.getTargetAuthState();
                    if (this.log.isDebugEnabled()) {
                        this.log.debug("[exchange: " + state.getId() + "] Resetting target auth state");
                    }
                    targetAuthState.reset();
                    final AuthState proxyAuthState = localContext.getProxyAuthState();
                    final AuthScheme authScheme = proxyAuthState.getAuthScheme();
                    if (authScheme != null && authScheme.isConnectionBased()) {
                        if (this.log.isDebugEnabled()) {
                            this.log.debug("[exchange: " + state.getId() + "] Resetting proxy auth state");
                        }
                        proxyAuthState.reset();
                    }
                }

                if (!redirect.headerIterator().hasNext()) {
                    final HttpRequest original = state.getMainRequest().getOriginal();
                    redirect.setHeaders(original.getAllHeaders());
                }

                final HttpRequestWrapper newRequest = HttpRequestWrapper.wrap(redirect);
                final HttpRoute newRoute = this.routePlanner.determineRoute(
                    newTarget, newRequest, localContext);
                if (!route.equals(newRoute)) {
                    handler.releaseConnection();
                }
                handler.setRoute(newRoute);
                handler.setCurrentRequest(newRequest);
                state.setMainRequest(newRequest);
                prepareRequest(state, handler);
            }
        }
        handler.setCurrentResponse(null);
    }

    private void rewriteRequestURI(
            final HttpRequestWrapper request,
            final HttpRoute route) throws ProtocolException {
        try {
            URI uri = request.getURI();
            if (uri != null) {
                if (route.getProxyHost() != null && !route.isTunnelled()) {
                    // Make sure the request URI is absolute
                    if (!uri.isAbsolute()) {
                        final HttpHost target = route.getTargetHost();
                        uri = URIUtils.rewriteURI(uri, target, true);
                    } else {
                        uri = URIUtils.rewriteURI(uri);
                    }
                } else {
                    // Make sure the request URI is relative
                    if (uri.isAbsolute()) {
                        uri = URIUtils.rewriteURI(uri, null, true);
                    } else {
                        uri = URIUtils.rewriteURI(uri);
                    }
                }
                request.setURI(uri);
            }
        } catch (final URISyntaxException ex) {
            throw new ProtocolException("Invalid URI: " +
                    request.getRequestLine().getUri(), ex);
        }
    }

    private void prepareRequest(
            final InternalState state,
            final AbstractClientExchangeHandler<?> handler) throws IOException, HttpException {
        final HttpClientContext localContext = state.getLocalContext();
        final HttpRequestWrapper currentRequest = handler.getCurrentRequest();
        final HttpRoute route = handler.getRoute();

        final HttpRequest original = currentRequest.getOriginal();
        URI uri = null;
        if (original instanceof HttpUriRequest) {
            uri = ((HttpUriRequest) original).getURI();
        } else {
            final String uriString = original.getRequestLine().getUri();
            try {
                uri = URI.create(uriString);
            } catch (final IllegalArgumentException ex) {
                if (this.log.isDebugEnabled()) {
                    this.log.debug("Unable to parse '" + uriString + "' as a valid URI; " +
                        "request URI and Host header may be inconsistent", ex);
                }
            }

        }
        currentRequest.setURI(uri);

        // Re-write request URI if needed
        rewriteRequestURI(currentRequest, route);

        HttpHost target = null;
        if (uri != null && uri.isAbsolute() && uri.getHost() != null) {
            target = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
        }
        if (target == null) {
            target = route.getTargetHost();
        }

        // Get user info from the URI
        if (uri != null) {
            final String userinfo = uri.getUserInfo();
            if (userinfo != null) {
                final CredentialsProvider credsProvider = localContext.getCredentialsProvider();
                credsProvider.setCredentials(
                        new AuthScope(target),
                        new UsernamePasswordCredentials(userinfo));
            }
        }

        localContext.setAttribute(HttpClientContext.HTTP_REQUEST, currentRequest);
        localContext.setAttribute(HttpClientContext.HTTP_TARGET_HOST, target);
        localContext.setAttribute(HttpClientContext.HTTP_ROUTE, route);
        this.httpProcessor.process(currentRequest, localContext);
    }

    private HttpRequest createConnectRequest(
            final HttpRoute route, final InternalState state) throws IOException, HttpException {
        // see RFC 2817, section 5.2 and
        // INTERNET-DRAFT: Tunneling TCP based protocols through
        // Web proxy servers
        final HttpHost target = route.getTargetHost();
        final String host = target.getHostName();
        final int port = target.getPort();
        final StringBuilder buffer = new StringBuilder(host.length() + 6);
        buffer.append(host);
        buffer.append(':');
        buffer.append(Integer.toString(port));
        final HttpRequest request = new BasicHttpRequest("CONNECT", buffer.toString(), HttpVersion.HTTP_1_1);
        final HttpClientContext localContext = state.getLocalContext();
        this.proxyHttpProcessor.process(request, localContext);
        return request;
    }

    private boolean handleConnectResponse(
            final InternalState state,
            final AbstractClientExchangeHandler<?> handler) throws HttpException {
        final HttpClientContext localContext = state.getLocalContext();
        final RequestConfig config = localContext.getRequestConfig();
        if (config.isAuthenticationEnabled()) {
            final CredentialsProvider credsProvider = localContext.getCredentialsProvider();
            if (credsProvider != null) {
                final HttpRoute route = handler.getRoute();
                final HttpHost proxy = route.getProxyHost();
                final HttpResponse currentResponse = handler.getCurrentResponse();
                final AuthState proxyAuthState = localContext.getProxyAuthState();
                if (this.authenticator.isAuthenticationRequested(proxy, currentResponse,
                        this.proxyAuthStrategy, proxyAuthState, localContext)) {
                    return this.authenticator.handleAuthChallenge(proxy, currentResponse,
                            this.proxyAuthStrategy, proxyAuthState, localContext);
                }
            }
        }
        return false;
    }

    private boolean handleResponse(
            final InternalState state,
            final AbstractClientExchangeHandler<?> handler) throws HttpException {
        final HttpClientContext localContext = state.getLocalContext();
        final RequestConfig config = localContext.getRequestConfig();
        if (config.isAuthenticationEnabled()) {
            if (needAuthentication(state, handler)) {
                // discard previous auth headers
                final HttpRequestWrapper currentRequest = handler.getCurrentRequest();
                final HttpRequest original = currentRequest.getOriginal();
                if (!original.containsHeader(AUTH.WWW_AUTH_RESP)) {
                    currentRequest.removeHeaders(AUTH.WWW_AUTH_RESP);
                }
                if (!original.containsHeader(AUTH.PROXY_AUTH_RESP)) {
                    currentRequest.removeHeaders(AUTH.PROXY_AUTH_RESP);
                }
                return true;
            }
        }
        if (config.isRedirectsEnabled()) {
            final HttpRequest currentRequest = handler.getCurrentRequest();
            final HttpResponse currentResponse = handler.getCurrentResponse();
            if (this.redirectStrategy.isRedirected(currentRequest, currentResponse, localContext)) {
                final int maxRedirects = config.getMaxRedirects() >= 0 ? config.getMaxRedirects() : 100;
                if (state.getRedirectCount() >= maxRedirects) {
                    throw new RedirectException("Maximum redirects (" + maxRedirects + ") exceeded");
                }
                state.incrementRedirectCount();
                final HttpUriRequest redirect = this.redirectStrategy.getRedirect(currentRequest, currentResponse,
                    localContext);
                state.setRedirect(redirect);
                return true;
            }
        }
        return false;
    }

    private boolean needAuthentication(
            final InternalState state,
            final AbstractClientExchangeHandler<?> handler) throws HttpException {
        final HttpClientContext localContext = state.getLocalContext();
        final CredentialsProvider credsProvider = localContext.getCredentialsProvider();
        if (credsProvider != null) {
            final HttpRoute route = handler.getRoute();
            final HttpResponse currentResponse = handler.getCurrentResponse();
            HttpHost target = localContext.getTargetHost();
            if (target == null) {
                target = route.getTargetHost();
            }
            if (target.getPort() < 0) {
                target = new HttpHost(
                        target.getHostName(),
                        route.getTargetHost().getPort(),
                        target.getSchemeName());
            }
            final AuthState targetAuthState = localContext.getTargetAuthState();
            final AuthState proxyAuthState = localContext.getProxyAuthState();

            final boolean targetAuthRequested = this.authenticator.isAuthenticationRequested(
                    target, currentResponse, this.targetAuthStrategy, targetAuthState, localContext);

            HttpHost proxy = route.getProxyHost();
            // if proxy is not set use target host instead
            if (proxy == null) {
                proxy = route.getTargetHost();
            }
            final boolean proxyAuthRequested = this.authenticator.isAuthenticationRequested(
                    proxy, currentResponse, this.proxyAuthStrategy, proxyAuthState, localContext);

            if (targetAuthRequested) {
                return this.authenticator.handleAuthChallenge(target, currentResponse,
                        this.targetAuthStrategy, targetAuthState, localContext);
            }
            if (proxyAuthRequested) {
                return this.authenticator.handleAuthChallenge(proxy, currentResponse,
                        this.proxyAuthStrategy, proxyAuthState, localContext);
            }
        }
        return false;
    }

}
