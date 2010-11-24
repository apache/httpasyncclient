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

import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.nio.pool.PoolEntry;
import org.apache.http.nio.conn.IOSessionManager;
import org.apache.http.nio.conn.ManagedIOSession;
import org.apache.http.nio.reactor.IOSession;

public class BasicManagedIOSession implements ManagedIOSession {

    private final IOSessionManager<HttpRoute> manager;
    private volatile PoolEntry<HttpRoute> entry;
    private volatile boolean released;
    private volatile boolean reusable;

    public BasicManagedIOSession(
            final IOSessionManager<HttpRoute> manager,
            final PoolEntry<HttpRoute> entry) {
        super();
        this.manager = manager;
        this.entry = entry;
        this.released = false;
        this.reusable = true;
    }

    protected IOSessionManager<HttpRoute> getManager() {
        return this.manager;
    }

    protected PoolEntry<HttpRoute> getEntry() {
        return this.entry;
    }

    public IOSession getSession() {
        if (this.released) {
            return null;
        }
        return this.entry.getIOSession();
    }

    public void releaseSession() {
        if (this.released) {
            return;
        }
        this.released = true;
        this.manager.releaseSession(this);
        this.entry = null;
    }

    public void abortSession() {
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

    public Object getState() {
        if (this.released) {
            return null;
        }
        return this.entry.getState();
    }

    public void setState(final Object state) {
        if (this.released) {
            return;
        }
        this.entry.setState(state);
    }

    public boolean isReusable() {
        return this.reusable;
    }

    public void markNonReusable() {
        if (this.released) {
            return;
        }
        this.reusable = false;
    }

    public void markReusable() {
        if (this.released) {
            return;
        }
        this.reusable = true;
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
