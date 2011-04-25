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
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.nio.pool.PoolEntryCallback;
import org.apache.http.nio.concurrent.BasicFuture;
import org.apache.http.nio.concurrent.FutureCallback;
import org.apache.http.nio.conn.ManagedClientConnection;
import org.apache.http.nio.conn.ClientConnectionManager;
import org.apache.http.nio.conn.PoolStats;
import org.apache.http.nio.conn.scheme.SchemeRegistry;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.nio.reactor.IOReactorStatus;

public class PoolingClientConnectionManager implements ClientConnectionManager {

    private final Log log = LogFactory.getLog(getClass());

    private final ConnectingIOReactor ioreactor;
    private final HttpSessionPool pool;
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
        this.pool = new HttpSessionPool(this.log, ioreactor, schemeRegistry, timeToLive, tunit);
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
        this.log.debug("Connection manager shut down");
        this.pool.shutdown(waitMs);
    }

    public void shutdown() throws IOException {
        this.log.debug("Connection manager shut down");
        this.pool.shutdown(2000);
    }

    public Future<ManagedClientConnection> leaseConnection(
            final HttpRoute route,
            final Object state,
            final long timeout,
            final TimeUnit tunit,
            final FutureCallback<ManagedClientConnection> callback) {
        if (route == null) {
            throw new IllegalArgumentException("HTTP route may not be null");
        }
        if (tunit == null) {
            throw new IllegalArgumentException("Time unit may not be null");
        }
        if (this.log.isDebugEnabled()) {
            this.log.debug("Connection request: route[" + route + "][state: " + state + "]");
            PoolStats totals = this.pool.getTotalStats();
            PoolStats stats = this.pool.getStats(route);
            this.log.debug("Total: " + totals);
            this.log.debug("Route [" + route + "]: " + stats);
        }
        BasicFuture<ManagedClientConnection> future = new BasicFuture<ManagedClientConnection>(
                callback);
        this.pool.lease(route, state, timeout, tunit, new InternalPoolEntryCallback(future));
        if (this.log.isDebugEnabled()) {
            if (!future.isDone()) {
                this.log.debug("Connection could not be allocated immediately: " +
                        "route[" + route + "][state: " + state + "]");
            }
        }
        return future;
    }

    public void releaseConnection(
            final ManagedClientConnection conn,
            final long validDuration,
            final TimeUnit tunit) {
        if (conn == null) {
            throw new IllegalArgumentException("HTTP connection may not be null");
        }
        if (!(conn instanceof ClientConnAdaptor)) {
            throw new IllegalArgumentException("Connection class mismatch, " +
                 "connection not obtained from this manager");
        }
        if (tunit == null) {
            throw new IllegalArgumentException("Time unit may not be null");
        }
        ClientConnAdaptor adaptor = (ClientConnAdaptor) conn;
        ClientConnectionManager manager = adaptor.getManager();
        if (manager != null && manager != this) {
            throw new IllegalArgumentException("Connection not obtained from this manager");
        }
        HttpPoolEntry entry = adaptor.getEntry();
        if (this.log.isDebugEnabled()) {
            HttpRoute route = entry.getPlannedRoute();
            this.log.debug("Releasing connection: " + entry);
            PoolStats totals = this.pool.getTotalStats();
            PoolStats stats = this.pool.getStats(route);
            this.log.debug("Total: " + totals);
            this.log.debug("Route [" + route + "]: " + stats);
        }

        boolean reusable = adaptor.isReusable();
        if (reusable) {
            entry.updateExpiry(validDuration, tunit);
            if (this.log.isDebugEnabled()) {
                String s;
                if (validDuration > 0) {
                    s = "for " + validDuration + " " + tunit;
                } else {
                    s = "indefinitely";
                }
                this.log.debug("Pooling connection: " + entry + "; keep alive " + s);
            }
        }
        this.pool.release(entry, reusable);
    }

    public PoolStats getTotalStats() {
        return this.pool.getTotalStats();
    }

    public PoolStats getStats(final HttpRoute route) {
        return this.pool.getStats(route);
    }

    public void setTotalMax(int max) {
        this.pool.setTotalMax(max);
    }

    public void setDefaultMaxPerHost(int max) {
        this.pool.setDefaultMaxPerHost(max);
    }

    public void setMaxPerHost(final HttpRoute route, int max) {
        this.pool.setMaxPerHost(route, max);
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

    class InternalPoolEntryCallback implements PoolEntryCallback<HttpRoute, HttpPoolEntry> {

        private final BasicFuture<ManagedClientConnection> future;

        public InternalPoolEntryCallback(
                final BasicFuture<ManagedClientConnection> future) {
            super();
            this.future = future;
        }

        public void completed(final HttpPoolEntry entry) {
            if (log.isDebugEnabled()) {
                log.debug("Connection allocated: " + entry);
            }
            ManagedClientConnection conn = new ClientConnAdaptor(
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
