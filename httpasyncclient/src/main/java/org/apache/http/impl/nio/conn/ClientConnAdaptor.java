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
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSession;

import org.apache.http.HttpConnectionMetrics;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpInetConnection;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.RouteTracker;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.conn.ConnectionShutdownException;
import org.apache.http.impl.nio.reactor.SSLIOSession;
import org.apache.http.impl.nio.reactor.SSLMode;
import org.apache.http.nio.NHttpConnection;
import org.apache.http.nio.conn.ClientConnectionManager;
import org.apache.http.nio.conn.ManagedClientConnection;
import org.apache.http.nio.conn.OperatedClientConnection;
import org.apache.http.nio.conn.scheme.LayeringStrategy;
import org.apache.http.nio.conn.scheme.Scheme;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.util.ByteBufferAllocator;
import org.apache.http.nio.util.HeapByteBufferAllocator;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;

class ClientConnAdaptor implements ManagedClientConnection {

    private final ClientConnectionManager manager;
    private HttpPoolEntry entry;
    private OperatedClientConnection conn;
    private volatile boolean released;
    private volatile boolean reusable;
    private long expiry;
    private TimeUnit tunit;

    public ClientConnAdaptor(
            final ClientConnectionManager manager,
            final HttpPoolEntry entry) {
        super();
        this.manager = manager;
        this.entry = entry;
        this.released = false;
        this.reusable = true;
        this.expiry = -1;
        this.tunit = TimeUnit.MILLISECONDS;
        this.conn = entry.getConnection();
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
        this.manager.releaseConnection(this, this.expiry, this.tunit);
        this.entry = null;
        this.conn = null;
    }

    public synchronized void abortConnection() {
        if (this.released) {
            return;
        }
        this.released = true;
        this.reusable = false;
        this.manager.releaseConnection(this, this.expiry, this.tunit);
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
        return this.reusable && this.conn != null && this.conn.isOpen();
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

    public synchronized void shutdown() throws IOException {
        if (this.released) {
            return;
        }
        this.conn.shutdown();
        this.reusable = false;
    }

    public synchronized void close() throws IOException {
        if (this.released) {
            return;
        }
        this.conn.close();
        this.reusable = false;
        releaseConnection();
    }

    private void assertValid() {
        if (this.released) {
            throw new ConnectionShutdownException();
        }
    }

    private OperatedClientConnection getWrappedConnection() {
        OperatedClientConnection conn = this.conn;
        if (conn == null) {
            throw new ConnectionShutdownException();
        }
        return conn;
    }

    public synchronized boolean isOpen() {
        return !this.released && this.conn != null && this.conn.isOpen();
    }

    public boolean isStale() {
        return isOpen();
    }

    public HttpRoute getRoute() {
        assertValid();
        return this.entry.getEffectiveRoute();
    }

    public SSLSession getSSLSession() {
        return null;
    }

    public boolean isSecure() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        return conn.getSSLIOSession() != null;
    }

    public InetAddress getLocalAddress() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        if (conn instanceof HttpInetConnection) {
            return ((HttpInetConnection) conn).getLocalAddress();
        } else {
            return null;
        }
    }

    public int getLocalPort() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        if (conn instanceof HttpInetConnection) {
            return ((HttpInetConnection) conn).getLocalPort();
        } else {
            return -1;
        }
    }

    public InetAddress getRemoteAddress() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        if (conn instanceof HttpInetConnection) {
            return ((HttpInetConnection) conn).getRemoteAddress();
        } else {
            return null;
        }
    }

    public int getRemotePort() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        if (conn instanceof HttpInetConnection) {
            return ((HttpInetConnection) conn).getRemotePort();
        } else {
            return -1;
        }
    }

    public synchronized HttpConnectionMetrics getMetrics() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        return conn.getMetrics();
    }

    public synchronized int getSocketTimeout() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        return conn.getSocketTimeout();
    }

    public synchronized void setSocketTimeout(int timeout) {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        conn.setSocketTimeout(timeout);
    }

    public synchronized int getStatus() {
        return this.conn == null || !this.conn.isOpen() ?
                NHttpConnection.CLOSED : NHttpConnection.ACTIVE;
    }

    public synchronized HttpContext getContext() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        return conn.getContext();
    }

    public synchronized HttpRequest getHttpRequest() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        return conn.getHttpRequest();
    }

    public synchronized HttpResponse getHttpResponse() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        return conn.getHttpResponse();
    }

    public synchronized void requestInput() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        conn.requestInput();
    }

    public synchronized void requestOutput() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        conn.requestOutput();
    }

    public synchronized void suspendInput() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        conn.suspendInput();
    }

    public synchronized void suspendOutput() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        conn.suspendOutput();
    }

    public synchronized boolean isRequestSubmitted() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        return conn.isRequestSubmitted();
    }

    public synchronized void resetInput() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        conn.resetInput();
    }

    public synchronized void resetOutput() {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        conn.resetOutput();
    }

    public synchronized void submitRequest(
            final HttpRequest request) throws IOException, HttpException {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
        conn.submitRequest(request);
    }

    protected ByteBufferAllocator createByteBufferAllocator() {
        return new HeapByteBufferAllocator();
    }

    protected HttpResponseFactory createHttpResponseFactory() {
        return new DefaultHttpResponseFactory();
    }

    public synchronized void open(
            final HttpRoute route,
            final HttpContext context, final HttpParams params) throws IOException {
        assertValid();
        RouteTracker tracker = this.entry.getTracker();
        if (tracker.isConnected()) {
            throw new IllegalStateException("Connection already open");
        }

        HttpHost target = route.getTargetHost();
        HttpHost proxy = route.getProxyHost();
        IOSession iosession = this.entry.getIOSession();

        if (proxy == null) {
            Scheme scheme = this.manager.getSchemeRegistry().getScheme(target);
            LayeringStrategy layeringStrategy = scheme.getLayeringStrategy();
            if (layeringStrategy != null) {
                SSLIOSession ssliosession = (SSLIOSession) layeringStrategy.layer(iosession);
                ssliosession.bind(SSLMode.CLIENT, params);
                iosession = ssliosession;
            }
        }

        OperatedClientConnection conn = new DefaultClientConnection(
                iosession, createHttpResponseFactory(), createByteBufferAllocator(), params);
        iosession.setAttribute(ExecutionContext.HTTP_CONNECTION, conn);

        this.conn = conn;
        if (proxy == null) {
            tracker.connectTarget(conn.getSSLIOSession() != null);
        } else {
            tracker.connectProxy(proxy, false);
        }
    }

    public synchronized void tunnelProxy(
            final HttpHost next, final HttpParams params) throws IOException {
        assertValid();
        RouteTracker tracker = this.entry.getTracker();
        if (!tracker.isConnected()) {
            throw new IllegalStateException("Connection not open");
        }
        tracker.tunnelProxy(next, false);
    }

    public synchronized void tunnelTarget(
            final HttpParams params) throws IOException {
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

    public synchronized void layerProtocol(
            final HttpContext context, final HttpParams params) throws IOException {
        assertValid();
        OperatedClientConnection conn = getWrappedConnection();
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
        SSLIOSession ssliosession = (SSLIOSession) layeringStrategy.layer(iosession);
        ssliosession.bind(SSLMode.CLIENT, params);

        conn.upgrade(ssliosession);
        tracker.layerProtocol(layeringStrategy.isSecure());
    }

    public synchronized void setIdleDuration(final long duration, final TimeUnit tunit) {
        if (tunit == null) {
            throw new IllegalArgumentException("Time unit may not be null");
        }
        this.expiry = duration;
        this.tunit = tunit;
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
