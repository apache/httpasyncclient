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

import org.apache.commons.logging.Log;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.Configurable;
import org.apache.http.client.methods.HttpExecutionAware;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.concurrent.BasicFuture;
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

/**
 * Default implementation of {@link org.apache.http.nio.protocol.HttpAsyncClientExchangeHandler}.
 * <p>
 * Instances of this class are expected to be accessed by one thread at a time only.
 * The {@link #cancel()} method can be called concurrently by multiple threads.
 */
class MinimalClientExchangeHandlerImpl<T> extends AbstractClientExchangeHandler {

    private final HttpAsyncRequestProducer requestProducer;
    private final HttpAsyncResponseConsumer<T> responseConsumer;
    private final HttpClientContext localContext;
    private final BasicFuture<T> resultFuture;
    private final HttpProcessor httpProcessor;

    public MinimalClientExchangeHandlerImpl(
            final Log log,
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final HttpClientContext localContext,
            final BasicFuture<T> resultFuture,
            final NHttpClientConnectionManager connmgr,
            final HttpProcessor httpProcessor,
            final ConnectionReuseStrategy connReuseStrategy,
            final ConnectionKeepAliveStrategy keepaliveStrategy) {
        super(log, localContext, resultFuture, connmgr, connReuseStrategy, keepaliveStrategy);
        this.requestProducer = requestProducer;
        this.responseConsumer = responseConsumer;
        this.localContext = localContext;
        this.resultFuture = resultFuture;
        this.httpProcessor = httpProcessor;
    }

    @Override
    void releaseResources() {
        try {
            this.requestProducer.close();
        } catch (final IOException ex) {
            this.log.debug("I/O error closing request producer", ex);
        }
        try {
            this.responseConsumer.close();
        } catch (final IOException ex) {
            this.log.debug("I/O error closing response consumer", ex);
        }
    }

    @Override
    void executionFailed(final Exception ex) {
        this.requestProducer.failed(ex);
        this.responseConsumer.failed(ex);
    }

    @Override
    boolean executionCancelled() {
        final boolean cancelled = this.responseConsumer.cancel();

        final T result = this.responseConsumer.getResult();
        final Exception ex = this.responseConsumer.getException();
        if (ex != null) {
            this.resultFuture.failed(ex);
        } else if (result != null) {
            this.resultFuture.completed(result);
        } else {
            this.resultFuture.cancel();
        }
        return cancelled;
    }

    public void start() throws HttpException, IOException {
        final HttpHost target = this.requestProducer.getTarget();
        final HttpRequest original = this.requestProducer.generateRequest();

        if (original instanceof HttpExecutionAware) {
            ((HttpExecutionAware) original).setCancellable(this);
        }
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + getId() + "] start execution");
        }

        if (original instanceof Configurable) {
            final RequestConfig config = ((Configurable) original).getConfig();
            if (config != null) {
                this.localContext.setRequestConfig(config);
            }
        }

        final HttpRequestWrapper request = HttpRequestWrapper.wrap(original);
        final HttpRoute route = new HttpRoute(target);
        setCurrentRequest(request);
        setRoute(route);

        this.localContext.setAttribute(HttpClientContext.HTTP_REQUEST, request);
        this.localContext.setAttribute(HttpClientContext.HTTP_TARGET_HOST, target);
        this.localContext.setAttribute(HttpClientContext.HTTP_ROUTE, route);

        this.httpProcessor.process(request, this.localContext);

        requestConnection();
    }

    @Override
    public HttpRequest generateRequest() throws IOException, HttpException {
        verifytRoute();
        if (!isRouteEstablished()) {
            onRouteToTarget();
            onRouteComplete();
        }

        final NHttpClientConnection localConn = getConnection();
        this.localContext.setAttribute(HttpCoreContext.HTTP_CONNECTION, localConn);
        final RequestConfig config = this.localContext.getRequestConfig();
        if (config.getSocketTimeout() > 0) {
            localConn.setSocketTimeout(config.getSocketTimeout());
        }
        return getCurrentRequest();
    }

    @Override
    public void produceContent(
            final ContentEncoder encoder, final IOControl ioctrl) throws IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + getId() + "] produce content");
        }
        this.requestProducer.produceContent(encoder, ioctrl);
        if (encoder.isCompleted()) {
            this.requestProducer.resetRequest();
        }
    }

    @Override
    public void requestCompleted() {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + getId() + "] Request completed");
        }
        this.requestProducer.requestCompleted(this.localContext);
    }

    @Override
    public void responseReceived(
            final HttpResponse response) throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + getId() + "] Response received " + response.getStatusLine());
        }
        this.localContext.setAttribute(HttpClientContext.HTTP_RESPONSE, response);
        this.httpProcessor.process(response, this.localContext);

        setCurrentResponse(response);

        this.responseConsumer.responseReceived(response);
    }

    @Override
    public void consumeContent(
            final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + getId() + "] Consume content");
        }
        this.responseConsumer.consumeContent(decoder, ioctrl);
        if (!decoder.isCompleted() && this.responseConsumer.isDone()) {
            markConnectionNonReusable();
            try {
                markCompleted();
                releaseConnection();
                this.resultFuture.cancel();
            } finally {
                close();
            }
        }
    }

    @Override
    public void responseCompleted() throws IOException, HttpException {
        manageConnectionPersistence();
        this.responseConsumer.responseCompleted(this.localContext);
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + getId() + "] Response processed");
        }
        try {
            markCompleted();
            releaseConnection();
            final T result = this.responseConsumer.getResult();
            final Exception ex = this.responseConsumer.getException();
            if (ex == null) {
                this.resultFuture.completed(result);
            } else {
                this.resultFuture.failed(ex);
            }
        } finally {
            close();
        }
    }

    @Override
    public void inputTerminated() {
        close();
    }

    public void abortConnection() {
        discardConnection();
    }

}
