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
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.protocol.RequestClientConnControl;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.nio.conn.ClientAsyncConnectionManager;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;
import org.apache.http.util.VersionInfo;

@SuppressWarnings("deprecation")
class MinimalHttpAsyncClient extends CloseableHttpAsyncClient {

    private final Log log = LogFactory.getLog(getClass());

    private final NHttpClientConnectionManager connmgr;
    private final HttpProcessor httpProcessor;
    private final ConnectionReuseStrategy connReuseStrategy;
    private final ConnectionKeepAliveStrategy keepaliveStrategy;
    private final Thread reactorThread;

    private volatile IOReactorStatus status;

    public MinimalHttpAsyncClient(
            final NHttpClientConnectionManager connmgr) {
        super();
        this.connmgr = connmgr;
        this.httpProcessor = new ImmutableHttpProcessor(new HttpRequestInterceptor[] {
                new RequestContent(),
                new RequestTargetHost(),
                new RequestClientConnControl(),
                new RequestUserAgent(VersionInfo.getUserAgent(
                        "Apache-HttpAsyncClient", "org.apache.http.nio.client", getClass()))
        });
        this.connReuseStrategy = DefaultConnectionReuseStrategy.INSTANCE;
        this.keepaliveStrategy = DefaultConnectionKeepAliveStrategy.INSTANCE;
        this.reactorThread = new Thread() {

            @Override
            public void run() {
                doExecute();
            }

        };
        this.status = IOReactorStatus.INACTIVE;
    }

    private void doExecute() {
        try {
            final IOEventDispatch ioEventDispatch = new InternalIODispatch();
            this.connmgr.execute(ioEventDispatch);
        } catch (final Exception ex) {
            this.log.error("I/O reactor terminated abnormally", ex);
        } finally {
            this.status = IOReactorStatus.SHUT_DOWN;
        }
    }

    public IOReactorStatus getStatus() {
        return this.status;
    }

    @Override
    public void start() {
        this.status = IOReactorStatus.ACTIVE;
        this.reactorThread.start();
    }

    public void shutdown() {
        if (this.status.compareTo(IOReactorStatus.ACTIVE) > 0) {
            return;
        }
        this.status = IOReactorStatus.SHUTDOWN_REQUEST;
        try {
            this.connmgr.shutdown();
        } catch (final IOException ex) {
            this.log.error("I/O error shutting down connection manager", ex);
        }
        if (this.reactorThread != null) {
            try {
                this.reactorThread.join();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void close() {
        shutdown();
    }

    public <T> Future<T> execute(
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final HttpContext context,
            final FutureCallback<T> callback) {
        if (this.status != IOReactorStatus.ACTIVE) {
            throw new IllegalStateException("Request cannot be executed; " +
                    "I/O reactor status: " + this.status);
        }
        final BasicFuture<T> future = new BasicFuture<T>(callback);
        final HttpClientContext localcontext = HttpClientContext.adapt(
            context != null ? context : new BasicHttpContext());

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

    @Deprecated
    public ClientAsyncConnectionManager getConnectionManager() {
        return null;
    }

    @Deprecated
    public HttpParams getParams() {
        return null;
    }

}
