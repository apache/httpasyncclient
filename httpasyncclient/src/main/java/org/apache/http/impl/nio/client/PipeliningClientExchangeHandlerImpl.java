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
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.http.ConnectionClosedException;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
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
import org.apache.http.nio.protocol.Pipelined;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.util.Args;
import org.apache.http.util.Asserts;

/**
 * {@link org.apache.http.nio.protocol.HttpAsyncClientExchangeHandler} implementation
 * that supports HTTP message pipelining.
 * <p>
 * Instances of this class are expected to be accessed by one thread at a time only.
 * The {@link #cancel()} method can be called concurrently by multiple threads.
 */
@Pipelined
class PipeliningClientExchangeHandlerImpl<T> extends AbstractClientExchangeHandler {

    private final HttpHost target;
    private final Queue<HttpAsyncRequestProducer> requestProducerQueue;
    private final Queue<HttpAsyncResponseConsumer<T>> responseConsumerQueue;
    private final Queue<HttpRequest> requestQueue;
    private final Queue<T> resultQueue;
    private final HttpClientContext localContext;
    private final BasicFuture<List<T>> resultFuture;
    private final HttpProcessor httpProcessor;
    private final AtomicReference<HttpAsyncRequestProducer> requestProducerRef;
    private final AtomicReference<HttpAsyncResponseConsumer<T>> responseConsumerRef;

    public PipeliningClientExchangeHandlerImpl(
            final Log log,
            final HttpHost target,
            final List<? extends HttpAsyncRequestProducer> requestProducers,
            final List<? extends HttpAsyncResponseConsumer<T>> responseConsumers,
            final HttpClientContext localContext,
            final BasicFuture<List<T>> resultFuture,
            final NHttpClientConnectionManager connmgr,
            final HttpProcessor httpProcessor,
            final ConnectionReuseStrategy connReuseStrategy,
            final ConnectionKeepAliveStrategy keepaliveStrategy) {
        super(log, localContext, connmgr, connReuseStrategy, keepaliveStrategy);
        Args.notNull(target, "HTTP target");
        Args.notEmpty(requestProducers, "Request producer list");
        Args.notEmpty(responseConsumers, "Response consumer list");
        Args.check(requestProducers.size() == responseConsumers.size(),
                "Number of request producers does not match that of response consumers");
        this.target = target;
        this.requestProducerQueue = new ConcurrentLinkedQueue<HttpAsyncRequestProducer>(requestProducers);
        this.responseConsumerQueue = new ConcurrentLinkedQueue<HttpAsyncResponseConsumer<T>>(responseConsumers);
        this.requestQueue = new ConcurrentLinkedQueue<HttpRequest>();
        this.resultQueue = new ConcurrentLinkedQueue<T>();
        this.localContext = localContext;
        this.resultFuture = resultFuture;
        this.httpProcessor = httpProcessor;
        this.requestProducerRef = new AtomicReference<HttpAsyncRequestProducer>(null);
        this.responseConsumerRef = new AtomicReference<HttpAsyncResponseConsumer<T>>(null);
    }

    private void closeProducer(final HttpAsyncRequestProducer requestProducer) {
        if (requestProducer != null) {
            try {
                requestProducer.close();
            } catch (final IOException ex) {
                this.log.debug("I/O error closing request producer", ex);
            }
        }
    }

    private void closeConsumer(final HttpAsyncResponseConsumer<?> responseConsumer) {
        if (responseConsumer != null) {
            try {
                responseConsumer.close();
            } catch (final IOException ex) {
                this.log.debug("I/O error closing response consumer", ex);
            }
        }
    }

    @Override
    void releaseResources() {
        closeProducer(this.requestProducerRef.getAndSet(null));
        closeConsumer(this.responseConsumerRef.getAndSet(null));
        while (!this.requestProducerQueue.isEmpty()) {
            closeProducer(this.requestProducerQueue.remove());
        }
        while (!this.responseConsumerQueue.isEmpty()) {
            closeConsumer(this.responseConsumerQueue.remove());
        }
        this.requestQueue.clear();
        this.resultQueue.clear();
    }

    @Override
    void executionFailed(final Exception ex) {
        final HttpAsyncRequestProducer requestProducer = this.requestProducerRef.get();
        if (requestProducer != null) {
            requestProducer.failed(ex);
        }
        final HttpAsyncResponseConsumer<T> responseConsumer = this.responseConsumerRef.get();
        if (responseConsumer != null) {
            responseConsumer.failed(ex);
        }
        for (final HttpAsyncResponseConsumer<T> cancellable: this.responseConsumerQueue) {
            cancellable.cancel();
        }
    }

    @Override
    boolean executionCancelled() {
        final HttpAsyncResponseConsumer<T> responseConsumer = this.responseConsumerRef.get();
        final boolean cancelled = responseConsumer != null && responseConsumer.cancel();
        this.resultFuture.cancel();
        return cancelled;
    }

