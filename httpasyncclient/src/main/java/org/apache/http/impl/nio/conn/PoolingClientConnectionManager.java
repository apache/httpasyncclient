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
package org.apache.http.impl.nio.conn;

import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.nio.conn.ManagedClientConnection;
import org.apache.http.nio.conn.ClientConnectionManager;
import org.apache.http.nio.conn.scheme.SchemeRegistry;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.pool.ConnPoolControl;
import org.apache.http.pool.PoolStats;

public class PoolingClientConnectionManager implements ClientConnectionManager, ConnPoolControl<HttpRoute> {

    private final Log log = LogFactory.getLog(getClass());

    private final ConnectingIOReactor ioreactor;
    private final HttpNIOConnPool pool;
    private final SchemeRegistry schemeRegistry;

    public PoolingClientConnectionManager(
            final ConnectingIOReactor ioreactor,
            final SchemeRegistry schemeRegistry,
            final long timeToLive, final TimeUnit tunit) {
        super();
        if (ioreactor == null) {
            throw new IllegalArgumentException("I/O reactor may not be null");
        }
        if (schemeRegistry == null) {
            throw new IllegalArgumentException("Scheme registory may not be null");
        }
        if (tunit == null) {
            throw new IllegalArgumentException("Time unit may not be null");
        }
        this.ioreactor = ioreactor;
        this.pool = new HttpNIOConnPool(this.log, ioreactor, schemeRegistry, timeToLive, tunit);
        this.schemeRegistry = schemeRegistry;
    }

    public PoolingClientConnectionManager(
            final ConnectingIOReactor ioreactor,
            final SchemeRegistry schemeRegistry) throws IOReactorException {
        this(ioreactor, schemeRegistry, -1, TimeUnit.MILLISECONDS);
    }

