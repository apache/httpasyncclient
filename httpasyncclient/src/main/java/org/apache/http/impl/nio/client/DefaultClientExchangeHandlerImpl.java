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
import org.apache.http.conn.routing.RouteTracker;
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

    private volatile boolean closed;
    private volatile boolean completed;

    private volatile NHttpClientConnection managedConn;

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
    }

    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
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

    public synchronized void start() throws HttpException, IOException {
        final HttpHost target = this.requestProducer.getTarget();
        final HttpRequest original = this.requestProducer.generateRequest();

        if (original instanceof HttpExecutionAware) {
            ((HttpExecutionAware) original).setCancellable(this);
        }
        this.exec.prepare(this.state, target, original);
        requestConnection();
    }

    public boolean isDone() {
        return this.completed;
    }

    public synchronized HttpRequest generateRequest() throws IOException, HttpException {
        return this.exec.generateRequest(this.state, this);
    }

    public synchronized void produceContent(
            final ContentEncoder encoder, final IOControl ioctrl) throws IOException {
        this.exec.produceContent(this.state, encoder, ioctrl);
    }

    public void requestCompleted() {
        this.exec.requestCompleted(this.state);
    }

    public synchronized void responseReceived(
            final HttpResponse response) throws IOException, HttpException {
        this.exec.responseReceived(this.state, response);
    }

    public synchronized void consumeContent(
            final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
        this.exec.consumeContent(this.state, decoder, ioctrl);
    }

    public synchronized void responseCompleted() throws IOException, HttpException {
        if (this.resultFuture.isDone()) {
            this.completed = true;
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
            this.completed = true;
        } else {
            if (this.managedConn != null &&!this.managedConn.isOpen()) {
                releaseConnection();
            }
            if (this.managedConn != null) {
                this.managedConn.requestOutput();
            } else {
                requestConnection();
            }
        }
    }

    public void inputTerminated() {
        if (!this.completed) {
            requestConnection();
        } else {
            close();
        }
    }

    public synchronized void releaseConnection() {
        if (this.managedConn != null) {
            try {
                if (this.log.isDebugEnabled()) {
                    this.log.debug("[exchange: " + this.state.getId() + "] releasing connection");
                }
                this.managedConn.getContext().removeAttribute(HttpAsyncRequestExecutor.HTTP_HANDLER);
                if (this.state.isReusable()) {
                    this.connmgr.releaseConnection(this.managedConn,
                        this.localContext.getUserToken(),
                        this.state.getValidDuration(), TimeUnit.MILLISECONDS);
                    this.managedConn = null;
                }
            } finally {
                abortConnection();
            }
        }
    }

    public synchronized void abortConnection() {
        if (this.managedConn != null) {
            try {
                this.managedConn.shutdown();
                if (this.log.isDebugEnabled()) {
                    this.log.debug("[exchange: " + this.state.getId() + "] connection discarded");
                }
            } catch (final IOException ex) {
                if (this.log.isDebugEnabled()) {
                    this.log.debug(ex.getMessage(), ex);
                }
            } finally {
                this.connmgr.releaseConnection(
                        this.managedConn, null, 0, TimeUnit.MILLISECONDS);
            }
        }
        this.managedConn = null;
    }

    public synchronized void failed(final Exception ex) {
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

    public synchronized boolean cancel() {
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

    private synchronized void connectionAllocated(final NHttpClientConnection managedConn) {
        try {
            if (this.log.isDebugEnabled()) {
                this.log.debug("[exchange: " + this.state.getId() + "] Connection allocated: " + managedConn);
            }
            this.managedConn = managedConn;
            this.state.setValidDuration(0);
            this.state.setNonReusable();

            if (this.closed) {
                releaseConnection();
                return;
            }

            this.localContext.setAttribute(HttpCoreContext.HTTP_CONNECTION, managedConn);
            this.state.setRouteEstablished(this.connmgr.isRouteComplete(managedConn));
            if (!this.state.isRouteEstablished()) {
                this.state.setRouteTracker(new RouteTracker(this.state.getRoute()));
            }

            if (!managedConn.isOpen()) {
                failed(new ConnectionClosedException("Connection closed"));
            } else {
                this.managedConn.getContext().setAttribute(HttpAsyncRequestExecutor.HTTP_HANDLER, this);
                this.managedConn.requestOutput();
            }
        } catch (final RuntimeException runex) {
            failed(runex);
            throw runex;
        }
    }

    private synchronized void connectionRequestFailed(final Exception ex) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.state.getId() + "] connection request failed");
        }
        try {
            this.resultFuture.failed(ex);
        } finally {
            close();
        }
    }

    private synchronized void connectionRequestCancelled() {
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
        final HttpRoute route = this.state.getRoute();
        final Object userToken = this.localContext.getUserToken();
        final RequestConfig config = this.localContext.getRequestConfig();
        this.connmgr.requestConnection(
                route,
                userToken,
                config.getConnectionRequestTimeout(), TimeUnit.MILLISECONDS,
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
        return this.managedConn;
    }

}
