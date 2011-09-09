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
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.RouteTracker;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.conn.ConnectionShutdownException;
import org.apache.http.nio.conn.ClientConnectionManager;
import org.apache.http.nio.conn.ManagedClientConnection;
import org.apache.http.nio.conn.OperatedClientConnection;
import org.apache.http.nio.conn.scheme.LayeringStrategy;
import org.apache.http.nio.conn.scheme.Scheme;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.ssl.SSLIOSession;
import org.apache.http.nio.util.ByteBufferAllocator;
import org.apache.http.nio.util.HeapByteBufferAllocator;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;

class ManagedClientConnectionImpl implements ManagedClientConnection {

    private final ClientConnectionManager manager;
    private volatile HttpPoolEntry poolEntry;
    private volatile boolean reusable;
    private volatile long duration;

    public ManagedClientConnectionImpl(
            final ClientConnectionManager manager,
            final HttpPoolEntry poolEntry) {
        super();
        this.manager = manager;
        this.poolEntry = poolEntry;
        this.reusable = true;
        this.duration = Long.MAX_VALUE;
    }

    HttpPoolEntry getPoolEntry() {
        return this.poolEntry;
    }

    HttpPoolEntry detach() {
        HttpPoolEntry local = this.poolEntry;
        this.poolEntry = null;
        return local;
    }

    public ClientConnectionManager getManager() {
        return this.manager;
    }

    private OperatedClientConnection getConnection() {
        HttpPoolEntry local = this.poolEntry;
        if (local == null) {
            return null;
        }
        IOSession session = local.getConnection();
        return (OperatedClientConnection) session.getAttribute(
                ExecutionContext.HTTP_CONNECTION);
    }

    private OperatedClientConnection ensureConnection() {
        HttpPoolEntry local = this.poolEntry;
        if (local == null) {
            throw new ConnectionShutdownException();
        }
        IOSession session = local.getConnection();
        return (OperatedClientConnection) session.getAttribute(
                ExecutionContext.HTTP_CONNECTION);
    }

    private HttpPoolEntry ensurePoolEntry() {
        HttpPoolEntry local = this.poolEntry;
        if (local == null) {
            throw new ConnectionShutdownException();
        }
        return local;
    }

    public void close() throws IOException {
        OperatedClientConnection conn = getConnection();
        if (conn != null) {
            conn.close();
        }
    }

    public void shutdown() throws IOException {
        OperatedClientConnection conn = getConnection();
        if (conn != null) {
            conn.shutdown();
        }
    }

    public boolean isOpen() {
        OperatedClientConnection conn = getConnection();
        if (conn != null) {
            return conn.isOpen();
        } else {
            return false;
        }
    }

    public boolean isStale() {
        return isOpen();
    }

    public void setSocketTimeout(int timeout) {
        OperatedClientConnection conn = ensureConnection();
        conn.setSocketTimeout(timeout);
    }

    public int getSocketTimeout() {
        OperatedClientConnection conn = ensureConnection();
        return conn.getSocketTimeout();
    }

    public HttpConnectionMetrics getMetrics() {
        OperatedClientConnection conn = ensureConnection();
        return conn.getMetrics();
    }

    public InetAddress getLocalAddress() {
        OperatedClientConnection conn = ensureConnection();
        return conn.getLocalAddress();
    }

    public int getLocalPort() {
        OperatedClientConnection conn = ensureConnection();
        return conn.getLocalPort();
    }

    public InetAddress getRemoteAddress() {
        OperatedClientConnection conn = ensureConnection();
        return conn.getRemoteAddress();
    }

    public int getRemotePort() {
        OperatedClientConnection conn = ensureConnection();
        return conn.getRemotePort();
    }

    public int getStatus() {
        OperatedClientConnection conn = ensureConnection();
        return conn.getStatus();
    }

    public HttpRequest getHttpRequest() {
        OperatedClientConnection conn = ensureConnection();
        return conn.getHttpRequest();
    }

    public HttpResponse getHttpResponse() {
        OperatedClientConnection conn = ensureConnection();
        return conn.getHttpResponse();
    }

    public HttpContext getContext() {
        OperatedClientConnection conn = ensureConnection();
        return conn.getContext();
    }

    public void requestInput() {
        OperatedClientConnection conn = ensureConnection();
        conn.requestInput();
    }

    public void suspendInput() {
        OperatedClientConnection conn = ensureConnection();
        conn.suspendInput();
    }

    public void requestOutput() {
        OperatedClientConnection conn = ensureConnection();
        conn.requestOutput();
    }

    public void suspendOutput() {
        OperatedClientConnection conn = ensureConnection();
        conn.suspendOutput();
    }

