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

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponseFactory;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.nio.DefaultNHttpClientConnection;
import org.apache.http.impl.nio.pool.PoolEntry;
import org.apache.http.impl.nio.pool.PoolEntryCallback;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.concurrent.BasicFuture;
import org.apache.http.nio.concurrent.FutureCallback;
import org.apache.http.nio.conn.ManagedIOSession;
import org.apache.http.nio.conn.IOSessionManager;
import org.apache.http.nio.conn.PoolStats;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.util.ByteBufferAllocator;
import org.apache.http.nio.util.HeapByteBufferAllocator;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.ExecutionContext;

public class BasicIOSessionManager implements IOSessionManager {

    private static final String HEADERS    = "org.apache.http.headers";
    private static final String WIRE       = "org.apache.http.wire";

    private final Log log = LogFactory.getLog(getClass());

    private final HttpSessionPool pool;
    private final HttpParams params;

    public BasicIOSessionManager(final ConnectingIOReactor ioreactor, final HttpParams params) {
        super();
        if (ioreactor == null) {
            throw new IllegalArgumentException("I/O reactor may not be null");
        }
        if (params == null) {
            throw new IllegalArgumentException("HTTP parameters may not be null");
        }
        this.pool = new HttpSessionPool(ioreactor);
        this.params = params;
    }

    public synchronized Future<ManagedIOSession> leaseSession(
            final HttpRoute route,
            final Object state,
            final long connectTimeout,
            final TimeUnit timeUnit,
            final FutureCallback<ManagedIOSession> callback) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("I/O session request: route[" + route + "][state: " + state + "]");
            PoolStats totals = this.pool.getTotalStats();
            PoolStats stats = this.pool.getStats(route);
            this.log.debug("Total: " + totals);
            this.log.debug("Route [" + route + "]: " + stats);
        }
        BasicFuture<ManagedIOSession> future = new BasicFuture<ManagedIOSession>(
                callback);
        this.pool.lease(route, state, connectTimeout, timeUnit, new InternalPoolEntryCallback(future));
        if (this.log.isDebugEnabled()) {
            if (!future.isDone()) {
                this.log.debug("I/O session could not be allocated immediately: " +
                        "route[" + route + "][state: " + state + "]");
            }
        }
        return future;
    }

    public synchronized void releaseSession(final ManagedIOSession conn) {
        if (!(conn instanceof BasicManagedIOSession)) {
            throw new IllegalArgumentException
                ("I/O session class mismatch, " +
                 "I/O session not obtained from this manager");
        }
        BasicManagedIOSession adaptor = (BasicManagedIOSession) conn;
        IOSessionManager manager = adaptor.getManager();
        if (manager != null && manager != this) {
            throw new IllegalArgumentException
                ("I/O session not obtained from this manager");
        }
        PoolEntry<HttpRoute> entry = adaptor.getEntry();
        IOSession iosession = entry.getIOSession();
        if (this.log.isDebugEnabled()) {
            HttpRoute route = entry.getRoute();
            PoolStats totals = this.pool.getTotalStats();
            PoolStats stats = this.pool.getStats(route);
            this.log.debug("Total: " + totals);
            this.log.debug("Route [" + route + "]: " + stats);
            this.log.debug("I/O session released: " + entry);
        }
        this.pool.release(entry, adaptor.isReusable() && !iosession.isClosed());
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

    public synchronized void shutdown() {
        this.log.debug("I/O session manager shut down");
        this.pool.shutdown();
    }

    protected ByteBufferAllocator createByteBufferAllocator() {
        return new HeapByteBufferAllocator();
    }

    protected HttpResponseFactory createHttpResponseFactory() {
        return new DefaultHttpResponseFactory();
    }

    class InternalPoolEntryCallback implements PoolEntryCallback<HttpRoute> {

        private final BasicFuture<ManagedIOSession> future;

        public InternalPoolEntryCallback(
                final BasicFuture<ManagedIOSession> future) {
            super();
            this.future = future;
        }

        public void completed(final PoolEntry<HttpRoute> entry) {
            if (log.isDebugEnabled()) {
                log.debug("I/O session allocated: " + entry);
            }
            IOSession session = entry.getIOSession();
            NHttpClientConnection conn = (NHttpClientConnection) session.getAttribute(
                    ExecutionContext.HTTP_CONNECTION);
            if (conn == null) {
                Log log = LogFactory.getLog(session.getClass());
                Log wirelog = LogFactory.getLog(WIRE);
                Log headerlog = LogFactory.getLog(HEADERS);
                if (log.isDebugEnabled() || wirelog.isDebugEnabled()) {
                    session = new LoggingIOSession(session, log, wirelog);
                }
                if (headerlog.isDebugEnabled()) {
                    conn = new LoggingNHttpClientConnection(
                            headerlog,
                            session,
                            createHttpResponseFactory(),
                            createByteBufferAllocator(),
                            params);
                } else {
                    conn = new DefaultNHttpClientConnection(
                            session,
                            createHttpResponseFactory(),
                            createByteBufferAllocator(),
                            params);
                }
                session.setAttribute(ExecutionContext.HTTP_CONNECTION, conn);
            }
            BasicManagedIOSession result = new BasicManagedIOSession(
                    BasicIOSessionManager.this,
                    entry,
                    conn);
            if (!this.future.completed(result)) {
                pool.release(entry, true);
            }
        }

        public void failed(final Exception ex) {
            if (log.isDebugEnabled()) {
                log.debug("I/O session request failed", ex);
            }
            this.future.failed(ex);
        }

        public void cancelled() {
            log.debug("I/O session request cancelled");
            this.future.cancel(true);
        }

    }

}
