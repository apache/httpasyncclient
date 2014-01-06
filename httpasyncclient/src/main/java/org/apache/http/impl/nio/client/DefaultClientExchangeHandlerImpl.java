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
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpExecutionAware;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.Cancellable;
import org.apache.http.concurrent.FutureCallback;
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

/**
 * Default implementation of {@link HttpAsyncClientExchangeHandler}.
 * <p/>
 * Instances of this class are expected to be accessed by one thread at a time only.
 * The {@link #cancel()} method can be called concurrently by multiple threads.
 */
class DefaultClientExchangeHandlerImpl<T>
    implements HttpAsyncClientExchangeHandler, InternalConnManager, Cancellable {

    private final Log log;

    private final HttpAsyncRequestProducer requestProducer;
    private final HttpAsyncResponseConsumer<T> responseConsumer;
    private final HttpClientContext localContext;
    private final BasicFuture<T> resultFuture;
    private final NHttpClientConnectionManager connmgr;
    private final InternalClientExec exec;
    private final InternalState state;
    private final AtomicReference<NHttpClientConnection> managedConn;
    private final AtomicBoolean closed;
    private final AtomicBoolean completed;

    public DefaultClientExchangeHandlerImpl(
            final Log log,
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final HttpClientContext localContext,
            final BasicFuture<T> resultFuture,
            final NHttpClientConnectionManager connmgr,
            final InternalClientExec exec) {
        super();
        this.log = log;
        this.requestProducer = requestProducer;
        this.responseConsumer = responseConsumer;
        this.localContext = localContext;
        this.resultFuture = resultFuture;
        this.connmgr = connmgr;
        this.exec = exec;
        this.state = new InternalState(requestProducer, responseConsumer, localContext);
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
        final HttpHost target = this.requestProducer.getTarget();
        final HttpRequest original = this.requestProducer.generateRequest();

        if (original instanceof HttpExecutionAware) {
            ((HttpExecutionAware) original).setCancellable(this);
        }
        this.exec.prepare(this.state, target, original);
        requestConnection();
    }

    public boolean isDone() {
        return this.completed.get();
    }

    public HttpRequest generateRequest() throws IOException, HttpException {
        return this.exec.generateRequest(this.state, this);
    }

    public void produceContent(
            final ContentEncoder encoder, final IOControl ioctrl) throws IOException {
        this.exec.produceContent(this.state, encoder, ioctrl);
    }

    public void requestCompleted() {
        this.exec.requestCompleted(this.state);
    }

    public void responseReceived(
            final HttpResponse response) throws IOException, HttpException {
        this.exec.responseReceived(this.state, response);
    }

    public void consumeContent(
            final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
        this.exec.consumeContent(this.state, decoder, ioctrl);
    }

    public void responseCompleted() throws IOException, HttpException {
        if (this.resultFuture.isDone()) {
            this.completed.set(true);
            releaseConnection();
            return;
        }
        this.exec.responseCompleted(this.state, this);

        if (this.state.getFinalResponse() != null) {
            releaseConnection();
            final T result = this.responseConsumer.getResult();
            final Exception ex = this.responseConsumer.getException();
            if (ex == null) {
                this.resultFuture.completed(result);
            } else {
                this.resultFuture.failed(ex);
            }
            this.completed.set(true);
        } else {
            NHttpClientConnection localConn = this.managedConn.get();
            if (localConn != null && !localConn.isOpen()) {
                releaseConnection();
                localConn = null;
            }
            if (localConn != null) {
                localConn.requestOutput();
            } else {
                requestConnection();
            }
        }
    }

    public void inputTerminated() {
        if (!this.completed.get()) {
            requestConnection();
        } else {
            close();
        }
    }

    public void releaseConnection() {
        final NHttpClientConnection localConn = this.managedConn.getAndSet(null);
        if (localConn != null) {
            if (this.log.isDebugEnabled()) {
                this.log.debug("[exchange: " + this.state.getId() + "] releasing connection");
            }
            localConn.getContext().removeAttribute(HttpAsyncRequestExecutor.HTTP_HANDLER);
            if (this.state.isReusable()) {
                this.connmgr.releaseConnection(localConn,
                        this.localContext.getUserToken(),
                        this.state.getValidDuration(), TimeUnit.MILLISECONDS);
            } else {
                try {
                    localConn.close();
                    if (this.log.isDebugEnabled()) {
                        this.log.debug("[exchange: " + this.state.getId() + "] connection discarded");
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

    public void abortConnection() {
        final NHttpClientConnection localConn = this.managedConn.getAndSet(null);
        if (localConn != null) {
            try {
                localConn.shutdown();
                if (this.log.isDebugEnabled()) {
                    this.log.debug("[exchange: " + this.state.getId() + "] connection aborted");
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
            this.log.debug("[exchange: " + this.state.getId() + "] Cancelled");
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
                this.log.debug("[exchange: " + this.state.getId() + "] Connection allocated: " + managedConn);
            }
            this.managedConn.set(managedConn);

            if (this.closed.get()) {
                releaseConnection();
                return;
            }

            if (!managedConn.isOpen()) {
                failed(new ConnectionClosedException("Connection closed"));
            } else {
                managedConn.getContext().setAttribute(HttpAsyncRequestExecutor.HTTP_HANDLER, this);
                managedConn.requestOutput();
            }
        } catch (final RuntimeException runex) {
            failed(runex);
            throw runex;
        }
    }

    private void connectionRequestFailed(final Exception ex) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.state.getId() + "] connection request failed");
        }
        try {
            this.resultFuture.failed(ex);
        } finally {
            close();
        }
    }

    private void connectionRequestCancelled() {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.state.getId() + "] Connection request cancelled");
        }
        try {
            this.resultFuture.cancel();
        } finally {
            close();
        }
    }

    private void requestConnection() {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.state.getId() + "] Request connection for " +
                this.state.getRoute());
        }
        this.state.setValidDuration(0);
        this.state.setNonReusable();
        this.state.setRouteEstablished(false);
        this.state.setRouteTracker(null);

        final HttpRoute route = this.state.getRoute();
        final Object userToken = this.localContext.getUserToken();
        final RequestConfig config = this.localContext.getRequestConfig();
        this.connmgr.requestConnection(
                route,
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

    public NHttpClientConnection getConnection() {
        return this.managedConn.get();
    }

}
