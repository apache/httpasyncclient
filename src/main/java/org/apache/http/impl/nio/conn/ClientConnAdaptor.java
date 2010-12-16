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

import org.apache.http.HttpConnectionMetrics;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.RouteTracker;
import org.apache.http.impl.conn.ConnectionShutdownException;
import org.apache.http.nio.NHttpConnection;
import org.apache.http.nio.conn.ClientConnectionManager;
import org.apache.http.nio.conn.ManagedClientConnection;
import org.apache.http.nio.conn.OperatedClientConnection;
import org.apache.http.nio.conn.scheme.LayeringStrategy;
import org.apache.http.nio.conn.scheme.Scheme;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.protocol.HttpContext;

class ClientConnAdaptor implements ManagedClientConnection {

    private final ClientConnectionManager manager;
    private volatile HttpPoolEntry entry;
    private volatile OperatedClientConnection conn;
    private volatile boolean released;
    private volatile boolean reusable;

    public ClientConnAdaptor(
            final ClientConnectionManager manager,
            final HttpPoolEntry entry,
            final OperatedClientConnection conn) {
        super();
        this.manager = manager;
        this.entry = entry;
        this.conn = conn;
        this.released = false;
        this.reusable = true;
    }

    protected ClientConnectionManager getManager() {
        return this.manager;
    }

    protected HttpPoolEntry getEntry() {
        return this.entry;
    }

    public synchronized void releaseConnection() {
        if (this.released) {
            return;
        }
        this.released = true;
        this.manager.releaseConnection(this);
        this.entry = null;
        this.conn = null;
    }

    public synchronized void abortConnection() {
        if (this.released) {
            return;
        }
        this.released = true;
        this.reusable = false;
        IOSession iosession = this.entry.getIOSession();
        iosession.shutdown();
        this.manager.releaseConnection(this);
        this.entry = null;
        this.conn = null;
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
        abortConnection();
    }

    public void close() {
        releaseConnection();
    }

    public boolean isOpen() {
        return !this.released;
    }

    public boolean isStale() {
        return !this.released;
    }

    private void assertValid() {
        if (this.released) {
            throw new ConnectionShutdownException();
        }
    }

    public HttpRoute getRoute() {
        assertValid();
        return this.entry.getEffectiveRoute();
    }

    public synchronized HttpConnectionMetrics getMetrics() {
        assertValid();
        return this.conn.getMetrics();
    }

    public synchronized int getSocketTimeout() {
        assertValid();
        return this.conn.getSocketTimeout();
    }

    public synchronized void setSocketTimeout(int timeout) {
        assertValid();
        this.conn.setSocketTimeout(timeout);
    }

    public int getStatus() {
        return this.released ? NHttpConnection.CLOSED : NHttpConnection.ACTIVE;
    }

    public synchronized HttpContext getContext() {
        assertValid();
        return this.conn.getContext();
    }

    public synchronized HttpRequest getHttpRequest() {
        assertValid();
        return this.conn.getHttpRequest();
    }

    public synchronized HttpResponse getHttpResponse() {
        assertValid();
        return this.conn.getHttpResponse();
    }

    public synchronized void requestInput() {
        assertValid();
        this.conn.requestInput();
    }

    public synchronized void requestOutput() {
        assertValid();
        this.conn.requestOutput();
    }

    public synchronized void suspendInput() {
        assertValid();
        this.conn.suspendInput();
    }

    public synchronized void suspendOutput() {
        assertValid();
        this.conn.suspendOutput();
    }

    public synchronized boolean isRequestSubmitted() {
        assertValid();
        return this.conn.isRequestSubmitted();
    }

    public synchronized void resetInput() {
        assertValid();
        this.conn.resetInput();
    }

    public synchronized void resetOutput() {
        assertValid();
        this.conn.resetOutput();
    }

    public synchronized void submitRequest(
            final HttpRequest request) throws IOException, HttpException {
        assertValid();
        this.conn.submitRequest(request);
    }

    public synchronized void updateOpen(final HttpRoute route) {
        assertValid();
        RouteTracker tracker = this.entry.getTracker();
        if (tracker.isConnected()) {
            throw new IllegalStateException("Connection already open");
        }
        HttpHost target = route.getTargetHost();
        HttpHost proxy = route.getProxyHost();
        if (proxy == null) {
            Scheme scheme = this.manager.getSchemeRegistry().getScheme(target);
            LayeringStrategy layeringStrategy = scheme.getLayeringStrategy();
            if (layeringStrategy != null) {
                IOSession iosession = this.entry.getIOSession();
                iosession = layeringStrategy.layer(iosession);
                this.conn.upgrade(iosession);
                tracker.connectTarget(layeringStrategy.isSecure());
            } else {
                tracker.connectTarget(false);
            }
        } else {
            tracker.connectProxy(proxy, false);
        }
    }

    public synchronized void updateTunnelProxy(final HttpHost next){
        assertValid();
        RouteTracker tracker = this.entry.getTracker();
        if (!tracker.isConnected()) {
            throw new IllegalStateException("Connection not open");
        }
        tracker.tunnelProxy(next, false);
    }

    public synchronized void updateTunnelTarget() {
        assertValid();
        RouteTracker tracker = this.entry.getTracker();
        if (!tracker.isConnected()) {
            throw new IllegalStateException("Connection not open");
        }
        if (tracker.isTunnelled()) {
            throw new IllegalStateException("Connection is already tunnelled");
        }
        tracker.tunnelTarget(false);
    }

    public synchronized void updateLayered(){
        assertValid();
        RouteTracker tracker = this.entry.getTracker();
        if (!tracker.isConnected()) {
            throw new IllegalStateException("Connection not open");
        }
        if (!tracker.isTunnelled()) {
            throw new IllegalStateException("Protocol layering without a tunnel not supported");
        }
        if (tracker.isLayered()) {
            throw new IllegalStateException("Multiple protocol layering not supported");
        }
        HttpHost target = tracker.getTargetHost();
        Scheme scheme = this.manager.getSchemeRegistry().getScheme(target);
        LayeringStrategy layeringStrategy = scheme.getLayeringStrategy();
        if (layeringStrategy == null) {
            throw new IllegalStateException(scheme.getName() +
                    " scheme does not provider support for protocol layering");
        }
        IOSession iosession = this.entry.getIOSession();
        iosession = layeringStrategy.layer(iosession);
        this.conn.upgrade(iosession);
        tracker.layerProtocol(layeringStrategy.isSecure());
    }

    @Override
    public synchronized String toString() {
        HttpRoute route = this.entry.getPlannedRoute();
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
