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
package org.apache.http.impl.nio.pool;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.http.nio.conn.PoolStats;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.SessionRequest;
import org.apache.http.nio.reactor.SessionRequestCallback;

public class SessionPool<T> {

    private final ConnectingIOReactor ioreactor;
    private final SessionRequestCallback sessionRequestCallback;
    private final RouteResolver<T> routeResolver;
    private final Map<T, SessionPoolForRoute<T>> routeToPool;
    private final LinkedList<LeaseRequest<T>> leasingRequests;
    private final Set<SessionRequest> pendingSessions;
    private final Set<PoolEntry<T>> leasedSessions;
    private final LinkedList<PoolEntry<T>> availableSessions;
    private final Map<T, Integer> maxPerRoute;
    private final Lock lock;

    private volatile boolean isShutDown;
    private volatile int defaultMaxPerRoute;
    private volatile int maxTotal;

    public SessionPool(
            final ConnectingIOReactor ioreactor,
            final RouteResolver<T> routeResolver,
            int defaultMaxPerRoute,
            int maxTotal) {
        super();
        if (ioreactor == null) {
            throw new IllegalArgumentException("I/O reactor may not be null");
        }
        if (routeResolver == null) {
            throw new IllegalArgumentException("Route resolver may not be null");
        }
        this.ioreactor = ioreactor;
        this.sessionRequestCallback = new InternalSessionRequestCallback();
        this.routeResolver = routeResolver;
        this.routeToPool = new HashMap<T, SessionPoolForRoute<T>>();
        this.leasingRequests = new LinkedList<LeaseRequest<T>>();
        this.pendingSessions = new HashSet<SessionRequest>();
        this.leasedSessions = new HashSet<PoolEntry<T>>();
        this.availableSessions = new LinkedList<PoolEntry<T>>();
        this.maxPerRoute = new HashMap<T, Integer>();
        this.lock = new ReentrantLock();
        this.defaultMaxPerRoute = defaultMaxPerRoute;
        this.maxTotal = maxTotal;
    }

    public void shutdown() {
        if (this.isShutDown) {
            return ;
        }
        this.isShutDown = true;
        this.lock.lock();
        try {
            for (SessionPoolForRoute<T> pool: this.routeToPool.values()) {
                pool.shutdown();
            }
            this.routeToPool.clear();

            this.leasedSessions.clear();
            this.pendingSessions.clear();
            this.availableSessions.clear();
            this.leasingRequests.clear();
        } finally {
            this.lock.unlock();
        }
    }

    private SessionPoolForRoute<T> getPool(final T route) {
        SessionPoolForRoute<T> pool = this.routeToPool.get(route);
        if (pool == null) {
            pool = new SessionPoolForRoute<T>(route);
            this.routeToPool.put(route, pool);
        }
        return pool;
    }

    public void lease(final T route, final Object state, final PoolEntryCallback<T> callback) {
        if (this.isShutDown) {
            throw new IllegalStateException("Session pool has been shut down");
        }
        this.lock.lock();
        try {
            LeaseRequest<T> request = new LeaseRequest<T>(route, state, callback);
            this.leasingRequests.add(request);

            processPendingRequests();
        } finally {
            this.lock.unlock();
        }
    }

    public void release(final PoolEntry<T> entry, boolean reusable) {
        if (this.isShutDown) {
            return;
        }
        this.lock.lock();
        try {
            if (this.leasedSessions.remove(entry)) {
                SessionPoolForRoute<T> pool = getPool(entry.getRoute());
                pool.freeEntry(entry, reusable);
                if (reusable) {
                    this.availableSessions.add(entry);
                }
                processPendingRequests();
            }
        } finally {
            this.lock.unlock();
        }
    }

    private int getAllocatedTotal() {
        return this.leasedSessions.size() +
            this.pendingSessions.size() +
            this.availableSessions.size();
    }

    private void entryShutdown(final PoolEntry<T> entry) {
        IOSession iosession = entry.getIOSession();
        iosession.close();
    }

    private void processPendingRequests() {
        ListIterator<LeaseRequest<T>> it = this.leasingRequests.listIterator();
        while (it.hasNext()) {
            LeaseRequest<T> request = it.next();

            T route = request.getRoute();
            Object state = request.getState();
            PoolEntryCallback<T> callback = request.getCallback();

            if (getAllocatedTotal() >= this.maxTotal) {
                if (!this.availableSessions.isEmpty()) {
                    PoolEntry<T> entry = this.availableSessions.remove();
                    entryShutdown(entry);
                    SessionPoolForRoute<T> pool = getPool(entry.getRoute());
                    pool.freeEntry(entry, false);
                }
            }

            SessionPoolForRoute<T> pool = getPool(request.getRoute());
            PoolEntry<T> entry = pool.getFreeEntry(state);
            if (entry != null) {
                it.remove();
                this.availableSessions.remove(entry);
                this.leasedSessions.add(entry);
                callback.completed(entry);
            } else {
                int max = getMaxPerRoute(route);
                if (pool.getAvailableCount() > 0 && pool.getAllocatedCount() >= max) {
                    entry = pool.deleteLastUsed();
                    if (entry != null) {
                        this.availableSessions.remove(entry);
                        entryShutdown(entry);
                    }
                }
                if (pool.getAllocatedCount() < max) {
                    it.remove();
                    SessionRequest sessionRequest = this.ioreactor.connect(
                            this.routeResolver.resolveRemoteAddress(route),
                            this.routeResolver.resolveLocalAddress(route),
                            route,
                            this.sessionRequestCallback);
                    pool.addPending(sessionRequest, callback);
                }
            }
        }
    }