    public PoolingClientConnectionManager(
            final ConnectingIOReactor ioreactor) throws IOReactorException {
        this(ioreactor, SchemeRegistryFactory.createDefault());
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            shutdown();
        } finally {
            super.finalize();
        }
    }

    public SchemeRegistry getSchemeRegistry() {
        return this.schemeRegistry;
    }

    public void execute(final IOEventDispatch eventDispatch) throws IOException {
        this.ioreactor.execute(eventDispatch);
    }

    public IOReactorStatus getStatus() {
        return this.ioreactor.getStatus();
    }

    public void shutdown(long waitMs) throws IOException {
        this.log.debug("Connection manager is shutting down");
        this.pool.shutdown(waitMs);
        this.log.debug("Connection manager shut down");
    }

    public void shutdown() throws IOException {
        this.log.debug("Connection manager is shutting down");
        this.pool.shutdown(2000);
        this.log.debug("Connection manager shut down");
    }

    private String format(final HttpRoute route, final Object state) {
        StringBuilder buf = new StringBuilder();
        buf.append("[route: ").append(route).append("]");
        if (state != null) {
            buf.append("[state: ").append(state).append("]");
        }
        return buf.toString();
    }

    private String formatStats(final HttpRoute route) {
        StringBuilder buf = new StringBuilder();
        PoolStats totals = this.pool.getTotalStats();
        PoolStats stats = this.pool.getStats(route);
        buf.append("[total kept alive: ").append(totals.getAvailable()).append("; ");
        buf.append("route allocated: ").append(stats.getLeased() + stats.getAvailable());
        buf.append(" of ").append(stats.getMax()).append("; ");
        buf.append("total allocated: ").append(totals.getLeased() + totals.getAvailable());
        buf.append(" of ").append(totals.getMax()).append("]");
        return buf.toString();
    }

    private String format(final HttpPoolEntry entry) {
        StringBuilder buf = new StringBuilder();
        buf.append("[id: ").append(entry.getId()).append("]");
        buf.append("[route: ").append(entry.getRoute()).append("]");
        Object state = entry.getState();
        if (state != null) {
            buf.append("[state: ").append(state).append("]");
        }
        return buf.toString();
    }

    public Future<ManagedClientConnection> leaseConnection(
            final HttpRoute route,
            final Object state,
            final long connectTimeout,
            final TimeUnit tunit,
            final FutureCallback<ManagedClientConnection> callback) {
        if (route == null) {
            throw new IllegalArgumentException("HTTP route may not be null");
        }
        if (tunit == null) {
            throw new IllegalArgumentException("Time unit may not be null");
        }
        if (this.log.isDebugEnabled()) {
            this.log.debug("Connection request: " + format(route, state) + formatStats(route));
        }
        BasicFuture<ManagedClientConnection> future = new BasicFuture<ManagedClientConnection>(
                callback);
        this.pool.lease(route, state, connectTimeout, tunit, new InternalPoolEntryCallback(future));
        return future;
    }

    public void releaseConnection(
            final ManagedClientConnection conn,
            final long keepalive,
            final TimeUnit tunit) {
        if (conn == null) {
            throw new IllegalArgumentException("HTTP connection may not be null");
        }
        if (!(conn instanceof ManagedClientConnectionImpl)) {
            throw new IllegalArgumentException("Connection class mismatch, " +
                 "connection not obtained from this manager");
        }
        if (tunit == null) {
            throw new IllegalArgumentException("Time unit may not be null");
        }
        ManagedClientConnectionImpl managedConn = (ManagedClientConnectionImpl) conn;
        ClientConnectionManager manager = managedConn.getManager();
        if (manager != null && manager != this) {
            throw new IllegalArgumentException("Connection not obtained from this manager");
        }
        if (this.pool.isShutdown()) {
            return;
        }

        synchronized (managedConn) {
            HttpPoolEntry entry = managedConn.detach();
            if (entry == null) {
                return;
            }
            try {
                if (managedConn.isOpen() && !managedConn.isMarkedReusable()) {
                    try {
                        managedConn.shutdown();
                    } catch (IOException iox) {
                        if (this.log.isDebugEnabled()) {
                            this.log.debug("I/O exception shutting down released connection", iox);
                        }
                    }
                }
                entry.updateExpiry(keepalive, tunit != null ? tunit : TimeUnit.MILLISECONDS);
                if (this.log.isDebugEnabled()) {
                    String s;
                    if (keepalive > 0) {
                        s = "for " + keepalive + " " + tunit;
                    } else {
                        s = "indefinitely";
                    }
                    this.log.debug("Connection " + format(entry) + " can be kept alive " + s);
                }
            } finally {
                this.pool.release(entry, managedConn.isMarkedReusable());
            }
            if (this.log.isDebugEnabled()) {
                this.log.debug("Connection released: " + format(entry) + formatStats(entry.getRoute()));
            }
        }
    }

    public PoolStats getTotalStats() {
        return this.pool.getTotalStats();
    }

    public PoolStats getStats(final HttpRoute route) {
        return this.pool.getStats(route);
    }

    @Deprecated
    public void setTotalMax(int max) {
        this.pool.setMaxTotal(max);
    }

    public void setMaxTotal(int max) {
        this.pool.setMaxTotal(max);
    }

    @Deprecated
    public void setDefaultMaxPerHost(int max) {
        this.pool.setDefaultMaxPerRoute(max);
    }

    public void setDefaultMaxPerRoute(int max) {
        this.pool.setDefaultMaxPerRoute(max);
    }

    @Deprecated
    public void setMaxPerHost(final HttpRoute route, int max) {
        this.pool.setMaxPerRoute(route, max);
    }

    public void setMaxPerRoute(final HttpRoute route, int max) {
        this.pool.setMaxPerRoute(route, max);
    }

    public int getMaxTotal() {
        return this.pool.getMaxTotal();
    }

    public int getDefaultMaxPerRoute() {
        return this.pool.getDefaultMaxPerRoute();
    }

    public int getMaxPerRoute(final HttpRoute route) {
        return this.pool.getMaxPerRoute(route);
    }

    public void closeIdleConnections(long idleTimeout, final TimeUnit tunit) {
        if (log.isDebugEnabled()) {
            log.debug("Closing connections idle longer than " + idleTimeout + " " + tunit);
        }
        this.pool.closeIdle(idleTimeout, tunit);
    }

    public void closeExpiredConnections() {
        log.debug("Closing expired connections");
        this.pool.closeExpired();
    }

    class InternalPoolEntryCallback implements FutureCallback<HttpPoolEntry> {

        private final BasicFuture<ManagedClientConnection> future;

        public InternalPoolEntryCallback(
                final BasicFuture<ManagedClientConnection> future) {
            super();
            this.future = future;
        }

        public void completed(final HttpPoolEntry entry) {
            if (log.isDebugEnabled()) {
                log.debug("Connection leased: " + format(entry) + formatStats(entry.getRoute()));
            }
            ManagedClientConnection conn = new ManagedClientConnectionImpl(
                    PoolingClientConnectionManager.this,
                    entry);
            if (!this.future.completed(conn)) {
                pool.release(entry, true);
            }
        }

        public void failed(final Exception ex) {
            if (log.isDebugEnabled()) {
                log.debug("Connection request failed", ex);
            }
            this.future.failed(ex);
        }

        public void cancelled() {
            log.debug("Connection request cancelled");
            this.future.cancel(true);
        }

    }

}
