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
import org.apache.http.client.methods.HttpExecutionAware;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;

/**
 * Default implementation of {@link org.apache.http.nio.protocol.HttpAsyncClientExchangeHandler}.
 * <p>
 * Instances of this class are expected to be accessed by one thread at a time only.
 * The {@link #cancel()} method can be called concurrently by multiple threads.
 */
class DefaultClientExchangeHandlerImpl<T> extends AbstractClientExchangeHandler {

    private final HttpAsyncRequestProducer requestProducer;
    private final HttpAsyncResponseConsumer<T> responseConsumer;
    private final BasicFuture<T> resultFuture;
    private final InternalClientExec exec;
    private final InternalState state;

    public DefaultClientExchangeHandlerImpl(
            final Log log,
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final HttpClientContext localContext,
            final BasicFuture<T> resultFuture,
            final NHttpClientConnectionManager connmgr,
            final ConnectionReuseStrategy connReuseStrategy,
            final ConnectionKeepAliveStrategy keepaliveStrategy,
            final InternalClientExec exec) {
        super(log, localContext, connmgr, connReuseStrategy, keepaliveStrategy);
        this.requestProducer = requestProducer;
        this.responseConsumer = responseConsumer;
        this.resultFuture = resultFuture;
        this.exec = exec;
        this.state = new InternalState(getId(), requestProducer, responseConsumer, localContext);
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
        try {
            this.requestProducer.failed(ex);
            this.responseConsumer.failed(ex);
        } finally {
            this.resultFuture.failed(ex);
        }
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
        this.exec.prepare(target, original, this.state, this);
        requestConnection();
    }

    @Override
    public HttpRequest generateRequest() throws IOException, HttpException {
        return this.exec.generateRequest(this.state, this);
    }

    @Override
    public void produceContent(
            final ContentEncoder encoder, final IOControl ioctrl) throws IOException {
        this.exec.produceContent(this.state, encoder, ioctrl);
    }

    @Override
    public void requestCompleted() {
        this.exec.requestCompleted(this.state, this);
    }

    @Override
    public void responseReceived(
            final HttpResponse response) throws IOException, HttpException {
        this.exec.responseReceived(response, this.state, this);
    }

    @Override
    public void consumeContent(
            final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
        this.exec.consumeContent(this.state, decoder, ioctrl);
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
        this.exec.responseCompleted(this.state, this);

        if (this.state.getFinalResponse() != null || this.resultFuture.isDone()) {
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
        } else {
            NHttpClientConnection localConn = getConnection();
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

    @Override
    public void inputTerminated() {
        if (!isCompleted()) {
            requestConnection();
        } else {
            close();
        }
    }

    public void abortConnection() {
        discardConnection();
    }

}
