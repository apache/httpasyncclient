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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.http.ConnectionClosedException;
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
import org.apache.http.concurrent.Cancellable;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.protocol.HttpAsyncClientExchangeHandler;
import org.apache.http.nio.protocol.HttpAsyncRequestExecutor;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpProcessor;

/**
 * Minimal implementation of {@link HttpAsyncClientExchangeHandler}.
 * <p/>
 * Instances of this class are expected to be accessed by one thread at a time only.
 * The {@link #cancel()} method can be called concurrently by multiple threads.
 */
class MinimalClientExchangeHandlerImpl<T>
    implements HttpAsyncClientExchangeHandler, Cancellable {

    private static final AtomicLong COUNTER = new AtomicLong(1);

    private final Log log;

    private final long id;
    private final HttpAsyncRequestProducer requestProducer;
    private final HttpAsyncResponseConsumer<T> responseConsumer;
    private final HttpClientContext localContext;
    private final BasicFuture<T> resultFuture;
    private final NHttpClientConnectionManager connmgr;
    private final HttpProcessor httpProcessor;
    private final ConnectionReuseStrategy connReuseStrategy;
    private final ConnectionKeepAliveStrategy keepaliveStrategy;
    private final AtomicReference<NHttpClientConnection> managedConn;
    private final AtomicBoolean closed;
    private final AtomicBoolean completed;

    private volatile HttpRoute route;
    private volatile HttpRequestWrapper request;
    private volatile HttpResponse response;
    private volatile boolean reusable;
    private volatile long validDuration;

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
        super();
        this.log = log;
        this.id = COUNTER.getAndIncrement();
        this.requestProducer = requestProducer;
        this.responseConsumer = responseConsumer;
        this.localContext = localContext;
        this.resultFuture = resultFuture;
        this.connmgr = connmgr;
        this.httpProcessor = httpProcessor;
        this.connReuseStrategy = connReuseStrategy;
        this.keepaliveStrategy = keepaliveStrategy;
        this.closed = new AtomicBoolean(false);
        this.completed = new AtomicBoolean(false);
        this.managedConn = new AtomicReference<NHttpClientConnection>(null);
    }

    public void close() {
        if (this.closed.getAndSet(true)) {
            return;
        }
        abortConnection();
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

    public void start() throws HttpException, IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] Start execution");
        }
        final HttpHost target = this.requestProducer.getTarget();
        final HttpRequest original = this.requestProducer.generateRequest();

        if (original instanceof HttpExecutionAware) {
            ((HttpExecutionAware) original).setCancellable(this);
        }
        if (original instanceof Configurable) {
            final RequestConfig config = ((Configurable) original).getConfig();
            if (config != null) {
                this.localContext.setRequestConfig(config);
            }
        }
        this.request = HttpRequestWrapper.wrap(original);
        this.route = new HttpRoute(target);

        this.localContext.setAttribute(HttpClientContext.HTTP_REQUEST, this.request);
        this.localContext.setAttribute(HttpClientContext.HTTP_TARGET_HOST, target);
        this.localContext.setAttribute(HttpClientContext.HTTP_ROUTE, this.route);
        this.httpProcessor.process(this.request, this.localContext);

        requestConnection();
    }

    public boolean isDone() {
        return this.completed.get();
    }

    public HttpRequest generateRequest() throws IOException, HttpException {
        final NHttpClientConnection localConn = this.managedConn.get();
        if (!this.connmgr.isRouteComplete(localConn)) {
            this.connmgr.startRoute(localConn, this.route, this.localContext);
            this.connmgr.routeComplete(localConn, this.route, this.localContext);
        }
        return this.request;
    }

    public void produceContent(
            final ContentEncoder encoder, final IOControl ioctrl) throws IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] Produce content");
        }
        this.requestProducer.produceContent(encoder, ioctrl);
        if (encoder.isCompleted()) {
            this.requestProducer.resetRequest();
        }
    }

    public void requestCompleted() {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] Request completed");
        }
        this.requestProducer.requestCompleted(this.localContext);
    }

    public void responseReceived(
            final HttpResponse response) throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] Response received " + response.getStatusLine());
        }

        this.localContext.setAttribute(HttpClientContext.HTTP_RESPONSE, response);
        this.httpProcessor.process(response, this.localContext);

        this.responseConsumer.responseReceived(response);
        this.response = response;
    }

    public void consumeContent(
            final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] Consume content");
        }
        this.responseConsumer.consumeContent(decoder, ioctrl);
    }

    public void responseCompleted() throws IOException, HttpException {
        if (this.resultFuture.isDone()) {
            this.completed.set(true);
            releaseConnection();
            return;
        }
        if (this.connReuseStrategy.keepAlive(this.response, this.localContext)) {
            this.validDuration = this.keepaliveStrategy.getKeepAliveDuration(this.response,
                    this.localContext);
            if (this.log.isDebugEnabled()) {
                final String s;
                if (this.validDuration > 0) {
                    s = "for " + this.validDuration + " " + TimeUnit.MILLISECONDS;
                } else {
                    s = "indefinitely";
                }
                this.log.debug("[exchange: " + this.id + "] Connection can be kept alive " + s);
            }
            this.reusable = true;
        } else {
            this.validDuration = 0;
            this.reusable = false;
            if (this.log.isDebugEnabled()) {
                this.log.debug("[exchange: " + this.id + "] Connection cannot be kept alive");
            }
        }
        this.responseConsumer.responseCompleted(this.localContext);
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] Response processed");
        }

        releaseConnection();

        final T result = this.responseConsumer.getResult();
        final Exception ex = this.responseConsumer.getException();
        if (ex == null) {
            this.resultFuture.completed(result);
        } else {
            this.resultFuture.failed(ex);
        }
        this.completed.set(true);
    }

    public void inputTerminated() {
        close();
    }

    public void failed(final Exception ex) {
        try {
            this.requestProducer.failed(ex);
            this.responseConsumer.failed(ex);
        } finally {
            try {
                this.resultFuture.failed(ex);
            } finally {
                close();
            }
        }
    }

    public boolean cancel() {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] Cancelled");
        }
        try {
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
        } catch (final RuntimeException runex) {
            this.resultFuture.failed(runex);
            throw runex;
        } finally {
            close();
        }
    }

    private void connectionAllocated(final NHttpClientConnection managedConn) {
        try {
            if (this.log.isDebugEnabled()) {
                this.log.debug("[exchange: " + this.id + "] Connection allocated: " + managedConn);
            }
            this.managedConn.set(managedConn);

            if (this.closed.get()) {
                releaseConnection();
                return;
            }

            this.localContext.setAttribute(HttpCoreContext.HTTP_CONNECTION, managedConn);

            if (!managedConn.isOpen()) {
                failed(new ConnectionClosedException("Connection closed"));
            } else {
                managedConn.getContext().setAttribute(HttpAsyncRequestExecutor.HTTP_HANDLER, this);
                final RequestConfig config = this.localContext.getRequestConfig();
                if (config.getSocketTimeout() > 0) {
                    managedConn.setSocketTimeout(config.getSocketTimeout());
                }
                managedConn.requestOutput();
            }
        } catch (final RuntimeException runex) {
            failed(runex);
            throw runex;
        }
    }

    private void connectionRequestFailed(final Exception ex) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] Connection request failed");
        }
        try {
            this.resultFuture.failed(ex);
        } finally {
            close();
        }
    }

    private void connectionRequestCancelled() {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] Connection request cancelled");
        }
        try {
            this.resultFuture.cancel();
        } finally {
            close();
        }
    }

    private void requestConnection() {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] Request connection for " + this.route);
        }
        final Object userToken = this.localContext.getUserToken();
        final RequestConfig config = this.localContext.getRequestConfig();
        this.connmgr.requestConnection(
                this.route,
                userToken,
                config.getConnectTimeout(),
                config.getConnectionRequestTimeout(),
                TimeUnit.MILLISECONDS,
                new FutureCallback<NHttpClientConnection>() {

                    public void completed(final NHttpClientConnection managedConn) {
                        connectionAllocated(managedConn);
                    }

                    public void failed(final Exception ex) {
                        connectionRequestFailed(ex);
                    }

                    public void cancelled() {
                        connectionRequestCancelled();
                    }

                });
    }

    private void releaseConnection() {
        final NHttpClientConnection localConn = this.managedConn.getAndSet(null);
        if (localConn != null) {
            if (this.log.isDebugEnabled()) {
                this.log.debug("[exchange: " + this.id + "] Releasing connection");
            }
            localConn.getContext().removeAttribute(HttpAsyncRequestExecutor.HTTP_HANDLER);
            if (this.reusable) {
                this.connmgr.releaseConnection(localConn,
                        this.localContext.getUserToken(),
                        this.validDuration, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void abortConnection() {
        final NHttpClientConnection localConn = this.managedConn.getAndSet(null);
        if (localConn != null) {
            try {
                localConn.shutdown();
                if (this.log.isDebugEnabled()) {
                    this.log.debug("[exchange: " + this.id + "] Connection discarded");
                }
            } catch (final IOException ex) {
                if (this.log.isDebugEnabled()) {
                    this.log.debug(ex.getMessage(), ex);
                }
            } finally {
                this.connmgr.releaseConnection(localConn, null, 0, TimeUnit.MILLISECONDS);
            }
        }
    }

}
