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
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.RouteTracker;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.protocol.HttpAsyncClientExchangeHandler;
import org.apache.http.nio.protocol.HttpAsyncRequestExecutor;
import org.apache.http.util.Asserts;

/**
 * Abstract {@link org.apache.http.nio.protocol.HttpAsyncClientExchangeHandler} class
 * that implements connection management aspects shared by all HTTP exchange handlers.
 * <p>
 * Instances of this class are expected to be accessed by one thread at a time only.
 * The {@link #cancel()} method can be called concurrently by multiple threads.
 */
abstract class AbstractClientExchangeHandler<T> implements HttpAsyncClientExchangeHandler {

    private static final AtomicLong COUNTER = new AtomicLong(1);

    protected final Log log;

    private final long id;
    private final HttpClientContext localContext;
    private final BasicFuture<T> resultFuture;
    private final NHttpClientConnectionManager connmgr;
    private final ConnectionReuseStrategy connReuseStrategy;
    private final ConnectionKeepAliveStrategy keepaliveStrategy;
    private final AtomicReference<NHttpClientConnection> managedConnRef;
    private final AtomicReference<HttpRoute> routeRef;
    private final AtomicReference<RouteTracker> routeTrackerRef;
    private final AtomicBoolean routeEstablished;
    private final AtomicReference<Long> validDurationRef;
    private final AtomicReference<HttpRequestWrapper> requestRef;
    private final AtomicReference<HttpResponse> responseRef;
    private final AtomicBoolean completed;
    private final AtomicBoolean closed;

    AbstractClientExchangeHandler(
            final Log log,
            final HttpClientContext localContext,
            final BasicFuture<T> resultFuture,
            final NHttpClientConnectionManager connmgr,
            final ConnectionReuseStrategy connReuseStrategy,
            final ConnectionKeepAliveStrategy keepaliveStrategy) {
        super();
        this.log = log;
        this.id = COUNTER.getAndIncrement();
        this.localContext = localContext;
        this.resultFuture = resultFuture;
        this.connmgr = connmgr;
        this.connReuseStrategy = connReuseStrategy;
        this.keepaliveStrategy = keepaliveStrategy;
        this.managedConnRef = new AtomicReference<NHttpClientConnection>(null);
        this.routeRef = new AtomicReference<HttpRoute>(null);
        this.routeTrackerRef = new AtomicReference<RouteTracker>(null);
        this.routeEstablished = new AtomicBoolean(false);
        this.validDurationRef = new AtomicReference<Long>(null);
        this.requestRef = new AtomicReference<HttpRequestWrapper>(null);
        this.responseRef = new AtomicReference<HttpResponse>(null);
        this.completed = new AtomicBoolean(false);
        this.closed = new AtomicBoolean(false);
    }

    final long getId() {
        return this.id;
    }

    final boolean isCompleted() {
        return this.completed.get();
    }

    final void markCompleted() {
        this.completed.set(true);
    }

    final void markConnectionNonReusable() {
        this.validDurationRef.set(null);
    }

    final boolean isRouteEstablished() {
        return this.routeEstablished.get();
    }

    final HttpRoute getRoute() {
        return this.routeRef.get();
    }

    final void setRoute(final HttpRoute route) {
        this.routeRef.set(route);
    }

    final HttpRequestWrapper getCurrentRequest() {
        return this.requestRef.get();
    }

    final void setCurrentRequest(final HttpRequestWrapper request) {
        this.requestRef.set(request);
    }

    final HttpResponse getCurrentResponse() {
        return this.responseRef.get();
    }

    final void setCurrentResponse(final HttpResponse response) {
        this.responseRef.set(response);
    }

    final HttpRoute getActualRoute() {
        final RouteTracker routeTracker = this.routeTrackerRef.get();
        return routeTracker != null ? routeTracker.toRoute() : null;
    }