    protected void requestCompleted(final SessionRequest request) {
        if (this.isShutDown) {
            return;
        }
        @SuppressWarnings("unchecked")
        T route = (T) request.getAttachment();
        this.lock.lock();
        try {
            this.pendingSessions.remove(request);
            SessionPoolForRoute<T> pool = getPool(route);
            PoolEntry<T> entry = pool.completed(request);
            this.leasedSessions.add(entry);
        } finally {
            this.lock.unlock();
        }
    }

    protected void requestCancelled(final SessionRequest request) {
        if (this.isShutDown) {
            return;
        }
        @SuppressWarnings("unchecked")
        T route = (T) request.getAttachment();
        this.lock.lock();
        try {
            this.pendingSessions.remove(request);
            SessionPoolForRoute<T> pool = getPool(route);
            pool.cancelled(request);
        } finally {
            this.lock.unlock();
        }
    }

    protected void requestFailed(final SessionRequest request) {
        if (this.isShutDown) {
            return;
        }
        @SuppressWarnings("unchecked")
        T route = (T) request.getAttachment();
        this.lock.lock();
        try {
            this.pendingSessions.remove(request);
            SessionPoolForRoute<T> pool = getPool(route);
            pool.failed(request);
        } finally {
            this.lock.unlock();
        }
    }

    protected void requestTimeout(final SessionRequest request) {
        if (this.isShutDown) {
            return;
        }
        @SuppressWarnings("unchecked")
        T route = (T) request.getAttachment();
        this.lock.lock();
        try {
            this.pendingSessions.remove(request);
            SessionPoolForRoute<T> pool = getPool(route);
            pool.timeout(request);
        } finally {
            this.lock.unlock();
        }
    }

    private int getMaxPerRoute(final T route) {
        Integer v = this.maxPerRoute.get(route);
        if (v != null) {
            return v.intValue();
        } else {
            return this.defaultMaxPerRoute;
        }
    }

    public void setTotalMax(int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("Max value may not be negative or zero");
        }
        this.lock.lock();
        try {
            this.maxTotal = max;
        } finally {
            this.lock.unlock();
        }
    }

    public void setDefaultMaxPerHost(int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("Max value may not be negative or zero");
        }
        this.lock.lock();
        try {
            this.defaultMaxPerRoute = max;
        } finally {
            this.lock.unlock();
        }
    }

    public void setMaxPerHost(final T route, int max) {
        if (route == null) {
            throw new IllegalArgumentException("Route may not be null");
        }
        if (max <= 0) {
            throw new IllegalArgumentException("Max value may not be negative or zero");
        }
        this.lock.lock();
        try {
            this.maxPerRoute.put(route, max);
        } finally {
            this.lock.unlock();
        }
    }

    public PoolStats getTotalStats() {
        this.lock.lock();
        try {
            return new PoolStats(
                    this.leasedSessions.size(),
                    this.pendingSessions.size(),
                    this.availableSessions.size(),
                    this.maxTotal);
        } finally {
            this.lock.unlock();
        }
    }

    public PoolStats getStats(final T route) {
        this.lock.lock();
        try {
            SessionPoolForRoute<T> pool = getPool(route);
            return new PoolStats(
                    pool.getLeasedCount(),
                    pool.getPendingCount(),
                    pool.getAvailableCount(),
                    getMaxPerRoute(route));
        } finally {
            this.lock.unlock();
        }
    }
    
    @Override
    public String toString() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("[leased: ");
        buffer.append(this.leasedSessions.size());
        buffer.append("][available: ");
        buffer.append(this.availableSessions.size());
        buffer.append("][pending: ");
        buffer.append(this.pendingSessions.size());
        buffer.append("]");
        return super.toString();
    }

    class InternalSessionRequestCallback implements SessionRequestCallback {

        public void completed(final SessionRequest request) {
            requestCompleted(request);
        }

        public void cancelled(final SessionRequest request) {
            requestCancelled(request);
        }

        public void failed(final SessionRequest request) {
            requestFailed(request);
        }

        public void timeout(final SessionRequest request) {
            requestTimeout(request);
        }

    }

}
