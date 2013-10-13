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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.Configurable;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpProcessor;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

class MinimalClientExec implements InternalClientExec {

    private final Log log = LogFactory.getLog(getClass());

    private final NHttpClientConnectionManager connmgr;
    private final HttpProcessor httpProcessor;
    private final ConnectionReuseStrategy connReuseStrategy;
    private final ConnectionKeepAliveStrategy keepaliveStrategy;

    public MinimalClientExec(
            final NHttpClientConnectionManager connmgr,
            final HttpProcessor httpProcessor,
            final ConnectionReuseStrategy connReuseStrategy,
            final ConnectionKeepAliveStrategy keepaliveStrategy) {
        super();
        this.connmgr = connmgr;
        this.httpProcessor = httpProcessor;
        this.connReuseStrategy = connReuseStrategy;
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

        if (original instanceof Configurable) {
            final RequestConfig config = ((Configurable) original).getConfig();
            if (config != null) {
                localContext.setRequestConfig(config);
            }
        }

        final HttpRequestWrapper request = HttpRequestWrapper.wrap(original);
        final HttpRoute route = new HttpRoute(target);
        state.setRoute(route);
        state.setMainRequest(request);
        state.setCurrentRequest(request);

        localContext.setAttribute(HttpClientContext.HTTP_REQUEST, request);
        localContext.setAttribute(HttpClientContext.HTTP_TARGET_HOST, target);
        localContext.setAttribute(HttpClientContext.HTTP_ROUTE, route);
        this.httpProcessor.process(request, localContext);
    }

    public HttpRequest generateRequest(
            final InternalState state,
            final InternalConnManager connManager) throws IOException, HttpException {
        final HttpClientContext localContext = state.getLocalContext();
        final HttpRoute route = state.getRoute();
        final NHttpClientConnection localConn = connManager.getConnection();
        if (!this.connmgr.isRouteComplete(localConn)) {
            this.connmgr.startRoute(localConn, route, localContext);
            this.connmgr.routeComplete(localConn, route, localContext);
        }
        localContext.setAttribute(HttpCoreContext.HTTP_CONNECTION, localConn);
        final RequestConfig config = localContext.getRequestConfig();
        if (config.getSocketTimeout() > 0) {
            localConn.setSocketTimeout(config.getSocketTimeout());
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
        state.setRequestContentProduced();
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
        final HttpClientContext localContext = state.getLocalContext();
        localContext.setAttribute(HttpClientContext.HTTP_RESPONSE, response);
        this.httpProcessor.process(response, localContext);
        state.setFinalResponse(response);

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
            final InternalConnManager connManager) throws IOException, HttpException {
        final HttpClientContext localContext = state.getLocalContext();
        final HttpResponse response = state.getFinalResponse();
        if (this.connReuseStrategy.keepAlive(response, localContext)) {
            final long validDuration = this.keepaliveStrategy.getKeepAliveDuration(
                    response, localContext);
            if (this.log.isDebugEnabled()) {
                final String s;
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
        connManager.releaseConnection();
    }

}