    final void verifytRoute() {
        if (!this.routeEstablished.get() && this.routeTrackerRef.get() == null) {
            final NHttpClientConnection managedConn = this.managedConnRef.get();
            Asserts.check(managedConn != null, "Inconsistent state: managed connection is null");
            final boolean routeComplete = this.connmgr.isRouteComplete(managedConn);
            this.routeEstablished.set(routeComplete);
            if (!routeComplete) {
                this.log.debug("Start connection routing");
                final HttpRoute route = this.routeRef.get();
                this.routeTrackerRef.set(new RouteTracker(route));
            } else {
                this.log.debug("Connection route already established");
            }
        }
    }

    final void onRouteToTarget() throws IOException {
        final NHttpClientConnection managedConn = this.managedConnRef.get();
        Asserts.check(managedConn != null, "Inconsistent state: managed connection is null");
        final HttpRoute route = this.routeRef.get();
        Asserts.check(route != null, "Inconsistent state: HTTP route is null");
        final RouteTracker routeTracker = this.routeTrackerRef.get();
        Asserts.check(routeTracker != null, "Inconsistent state: HTTP route tracker");
        this.connmgr.startRoute(managedConn, route, this.localContext);
        routeTracker.connectTarget(route.isSecure());
    }

    final void onRouteToProxy() throws IOException {
        final NHttpClientConnection managedConn = this.managedConnRef.get();
        Asserts.check(managedConn != null, "Inconsistent state: managed connection is null");
        final HttpRoute route = this.routeRef.get();
        Asserts.check(route != null, "Inconsistent state: HTTP route is null");
        final RouteTracker routeTracker = this.routeTrackerRef.get();
        Asserts.check(routeTracker != null, "Inconsistent state: HTTP route tracker");
        this.connmgr.startRoute(managedConn, route, this.localContext);
        final HttpHost proxy  = route.getProxyHost();
        routeTracker.connectProxy(proxy, false);
    }

    final void onRouteUpgrade() throws IOException {
        final NHttpClientConnection managedConn = this.managedConnRef.get();
        Asserts.check(managedConn != null, "Inconsistent state: managed connection is null");
        final HttpRoute route = this.routeRef.get();
        Asserts.check(route != null, "Inconsistent state: HTTP route is null");
        final RouteTracker routeTracker = this.routeTrackerRef.get();
        Asserts.check(routeTracker != null, "Inconsistent state: HTTP route tracker");
        this.connmgr.upgrade(managedConn, route, this.localContext);
        routeTracker.layerProtocol(route.isSecure());
    }

    final void onRouteTunnelToTarget() {
        final RouteTracker routeTracker = this.routeTrackerRef.get();
        Asserts.check(routeTracker != null, "Inconsistent state: HTTP route tracker");
        routeTracker.tunnelTarget(false);
    }

    final void onRouteComplete() {
        final NHttpClientConnection managedConn = this.managedConnRef.get();
        Asserts.check(managedConn != null, "Inconsistent state: managed connection is null");
        final HttpRoute route = this.routeRef.get();
        Asserts.check(route != null, "Inconsistent state: HTTP route is null");
        this.connmgr.routeComplete(managedConn, route, this.localContext);
        this.routeEstablished.set(true);
        this.routeTrackerRef.set(null);
    }

    final NHttpClientConnection getConnection() {
        return this.managedConnRef.get();
    }

