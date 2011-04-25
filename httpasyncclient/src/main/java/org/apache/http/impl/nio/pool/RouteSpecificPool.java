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

import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.SessionRequest;

class RouteSpecificPool<T, E extends PoolEntry<T>> {

    private final T route;
    private final PoolEntryFactory<T, E> factory;
    private final Set<E> leasedSessions;
    private final LinkedList<E> availableSessions;
    private final Map<SessionRequest, PoolEntryCallback<T, E>> pendingSessions;

    public RouteSpecificPool(final T route, final PoolEntryFactory<T, E> factory) {
        super();
        this.route = route;
        this.factory = factory;
        this.leasedSessions = new HashSet<E>();
        this.availableSessions = new LinkedList<E>();
        this.pendingSessions = new HashMap<SessionRequest, PoolEntryCallback<T, E>>();
    }

    public int getLeasedCount() {
        return this.leasedSessions.size();
    }

    public int getPendingCount() {
        return this.pendingSessions.size();
    }

    public int getAvailableCount() {
        return this.availableSessions.size();
    }

    public int getAllocatedCount() {
        return this.availableSessions.size() + this.leasedSessions.size() + this.pendingSessions.size();
    }

    public E getFreeEntry(final Object state) {
        if (!this.availableSessions.isEmpty()) {
            ListIterator<E> it = this.availableSessions.listIterator(this.availableSessions.size());
            while (it.hasPrevious()) {
                E entry = it.previous();
                if (entry.getState() == null || entry.getState().equals(state)) {
                    it.remove();
                    this.leasedSessions.add(entry);
                    return entry;
                }
            }
        }
        return null;
    }

    public E deleteLastUsed() {
        return this.availableSessions.poll();
    }

    public boolean remove(final E entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Pool entry may not be null");
        }
        boolean foundLeased = this.leasedSessions.remove(entry);
        boolean foundFree = this.availableSessions.remove(entry);
        return foundLeased || foundFree;
    }

    public void freeEntry(final E entry, boolean reusable) {
        if (entry == null) {
            throw new IllegalArgumentException("Pool entry may not be null");
        }
        boolean found = this.leasedSessions.remove(entry);
        if (!found) {
            throw new IllegalStateException("Entry " + entry +
                    " has not been leased from this pool");
        }
        if (reusable) {
            this.availableSessions.add(entry);
        }
    }

    public void addPending(
            final SessionRequest sessionRequest,
            final PoolEntryCallback<T, E> callback) {
        this.pendingSessions.put(sessionRequest, callback);
    }

    private PoolEntryCallback<T, E> removeRequest(final SessionRequest request) {
        PoolEntryCallback<T, E> callback = this.pendingSessions.remove(request);
        if (callback == null) {
            throw new IllegalStateException("Invalid session request");
        }
        return callback;
    }

    public E completed(final SessionRequest request) {
        PoolEntryCallback<T, E> callback = removeRequest(request);
        IOSession iosession = request.getSession();
        E entry = this.factory.createEntry(this.route, iosession);
        this.leasedSessions.add(entry);
        callback.completed(entry);
        return entry;
    }

    public void cancelled(final SessionRequest request) {
        PoolEntryCallback<T, E> callback = removeRequest(request);
        callback.cancelled();
    }

    public void failed(final SessionRequest request) {
        PoolEntryCallback<T, E> callback = removeRequest(request);
        callback.failed(request.getException());
    }

    public void timeout(final SessionRequest request) {
        PoolEntryCallback<T, E> callback = removeRequest(request);
        callback.failed(new SocketTimeoutException());
    }

    public void shutdown() {
        for (SessionRequest sessionRequest: this.pendingSessions.keySet()) {
            sessionRequest.cancel();
        }
        this.pendingSessions.clear();
        for (E entry: this.availableSessions) {
            entry.getIOSession().close();
        }
        this.availableSessions.clear();
        for (E entry: this.leasedSessions) {
            entry.getIOSession().close();
        }
        this.leasedSessions.clear();
    }

    @Override
    public String toString() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("[route: ");
        buffer.append(this.route);
        buffer.append("][leased: ");
        buffer.append(this.leasedSessions.size());
        buffer.append("][available: ");
        buffer.append(this.availableSessions.size());
        buffer.append("][pending: ");
        buffer.append(this.pendingSessions.size());
        buffer.append("]");
        return super.toString();
    }

}
