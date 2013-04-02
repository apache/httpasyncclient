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
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.AuthenticationStrategy;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.UserTokenHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.Configurable;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.ExecutionContext;

class MinimalClientExec implements InternalClientExec {

    private final Log log = LogFactory.getLog(getClass());

    private final NHttpClientConnectionManager connmgr;
    private final HttpRoutePlanner routePlanner;
    private final ConnectionKeepAliveStrategy keepaliveStrategy;

    public MinimalClientExec(
            final NHttpClientConnectionManager connmgr,
            final HttpRoutePlanner routePlanner,
            final ConnectionReuseStrategy reuseStrategy,
            final ConnectionKeepAliveStrategy keepaliveStrategy,
            final RedirectStrategy redirectStrategy,
            final AuthenticationStrategy targetAuthStrategy,
            final AuthenticationStrategy proxyAuthStrategy,
            final UserTokenHandler userTokenHandler) {
        super();
        this.connmgr = connmgr;
        this.routePlanner = routePlanner;
        this.keepaliveStrategy = keepaliveStrategy;
    }

    public void prepare(
            final InternalState state,
            final HttpHost target,
            final HttpRequest original) throws HttpException, IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + state.getId() + "] start execution");
        }

        final HttpClientContext localContext = state.getLocalContext();
        final HttpRequestWrapper request = HttpRequestWrapper.wrap(original);
        final HttpRoute route = this.routePlanner.determineRoute(target, request, localContext);

        state.setRoute(route);
        state.setMainRequest(request);
        state.setCurrentRequest(request);

        if (original instanceof Configurable) {
            final RequestConfig config = ((Configurable) original).getConfig();
            if (config != null) {
                localContext.setRequestConfig(config);
            }
        }

        HttpHost host = null;
        if (original instanceof HttpUriRequest) {
            final URI uri = ((HttpUriRequest) original).getURI();
            if (uri.isAbsolute()) {
                host = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
            }
        }
        if (host == null) {
            host = target;
        }

        localContext.setAttribute(ExecutionContext.HTTP_TARGET_HOST, host);
        localContext.setAttribute(ClientContext.ROUTE, route);
    }

    public HttpRequest generateRequest(
            final InternalState state,
            final InternalConnManager connManager) throws IOException, HttpException {
        if (state.isRouteEstablished()) {
            final HttpClientContext localContext = state.getLocalContext();
            final HttpRoute route = state.getRoute();
            final NHttpClientConnection managedConn = connManager.getConnection();
            this.connmgr.initialize(managedConn, route, localContext);
            this.connmgr.routeComplete(managedConn, route, localContext);

        }
        if (state.getCurrentRequest() == null) {
            state.setCurrentRequest(state.getMainRequest());
        }
        return state.getCurrentRequest();
    }

    public void produceContent(
            final InternalState state,
            final ContentEncoder encoder,
            final IOControl ioctrl) throws IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + state.getId() + "] produce content");
        }
        final HttpAsyncRequestProducer requestProducer = state.getRequestProducer();
        requestProducer.produceContent(encoder, ioctrl);
        if (encoder.isCompleted()) {
            requestProducer.resetRequest();
        }
    }

    public void requestCompleted(final InternalState state) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + state.getId() + "] Request completed");
        }
        final HttpClientContext localContext = state.getLocalContext();
        final HttpAsyncRequestProducer requestProducer = state.getRequestProducer();
        requestProducer.requestCompleted(localContext);
    }

    public void responseReceived(
            final InternalState state,
            final HttpResponse response) throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + state.getId() + "] Response received " + response.getStatusLine());
        }
        state.setCurrentResponse(response);
        final HttpAsyncResponseConsumer<?> responseConsumer = state.getResponseConsumer();
        responseConsumer.responseReceived(response);
    }

    public void consumeContent(
            final InternalState state,
            final ContentDecoder decoder,
            final IOControl ioctrl) throws IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + state.getId() + "] Consume content");
        }
        final HttpAsyncResponseConsumer<?> responseConsumer = state.getResponseConsumer();
        responseConsumer.consumeContent(decoder, ioctrl);
    }

    public void responseCompleted(
            final InternalState state,
            final InternalConnManager connManager) throws HttpException {
        final HttpClientContext localContext = state.getLocalContext();
        final HttpResponse currentResponse = state.getCurrentResponse();

        if (connManager.getConnection().isOpen()) {
            final long validDuration = this.keepaliveStrategy.getKeepAliveDuration(
                    currentResponse, localContext);
            if (this.log.isDebugEnabled()) {
                String s;
                if (validDuration > 0) {
                    s = "for " + validDuration + " " + TimeUnit.MILLISECONDS;
                } else {
                    s = "indefinitely";
                }
                this.log.debug("[exchange: " + state.getId() + "] Connection can be kept alive " + s);
            }
            state.setValidDuration(validDuration);
            state.setReusable();
        } else {
            if (this.log.isDebugEnabled()) {
                this.log.debug("[exchange: " + state.getId() + "] Connection cannot be kept alive");
            }
            state.setNonReusable();
        }
        final HttpAsyncResponseConsumer<?> responseConsumer = state.getResponseConsumer();
        responseConsumer.responseCompleted(localContext);
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + state.getId() + "] Response processed");
        }
        state.setFinalResponse(currentResponse);
    }

}