    public void submitRequest(final HttpRequest request) throws IOException, HttpException {
        OperatedClientConnection conn = ensureConnection();
        conn.submitRequest(request);
    }

    public boolean isRequestSubmitted() {
        OperatedClientConnection conn = ensureConnection();
        return conn.isRequestSubmitted();
    }

    public void resetOutput() {
        OperatedClientConnection conn = ensureConnection();
        conn.resetOutput();
    }

    public void resetInput() {
        OperatedClientConnection conn = ensureConnection();
        conn.resetInput();
    }

    public boolean isSecure() {
        OperatedClientConnection conn = ensureConnection();
        return conn.getSSLIOSession() != null;
    }

    public HttpRoute getRoute() {
        HttpPoolEntry entry = ensurePoolEntry();
        return entry.getEffectiveRoute();
    }

    public SSLSession getSSLSession() {
        OperatedClientConnection conn = ensureConnection();
        SSLIOSession iosession = conn.getSSLIOSession();
        return iosession != null ? iosession.getSSLSession() : null;
    }

    public Object getState() {
        HttpPoolEntry entry = ensurePoolEntry();
        return entry.getState();
    }

    public void setState(final Object state) {
        HttpPoolEntry entry = ensurePoolEntry();
        entry.setState(state);
    }

    public void markReusable() {
        this.reusable = true;
    }

    public void unmarkReusable() {
        this.reusable = false;
    }

    public boolean isMarkedReusable() {
        return this.reusable;
    }

    public void setIdleDuration(long duration, TimeUnit unit) {
        if(duration > 0) {
            this.duration = unit.toMillis(duration);
        } else {
            this.duration = -1;
        }
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
        HttpPoolEntry entry = ensurePoolEntry();
        RouteTracker tracker = entry.getTracker();
        if (tracker.isConnected()) {
            throw new IllegalStateException("Connection already open");
        }

        HttpHost target = route.getTargetHost();
        HttpHost proxy = route.getProxyHost();
        IOSession iosession = entry.getConnection();

        if (proxy == null) {
            Scheme scheme = this.manager.getSchemeRegistry().getScheme(target);
            LayeringStrategy layeringStrategy = scheme.getLayeringStrategy();
            if (layeringStrategy != null) {
                iosession = layeringStrategy.layer(iosession);
            }
        }

        OperatedClientConnection conn = new DefaultClientConnection(
                "http-outgoing-" + entry.getId(),
                iosession,
                createHttpResponseFactory(),
                createByteBufferAllocator(),
                params);
        iosession.setAttribute(ExecutionContext.HTTP_CONNECTION, conn);

        if (proxy == null) {
            tracker.connectTarget(conn.getSSLIOSession() != null);
        } else {
            tracker.connectProxy(proxy, false);
        }
    }

    public synchronized void tunnelProxy(
            final HttpHost next, final HttpParams params) throws IOException {
        HttpPoolEntry entry = ensurePoolEntry();
        RouteTracker tracker = entry.getTracker();
        if (!tracker.isConnected()) {
            throw new IllegalStateException("Connection not open");
        }
        tracker.tunnelProxy(next, false);
    }

    public synchronized void tunnelTarget(
            final HttpParams params) throws IOException {
        HttpPoolEntry entry = ensurePoolEntry();
        RouteTracker tracker = entry.getTracker();
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
        HttpPoolEntry entry = ensurePoolEntry();
        RouteTracker tracker = entry.getTracker();
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
        IOSession iosession = entry.getConnection();
        OperatedClientConnection conn = (OperatedClientConnection) iosession.getAttribute(
                ExecutionContext.HTTP_CONNECTION);
        conn.upgrade((SSLIOSession) layeringStrategy.layer(iosession));
        tracker.layerProtocol(layeringStrategy.isSecure());
    }

    public synchronized void releaseConnection() {
        if (this.poolEntry == null) {
            return;
        }
        this.manager.releaseConnection(this, this.duration, TimeUnit.MILLISECONDS);
        this.poolEntry = null;
    }

    public synchronized void abortConnection() {
        if (this.poolEntry == null) {
            return;
        }
        this.reusable = false;
        IOSession iosession = this.poolEntry.getConnection();
        OperatedClientConnection conn = (OperatedClientConnection) iosession.getAttribute(
                ExecutionContext.HTTP_CONNECTION);
        try {
            conn.shutdown();
        } catch (IOException ignore) {
        }
        this.manager.releaseConnection(this, this.duration, TimeUnit.MILLISECONDS);
        this.poolEntry = null;
    }

}
