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

import java.net.SocketAddress;
import java.nio.channels.ByteChannel;

import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.nio.pool.PoolEntry;
import org.apache.http.nio.conn.IOSessionManager;
import org.apache.http.nio.conn.ManagedIOSession;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.SessionBufferStatus;

public class BasicManagedIOSession implements ManagedIOSession {

    private final IOSessionManager manager;
    private volatile PoolEntry<HttpRoute> entry;
    private volatile boolean released;
    private volatile boolean reusable;

    public BasicManagedIOSession(
            final IOSessionManager manager,
            final PoolEntry<HttpRoute> entry) {
        super();
        this.manager = manager;
        this.entry = entry;
        this.released = false;
        this.reusable = true;
    }

    protected IOSessionManager getManager() {
        return this.manager;
    }

    protected PoolEntry<HttpRoute> getEntry() {
        return this.entry;
    }

    public synchronized void releaseSession() {
        if (this.released) {
            return;
        }
        this.released = true;
        this.manager.releaseSession(this);
        this.entry = null;
    }

    public synchronized void abortSession() {
        if (this.released) {
            return;
        }
        this.released = true;
        this.reusable = false;
        IOSession iosession = this.entry.getIOSession();
        iosession.shutdown();
        this.manager.releaseSession(this);
        this.entry = null;
    }

    public synchronized Object getState() {
        if (this.released) {
            return null;
        }
        return this.entry.getState();
    }

    public synchronized void setState(final Object state) {
        if (this.released) {
            return;
        }
        this.entry.setState(state);
    }

    public boolean isReusable() {
        return this.reusable;
    }

    public synchronized void markNonReusable() {
        if (this.released) {
            return;
        }
        this.reusable = false;
    }

    public synchronized void markReusable() {
        if (this.released) {
            return;
        }
        this.reusable = true;
    }

    public void shutdown() {
        abortSession();
    }

    public void close() {
        releaseSession();
    }

    public int getStatus() {
        return this.released ? IOSession.ACTIVE : IOSession.CLOSED;
    }

    public boolean isClosed() {
        return this.released;
    }

    private void assertValid() {
        if (this.released) {
            throw new IllegalStateException("I/O session has been released");
        }
    }

    private IOSession getIOSession() {
        assertValid();
        return this.entry.getIOSession();
    }

    public synchronized ByteChannel channel() {
        return getIOSession().channel();
    }

    public synchronized boolean hasBufferedInput() {
        return getIOSession().hasBufferedInput();
    }

    public synchronized boolean hasBufferedOutput() {
        return getIOSession().hasBufferedOutput();
    }

    public synchronized int getEventMask() {
        return getIOSession().getEventMask();
    }

    public synchronized void setEvent(int op) {
        getIOSession().setEvent(op);
    }

    public synchronized void clearEvent(int op) {
        getIOSession().clearEvent(op);
    }

    public synchronized void setEventMask(int ops) {
        getIOSession().setEventMask(ops);
    }

    public synchronized SocketAddress getLocalAddress() {
        return getIOSession().getLocalAddress();
    }

    public synchronized SocketAddress getRemoteAddress() {
        return getIOSession().getRemoteAddress();
    }

    public synchronized Object getAttribute(final String name) {
        return getIOSession().getAttribute(name);
    }

    public synchronized Object removeAttribute(final String name) {
        return getIOSession().removeAttribute(name);
    }

    public synchronized void setAttribute(final String name, final Object value) {
        getIOSession().setAttribute(name, value);
    }

    public void setBufferStatus(final SessionBufferStatus bufstatus) {
        throw new UnsupportedOperationException();
    }

    public synchronized int getSocketTimeout() {
        return getIOSession().getSocketTimeout();
    }

    public void setSocketTimeout(final int timeout) {
        getIOSession().setSocketTimeout(timeout);
    }

    @Override
    public String toString() {
        HttpRoute route = this.entry.getRoute();
        StringBuilder buffer = new StringBuilder();
        buffer.append("HTTP connection: ");
        if (route.getLocalAddress() != null) {
            buffer.append(route.getLocalAddress());
            buffer.append("->");
        }
        for (int i = 0; i < route.getHopCount(); i++) {
            buffer.append(route.getHopTarget(i));
            buffer.append("->");
        }
        buffer.append(route.getTargetHost());
        if (this.released) {
            buffer.append(" (released)");
        }
        return buffer.toString();
    }

}