    public void start() throws HttpException, IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + getId() + "] start execution");
        }

        final HttpRoute route = new HttpRoute(this.target);
        setRoute(route);

        this.localContext.setAttribute(HttpCoreContext.HTTP_TARGET_HOST, this.target);
        this.localContext.setAttribute(HttpClientContext.HTTP_ROUTE, route);

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

        Asserts.check(this.requestProducerRef.get() == null, "Inconsistent state: currentRequest producer is not null");
        final HttpAsyncRequestProducer requestProducer = this.requestProducerQueue.poll();
        if (requestProducer == null) {
            return null;
        }
        this.requestProducerRef.set(requestProducer);

        final HttpRequest original = requestProducer.generateRequest();
        final HttpRequestWrapper currentRequest = HttpRequestWrapper.wrap(original);
        final RequestConfig config = this.localContext.getRequestConfig();
        if (config.getSocketTimeout() > 0) {
            localConn.setSocketTimeout(config.getSocketTimeout());
        }

        this.httpProcessor.process(currentRequest, this.localContext);

        this.requestQueue.add(currentRequest);
        setCurrentRequest(currentRequest);

        return currentRequest;
    }

    @Override
    public void produceContent(
            final ContentEncoder encoder, final IOControl ioControl) throws IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + getId() + "] produce content");
        }
        final HttpAsyncRequestProducer requestProducer = this.requestProducerRef.get();
        Asserts.check(requestProducer != null, "Inconsistent state: request producer is null");
        requestProducer.produceContent(encoder, ioControl);
        if (encoder.isCompleted()) {
            requestProducer.resetRequest();
        }
    }

    @Override
    public void requestCompleted() {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + getId() + "] Request completed");
        }
        final HttpAsyncRequestProducer requestProducer = this.requestProducerRef.getAndSet(null);
        Asserts.check(requestProducer != null, "Inconsistent state: request producer is null");
        requestProducer.requestCompleted(this.localContext);
        try {
            requestProducer.close();
        } catch (final IOException ioex) {
            this.log.debug(ioex.getMessage(), ioex);
        }
    }

    @Override
    public void responseReceived(
            final HttpResponse response) throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + getId() + "] Response received " + response.getStatusLine());
        }

        Asserts.check(this.responseConsumerRef.get() == null, "Inconsistent state: response consumer is not null");

        final HttpAsyncResponseConsumer<T> responseConsumer = this.responseConsumerQueue.poll();
        Asserts.check(responseConsumer != null, "Inconsistent state: response consumer queue is empty");
        this.responseConsumerRef.set(responseConsumer);

        final HttpRequest request = this.requestQueue.poll();
        Asserts.check(request != null, "Inconsistent state: request queue is empty");

        this.localContext.setAttribute(HttpCoreContext.HTTP_REQUEST, request);
        this.localContext.setAttribute(HttpCoreContext.HTTP_RESPONSE, response);
        this.httpProcessor.process(response, this.localContext);

        responseConsumer.responseReceived(response);

        setCurrentResponse(response);
    }

    @Override
    public void consumeContent(
            final ContentDecoder decoder, final IOControl ioControl) throws IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + getId() + "] Consume content");
        }
        final HttpAsyncResponseConsumer<T> responseConsumer = this.responseConsumerRef.get();
        Asserts.check(responseConsumer != null, "Inconsistent state: response consumer is null");
        responseConsumer.consumeContent(decoder, ioControl);
    }

    @Override
    public void responseCompleted() throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + getId() + "] Response processed");
        }

        final boolean keepAlive = manageConnectionPersistence();

        final HttpAsyncResponseConsumer<T> responseConsumer = this.responseConsumerRef.getAndSet(null);
        Asserts.check(responseConsumer != null, "Inconsistent state: response consumer is null");
        try {
            responseConsumer.responseCompleted(this.localContext);
            final T result = responseConsumer.getResult();
            final Exception ex = responseConsumer.getException();
            try {
                responseConsumer.close();
            } catch (final IOException ioex) {
                this.log.debug(ioex.getMessage(), ioex);
            }
            if (result != null) {
                this.resultQueue.add(result);
            } else {
                failed(ex);
            }
            if (!this.resultFuture.isDone() && this.responseConsumerQueue.isEmpty()) {
                this.resultFuture.completed(new ArrayList<T>(this.resultQueue));
                this.resultQueue.clear();
            }

            if (this.resultFuture.isDone()) {
                close();
            } else {
                if (!keepAlive) {
                    failed(new ConnectionClosedException("Connection closed"));
                } else {
                    final NHttpClientConnection localConn = getConnection();
                    if (localConn != null) {
                        localConn.requestOutput();
                    } else {
                        requestConnection();
                    }
                }
            }
        } catch (final RuntimeException ex) {
            failed(ex);
            throw ex;
        }
    }

    @Override
    public void inputTerminated() {
        failed(new ConnectionClosedException("Connection closed"));
    }

    public void abortConnection() {
        discardConnection();
    }

}
