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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ProtocolException;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.conn.routing.BasicRouteDirector;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.HttpRouteDirector;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.impl.client.ClientParamsStack;
import org.apache.http.impl.client.EntityEnclosingRequestWrapper;
import org.apache.http.impl.client.RequestWrapper;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.HttpAsyncExchangeHandler;
import org.apache.http.nio.client.HttpAsyncRequestProducer;
import org.apache.http.nio.client.HttpAsyncResponseConsumer;
import org.apache.http.nio.concurrent.BasicFuture;
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
    private final BasicFuture<T> resultFuture;
    private final ClientConnectionManager connmgr;
    private final HttpProcessor httppocessor;
    private final HttpRoutePlanner routePlanner;
    private final HttpRouteDirector routeDirector;
    private final HttpParams clientParams;

    private ClientParamsStack params;
    private RequestWrapper request;
    private RequestWrapper current;
    private HttpRoute route;
    private boolean routeEstablished;
    private Future<ManagedClientConnection> connFuture;
    private ManagedClientConnection managedConn;
    private HttpResponse response;

    public DefaultAsyncRequestDirector(
            final Log log,
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final HttpContext localContext,
            final FutureCallback<T> callback,
            final ClientConnectionManager connmgr,
            final HttpProcessor httppocessor,
            final HttpRoutePlanner routePlanner,
            final HttpParams clientParams) {
        super();
        this.log = log;
        this.requestProducer = requestProducer;
        this.responseConsumer = responseConsumer;
        this.localContext = localContext;
        this.resultFuture = new BasicFuture<T>(callback);
        this.connmgr = connmgr;
        this.httppocessor = httppocessor;
        this.routePlanner = routePlanner;
        this.routeDirector = new BasicRouteDirector();
        this.clientParams = clientParams;
    }

    public synchronized void start() {
        try {
            HttpHost target = this.requestProducer.getTarget();
            HttpRequest request = this.requestProducer.generateRequest();
            this.params = new ClientParamsStack(null, this.clientParams, request.getParams(), null);
            this.request = wrapRequest(request);
            this.request.setParams(this.params);
            this.route = determineRoute(target, this.request, this.localContext);
            long connectTimeout = HttpConnectionParams.getConnectionTimeout(this.params);
            Object userToken = this.localContext.getAttribute(ClientContext.USER_TOKEN);
            this.connFuture = this.connmgr.leaseConnection(
                    this.route, userToken,
                    connectTimeout, TimeUnit.MILLISECONDS,
                    new InternalFutureCallback());
        } catch (Exception ex) {
            failed(ex);
        }
    }

    public Future<T> getResultFuture() {
        return this.resultFuture;
    }

    public HttpHost getTarget() {
        return this.requestProducer.getTarget();
    }

    public synchronized HttpRequest generateRequest() throws IOException, HttpException {
        if (!this.routeEstablished) {
            int step;
            do {
                HttpRoute fact = this.managedConn.getRoute();
                step = this.routeDirector.nextStep(this.route, fact);
                switch (step) {
                case HttpRouteDirector.CONNECT_TARGET:
                case HttpRouteDirector.CONNECT_PROXY:
                    break;
                case HttpRouteDirector.TUNNEL_TARGET:
                    this.log.debug("Tunnel required");
                    HttpRequest connect = createConnectRequest(this.route);
                    this.current = wrapRequest(connect);
                    this.current.setParams(this.params);
                    break;
                case HttpRouteDirector.TUNNEL_PROXY:
                    throw new HttpException("Proxy chains are not supported");
                case HttpRouteDirector.LAYER_PROTOCOL:
                    managedConn.layerProtocol(this.localContext, this.params);
                    break;
                case HttpRouteDirector.UNREACHABLE:
                    throw new HttpException("Unable to establish route: " +
                            "planned = " + this.route + "; current = " + fact);
                case HttpRouteDirector.COMPLETE:
                    this.routeEstablished = true;
                    break;
                default:
                    throw new IllegalStateException("Unknown step indicator "
                            + step + " from RouteDirector.");
                }
            } while (step > HttpRouteDirector.COMPLETE && this.current == null);
        }

        HttpHost target = (HttpHost) this.params.getParameter(ClientPNames.VIRTUAL_HOST);
        if (target == null) {
            target = this.route.getTargetHost();
        }
        HttpHost proxy = this.route.getProxyHost();

        if (this.current == null) {
            this.current = this.request;
            // Re-write request URI if needed
            rewriteRequestURI(this.current, this.route);
        }
        // Reset headers on the request wrapper
        this.current.resetHeaders();

        this.localContext.setAttribute(ExecutionContext.HTTP_REQUEST, this.current);
        this.localContext.setAttribute(ExecutionContext.HTTP_TARGET_HOST, target);
        this.localContext.setAttribute(ExecutionContext.HTTP_PROXY_HOST, proxy);
        this.httppocessor.process(this.current, this.localContext);
        if (this.log.isDebugEnabled()) {
            this.log.debug("Request submitted: " + this.current.getRequestLine());
        }
        return this.current;
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
            this.log.debug("Response received: " + response.getStatusLine());
        }
        response.setParams(this.params);
        this.localContext.setAttribute(ExecutionContext.HTTP_RESPONSE, response);
        this.httppocessor.process(response, this.localContext);

        int status = response.getStatusLine().getStatusCode();

        if (!this.routeEstablished) {
            if (this.current.getMethod().equalsIgnoreCase("CONNECT") && status == HttpStatus.SC_OK) {
                this.managedConn.tunnelTarget(this.params);
            } else {
                this.response = response;
            }
        } else {
            this.response = response;
        }
        if (this.response != null) {
            this.responseConsumer.responseReceived(response);
        }
        this.current = null;
    }

    public synchronized void consumeContent(
            final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
        if (this.response != null) {
            this.responseConsumer.consumeContent(decoder, ioctrl);
        } else {
            this.log.debug("Discard intermediate response content");
        }
    }

    private void releaseResources() {
        if (this.managedConn != null) {
            try {
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
                this.resultFuture.failed(ex);
            } finally {
                releaseResources();
            }
        }
    }

    public synchronized void responseCompleted() {
        this.log.debug("Response completed");
        try {
            if (this.response != null) {
                this.responseConsumer.responseCompleted();
                if (this.responseConsumer.isDone()) {
                    this.log.debug("Response processing completed");
                    this.resultFuture.completed(this.responseConsumer.getResult());
                    releaseResources();
                }
            } else {
                this.managedConn.requestOutput();
            }
        } catch (RuntimeException runex) {
            failed(runex);
            throw runex;
        }
    }

    public synchronized void cancel() {
        this.log.debug("HTTP exchange cancelled");
        try {
            this.responseConsumer.cancel();
            this.resultFuture.cancel(true);
            releaseResources();
        } catch (RuntimeException runex) {
            failed(runex);
            throw runex;
        }
    }

    public boolean isDone() {
        return this.responseConsumer.isDone();
    }

    public T getResult() {
        return this.responseConsumer.getResult();
    }

    private synchronized void connectionRequestCompleted(final ManagedClientConnection conn) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("Connection request suceeded: " + conn);
        }
        try {
            if (!conn.isOpen()) {
                conn.open(this.route, this.localContext, this.params);
            }
            this.managedConn = conn;
            this.managedConn.getContext().setAttribute(HTTP_EXCHANGE_HANDLER, this);
            this.managedConn.requestOutput();
            this.routeEstablished = this.route.equals(conn.getRoute());
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
            this.resultFuture.failed(ex);
        }
    }

    private synchronized void connectionRequestCancelled() {
        this.log.debug("Connection request cancelled");
        try {
            this.requestProducer.resetRequest();
            this.responseConsumer.cancel();
        } finally {
            this.resultFuture.cancel(true);
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

}
