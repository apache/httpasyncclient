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
import java.nio.channels.SelectionKey;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.client.ClientParamsStack;
import org.apache.http.impl.client.EntityEnclosingRequestWrapper;
import org.apache.http.impl.client.RequestWrapper;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.HttpAsyncExchangeHandler;
import org.apache.http.nio.concurrent.BasicFuture;
import org.apache.http.nio.concurrent.FutureCallback;
import org.apache.http.nio.conn.IOSessionManager;
import org.apache.http.nio.conn.ManagedIOSession;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;

class DefaultAsyncRequestDirector<T> implements HttpAsyncExchangeHandler<T> {

    public static final String HTTP_EXCHANGE_HANDLER = "http.nio.async-exchange-handler";

    private final BasicFuture<T> resultFuture;
    private final HttpAsyncExchangeHandler<T> handler;
    private final IOSessionManager<HttpRoute> sessmrg;
    private final HttpProcessor httppocessor;
    private final HttpContext localContext;
    private final HttpParams clientParams;

    private HttpRequest originalRequest;
    private RequestWrapper currentRequest;
    private HttpRoute route;
    private Future<ManagedIOSession> sessionFuture;
    private ManagedIOSession managedSession;

    public DefaultAsyncRequestDirector(
            final HttpAsyncExchangeHandler<T> handler,
            final FutureCallback<T> callback,
            final IOSessionManager<HttpRoute> sessmrg,
            final HttpProcessor httppocessor,
            final HttpContext localContext,
            final HttpParams clientParams) {
        super();
        this.handler = handler;
        this.resultFuture = new BasicFuture<T>(callback);
        this.sessmrg = sessmrg;
        this.httppocessor = httppocessor;
        this.localContext = localContext;
        this.clientParams = clientParams;
    }

    public void start() {
        try {
            HttpHost target = this.handler.getTarget();
            this.originalRequest = this.handler.generateRequest();
            HttpParams params = new ClientParamsStack(
                    null, this.clientParams, this.originalRequest.getParams(), null);
            this.currentRequest = wrapRequest(this.originalRequest);
            this.currentRequest.setParams(params);
            this.route = determineRoute(target, this.currentRequest, this.localContext);

            long connectTimeout = HttpConnectionParams.getConnectionTimeout(params);
            Object userToken = this.localContext.getAttribute(ClientContext.USER_TOKEN);

            this.sessionFuture = this.sessmrg.leaseSession(
                    this.route, userToken,
                    connectTimeout, TimeUnit.MILLISECONDS,
                    new InternalFutureCallback());
        } catch (HttpException ex) {
            failed(ex);
        } catch (IOException ex) {
            failed(ex);
        }
    }

    public Future<T> getResultFuture() {
        return this.resultFuture;
    }

    public HttpHost getTarget() {
        return this.handler.getTarget();
    }

    public HttpRequest generateRequest() throws IOException, HttpException {

        HttpHost target = (HttpHost) this.currentRequest.getParams().getParameter(
                ClientPNames.VIRTUAL_HOST);
        if (target == null) {
            target = this.route.getTargetHost();
        }

        HttpHost proxy = this.route.getProxyHost();

        // Reset headers on the request wrapper
        this.currentRequest.resetHeaders();
        // Re-write request URI if needed
        rewriteRequestURI(this.currentRequest, this.route);

        this.localContext.setAttribute(ExecutionContext.HTTP_REQUEST, this.currentRequest);
        this.localContext.setAttribute(ExecutionContext.HTTP_TARGET_HOST, target);
        this.localContext.setAttribute(ExecutionContext.HTTP_PROXY_HOST, proxy);
        this.httppocessor.process(this.currentRequest, this.localContext);
        return this.currentRequest;
    }

    public void produceContent(
            final ContentEncoder encoder, final IOControl ioctrl) throws IOException {
        this.handler.produceContent(encoder, ioctrl);
    }

    public void responseReceived(final HttpResponse response) throws IOException, HttpException {
        response.setParams(this.currentRequest.getParams());
        this.localContext.setAttribute(ExecutionContext.HTTP_RESPONSE, response);
        this.httppocessor.process(response, this.localContext);
        this.handler.responseReceived(response);
    }

    public void consumeContent(
            final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
        this.handler.consumeContent(decoder, ioctrl);
    }

    public synchronized void completed() {
        try {
            if (this.managedSession != null) {
                this.managedSession.releaseSession();
            }
            this.managedSession = null;
            this.handler.completed();
            this.resultFuture.completed(this.handler.getResult());
        } catch (RuntimeException runex) {
            this.resultFuture.failed(runex);
            throw runex;
        }
    }

    public synchronized void failed(final Exception ex) {
        try {
            this.sessionFuture.cancel(true);
            if (this.managedSession != null) {
                this.managedSession.abortSession();
            }
            this.managedSession = null;
            this.handler.failed(ex);
            this.resultFuture.failed(ex);
        } catch (RuntimeException runex) {
            this.resultFuture.failed(ex);
            throw runex;
        }
    }

    public synchronized void cancel() {
        try {
            this.sessionFuture.cancel(true);
            if (this.managedSession != null) {
                this.managedSession.abortSession();
            }
            this.managedSession = null;
            this.handler.cancel();
            this.resultFuture.cancel(true);
        } catch (RuntimeException runex) {
            this.resultFuture.failed(runex);
            throw runex;
        }
    }

    public boolean isCompleted() {
        return this.handler.isCompleted();
    }

    public T getResult() {
        return this.handler.getResult();
    }

    private synchronized void sessionRequestCompleted(final ManagedIOSession session) {
        this.managedSession = session;
        IOSession iosession = session.getSession();
        iosession.setAttribute(HTTP_EXCHANGE_HANDLER, this);
        iosession.setEvent(SelectionKey.OP_WRITE);
    }

    private synchronized void sessionRequestFailed(final Exception ex) {
        try {
            this.handler.failed(ex);
        } finally {
            this.resultFuture.failed(ex);
        }
    }

    private synchronized void sessionRequestCancelled() {
        try {
            this.handler.cancel();
        } finally {
            this.resultFuture.cancel(true);
        }
    }

    class InternalFutureCallback implements FutureCallback<ManagedIOSession> {

        public void completed(final ManagedIOSession session) {
            sessionRequestCompleted(session);
        }

        public void failed(final Exception ex) {
            sessionRequestFailed(ex);
        }

        public void cancelled() {
            sessionRequestCancelled();
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
            throw new IllegalStateException("Target host must not be null, or set in parameters.");
        }
        return new HttpRoute(target);
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
            throw new ProtocolException("Invalid URI: " +
                    request.getRequestLine().getUri(), ex);
        }
    }

}
