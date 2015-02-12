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

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpHost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.nio.NHttpClientEventHandler;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.protocol.HttpAsyncRequestExecutor;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;

class MinimalHttpAsyncClient extends CloseableHttpAsyncClientBase {

    private final Log log = LogFactory.getLog(getClass());

    private final NHttpClientConnectionManager connmgr;
    private final HttpProcessor httpProcessor;
    private final ConnectionReuseStrategy connReuseStrategy;
    private final ConnectionKeepAliveStrategy keepaliveStrategy;

    public MinimalHttpAsyncClient(
            final NHttpClientConnectionManager connmgr,
            final ThreadFactory threadFactory,
            final NHttpClientEventHandler eventHandler,
            final HttpProcessor httpProcessor,
            final ConnectionReuseStrategy connReuseStrategy,
            final ConnectionKeepAliveStrategy keepaliveStrategy) {
        super(connmgr, threadFactory, eventHandler);
        this.connmgr = connmgr;
        this.httpProcessor = httpProcessor;
        this.connReuseStrategy = connReuseStrategy;
        this.keepaliveStrategy = keepaliveStrategy;
    }

    public MinimalHttpAsyncClient(
            final NHttpClientConnectionManager connmgr,
            final HttpProcessor httpProcessor) {
        this(connmgr,
                Executors.defaultThreadFactory(),
                new HttpAsyncRequestExecutor(),
                httpProcessor,
                DefaultConnectionReuseStrategy.INSTANCE,
                DefaultConnectionKeepAliveStrategy.INSTANCE);
    }

    @Override
    public <T> Future<T> execute(
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final HttpContext context,
            final FutureCallback<T> callback) {
        ensureRunning();
        final BasicFuture<T> future = new BasicFuture<T>(callback);
        final HttpClientContext localcontext = HttpClientContext.adapt(
            context != null ? context : new BasicHttpContext());

        @SuppressWarnings("resource")
        final MinimalClientExchangeHandlerImpl<T> handler = new MinimalClientExchangeHandlerImpl<T>(
            this.log,
            requestProducer,
            responseConsumer,
            localcontext,
            future,
            this.connmgr,
            this.httpProcessor,
            this.connReuseStrategy,
            this.keepaliveStrategy);
        try {
            handler.start();
        } catch (final Exception ex) {
            handler.failed(ex);
        }
        return future;
    }

    @Override
    public <T> Future<List<T>> execute(
            final HttpHost target,
            final List<? extends HttpAsyncRequestProducer> requestProducers,
            final List<? extends HttpAsyncResponseConsumer<T>> responseConsumers,
            final HttpContext context,
            final FutureCallback<List<T>> callback) {
        ensureRunning();
        final BasicFuture<List<T>> future = new BasicFuture<List<T>>(callback);
        final HttpClientContext localcontext = HttpClientContext.adapt(
                context != null ? context : new BasicHttpContext());
        @SuppressWarnings("resource")
        final PipeliningClientExchangeHandlerImpl<T> handler = new PipeliningClientExchangeHandlerImpl<T>(
                this.log,
                target,
                requestProducers,
                responseConsumers,
                localcontext,
                future,
                this.connmgr,
                this.httpProcessor,
                this.connReuseStrategy,
                this.keepaliveStrategy);
        try {
            handler.start();
        } catch (final Exception ex) {
            handler.failed(ex);
        }
        return future;
    }

}