    final void releaseConnection() {
        final NHttpClientConnection localConn = this.managedConnRef.getAndSet(null);
        if (localConn != null) {
            if (this.log.isDebugEnabled()) {
                this.log.debug("[exchange: " + this.id + "] releasing connection");
            }
            localConn.getContext().removeAttribute(HttpAsyncRequestExecutor.HTTP_HANDLER);
            final Long validDuration = this.validDurationRef.get();
            if (validDuration != null) {
                final Object userToken = this.localContext.getUserToken();
                this.connmgr.releaseConnection(localConn, userToken, validDuration, TimeUnit.MILLISECONDS);
            } else {
                try {
                    localConn.close();
                    if (this.log.isDebugEnabled()) {
                        this.log.debug("[exchange: " + this.id + "] connection discarded");
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

    final void discardConnection() {
        final NHttpClientConnection localConn = this.managedConnRef.getAndSet(null);
        if (localConn != null) {
            try {
                localConn.shutdown();
                if (this.log.isDebugEnabled()) {
                    this.log.debug("[exchange: " + this.id + "] connection aborted");
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

    final boolean manageConnectionPersistence() {
        final HttpResponse response = this.responseRef.get();
        Asserts.check(response != null, "Inconsistent state: HTTP response");
        final NHttpClientConnection managedConn = this.managedConnRef.get();
        Asserts.check(managedConn != null, "Inconsistent state: managed connection is null");
        final boolean keepAlive = managedConn.isOpen() &&
                this.connReuseStrategy.keepAlive(response, this.localContext);
        if (keepAlive) {
            final long validDuration = this.keepaliveStrategy.getKeepAliveDuration(
                    response, this.localContext);
            if (this.log.isDebugEnabled()) {
                final String s;
                if (validDuration > 0) {
                    s = "for " + validDuration + " " + TimeUnit.MILLISECONDS;
                } else {
                    s = "indefinitely";
                }
                this.log.debug("[exchange: " + this.id + "] Connection can be kept alive " + s);
            }
            this.validDurationRef.set(validDuration);
        } else {
            if (this.log.isDebugEnabled()) {
                this.log.debug("[exchange: " + this.id + "] Connection cannot be kept alive");
            }
            this.validDurationRef.set(null);
        }
        return keepAlive;
    }

    private void connectionAllocated(final NHttpClientConnection managedConn) {
        try {
            if (this.log.isDebugEnabled()) {
                this.log.debug("[exchange: " + this.id + "] Connection allocated: " + managedConn);
            }
            this.managedConnRef.set(managedConn);

            if (this.closed.get()) {
                discardConnection();
                return;
            }

            managedConn.getContext().setAttribute(HttpAsyncRequestExecutor.HTTP_HANDLER, this);
            managedConn.requestOutput();
            if (!managedConn.isOpen()) {
                failed(new ConnectionClosedException("Connection closed"));
            }
        } catch (final RuntimeException runex) {
            failed(runex);
            throw runex;
        }
    }

    private void connectionRequestFailed(final Exception ex) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] connection request failed");
        }
        failed(ex);
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

    final void requestConnection() {
        final HttpRoute route = this.routeRef.get();
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] Request connection for " + route);
        }

        discardConnection();

        this.validDurationRef.set(null);
        this.routeTrackerRef.set(null);
        this.routeEstablished.set(false);

        final Object userToken = this.localContext.getUserToken();
        final RequestConfig config = this.localContext.getRequestConfig();
        this.connmgr.requestConnection(
                route,
                userToken,
                config.getConnectTimeout(),
                config.getConnectionRequestTimeout(),
                TimeUnit.MILLISECONDS,
                new FutureCallback<NHttpClientConnection>() {

                    @Override
                    public void completed(final NHttpClientConnection managedConn) {
                        connectionAllocated(managedConn);
                    }

                    @Override
                    public void failed(final Exception ex) {
                        connectionRequestFailed(ex);
                    }

                    @Override
                    public void cancelled() {
                        connectionRequestCancelled();
                    }

                });
    }

    abstract void releaseResources();

    abstract void executionFailed(final Exception ex);

    abstract boolean executionCancelled();

    @Override
    public final void close() {
        if (this.closed.compareAndSet(false, true)) {
            discardConnection();
            releaseResources();
        }
    }

    @Override
    public final boolean isDone() {
        return this.completed.get();
    }

    @Override
    public final void failed(final Exception ex) {
        try {
            executionFailed(ex);
        } finally {
            try {
                this.resultFuture.failed(ex);
            } finally {
                close();
            }
        }
    }

    @Override
    public final boolean cancel() {
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] Cancelled");
        }
        if (this.closed.compareAndSet(false, true)) {
            try {
                try {
                    return executionCancelled();
                } finally {
                    this.resultFuture.cancel();
                }
            } finally {
                discardConnection();
                releaseResources();
            }
        }
        return false;
    }

}
