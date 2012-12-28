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
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.RouteTracker;
import org.apache.http.impl.conn.ConnectionShutdownException;
import org.apache.http.nio.conn.ClientAsyncConnection;
import org.apache.http.nio.conn.ClientAsyncConnectionFactory;
import org.apache.http.nio.conn.ClientAsyncConnectionManager;
import org.apache.http.nio.conn.ManagedClientAsyncConnection;
import org.apache.http.nio.conn.scheme.AsyncScheme;
import org.apache.http.nio.conn.scheme.AsyncSchemeRegistry;
import org.apache.http.nio.conn.scheme.LayeringStrategy;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.ssl.SSLIOSession;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;

class ManagedClientAsyncConnectionImpl implements ManagedClientAsyncConnection {

    private final ClientAsyncConnectionManager manager;
    private final ClientAsyncConnectionFactory connFactory;
    private volatile HttpPoolEntry poolEntry;
    private volatile boolean reusable;
    private volatile long duration;

    ManagedClientAsyncConnectionImpl(
            final ClientAsyncConnectionManager manager,
            final ClientAsyncConnectionFactory connFactory,
            final HttpPoolEntry poolEntry) {
        super();
        this.manager = manager;
        this.connFactory = connFactory;
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

    public ClientAsyncConnectionManager getManager() {
        return this.manager;
    }

    private ClientAsyncConnection getConnection() {
        HttpPoolEntry local = this.poolEntry;
        if (local == null) {
            return null;
        }
        IOSession session = local.getConnection();
        return (ClientAsyncConnection) session.getAttribute(IOEventDispatch.CONNECTION_KEY);
    }

    private ClientAsyncConnection ensureConnection() {
        HttpPoolEntry local = this.poolEntry;
        if (local == null) {
            throw new ConnectionShutdownException();
        }
        IOSession session = local.getConnection();
        return (ClientAsyncConnection) session.getAttribute(IOEventDispatch.CONNECTION_KEY);
    }

    private HttpPoolEntry ensurePoolEntry() {
        HttpPoolEntry local = this.poolEntry;
        if (local == null) {
            throw new ConnectionShutdownException();
        }
        return local;
    }

    public void close() throws IOException {
        ClientAsyncConnection conn = getConnection();
        if (conn != null) {
            conn.close();
        }
    }

    public void shutdown() throws IOException {
        ClientAsyncConnection conn = getConnection();
        if (conn != null) {
            conn.shutdown();
        }
    }

    public boolean isOpen() {
        ClientAsyncConnection conn = getConnection();
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
        ClientAsyncConnection conn = ensureConnection();
        conn.setSocketTimeout(timeout);
    }

    public int getSocketTimeout() {
        ClientAsyncConnection conn = ensureConnection();
        return conn.getSocketTimeout();
    }

    public HttpConnectionMetrics getMetrics() {
        ClientAsyncConnection conn = ensureConnection();
        return conn.getMetrics();
    }

    public InetAddress getLocalAddress() {
        ClientAsyncConnection conn = ensureConnection();
        return conn.getLocalAddress();
    }

    public int getLocalPort() {
        ClientAsyncConnection conn = ensureConnection();
        return conn.getLocalPort();
    }

    public InetAddress getRemoteAddress() {
        ClientAsyncConnection conn = ensureConnection();
        return conn.getRemoteAddress();
    }

    public int getRemotePort() {
        ClientAsyncConnection conn = ensureConnection();
        return conn.getRemotePort();
    }

    public int getStatus() {
        ClientAsyncConnection conn = ensureConnection();
        return conn.getStatus();
    }

    public HttpRequest getHttpRequest() {
        ClientAsyncConnection conn = ensureConnection();
        return conn.getHttpRequest();
    }

    public HttpResponse getHttpResponse() {
        ClientAsyncConnection conn = ensureConnection();
        return conn.getHttpResponse();
    }

    public HttpContext getContext() {
        ClientAsyncConnection conn = ensureConnection();
        return conn.getContext();
    }

    public void requestInput() {
        ClientAsyncConnection conn = ensureConnection();
        conn.requestInput();
    }

    public void suspendInput() {
        ClientAsyncConnection conn = ensureConnection();
        conn.suspendInput();
    }

    public void requestOutput() {
        ClientAsyncConnection conn = ensureConnection();
        conn.requestOutput();
    }

    public void suspendOutput() {
        ClientAsyncConnection conn = ensureConnection();
        conn.suspendOutput();
    }

    public void submitRequest(final HttpRequest request) throws IOException, HttpException {
        ClientAsyncConnection conn = ensureConnection();
        conn.submitRequest(request);
    }

    public boolean isRequestSubmitted() {
        ClientAsyncConnection conn = ensureConnection();
        return conn.isRequestSubmitted();
    }

    public void resetOutput() {
        ClientAsyncConnection conn = ensureConnection();
        conn.resetOutput();
    }

    public void resetInput() {
        ClientAsyncConnection conn = ensureConnection();
        conn.resetInput();
    }

    public boolean isSecure() {
        ClientAsyncConnection conn = ensureConnection();
        return conn.getIOSession() instanceof SSLIOSession;
    }

    public HttpRoute getRoute() {
        HttpPoolEntry entry = ensurePoolEntry();
        return entry.getEffectiveRoute();
    }

    public SSLSession getSSLSession() {
        ClientAsyncConnection conn = ensureConnection();
        IOSession iosession = conn.getIOSession();
        if (iosession instanceof SSLIOSession) {
            return ((SSLIOSession) iosession).getSSLSession();
        } else {
            return null;
        }
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

    private AsyncSchemeRegistry getSchemeRegistry(final HttpContext context) {
        AsyncSchemeRegistry reg = (AsyncSchemeRegistry) context.getAttribute(
                ClientContext.SCHEME_REGISTRY);
        if (reg == null) {
            reg = this.manager.getSchemeRegistry();
        }
        return reg;
    }

    public synchronized void open(
            final HttpRoute route,
            final HttpContext context,
            final HttpParams params) throws IOException {
        HttpPoolEntry entry = ensurePoolEntry();
        RouteTracker tracker = entry.getTracker();
        if (tracker.isConnected()) {
            throw new IllegalStateException("Connection already open");
        }

        HttpHost target = route.getTargetHost();
        HttpHost proxy = route.getProxyHost();
        IOSession iosession = entry.getConnection();

        if (proxy == null) {
            AsyncScheme scheme = getSchemeRegistry(context).getScheme(target);
            LayeringStrategy layeringStrategy = scheme.getLayeringStrategy();
            if (layeringStrategy != null) {
                iosession = layeringStrategy.layer(iosession);
            }
        }

        ClientAsyncConnection conn = this.connFactory.create(
                "http-outgoing-" + entry.getId(),
                iosession,
                params);
        iosession.setAttribute(IOEventDispatch.CONNECTION_KEY, conn);

        if (proxy == null) {
            tracker.connectTarget(conn.getIOSession() instanceof SSLIOSession);
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
        AsyncScheme scheme = getSchemeRegistry(context).getScheme(target);
        LayeringStrategy layeringStrategy = scheme.getLayeringStrategy();
        if (layeringStrategy == null) {
            throw new IllegalStateException(scheme.getName() +
                    " scheme does not provider support for protocol layering");
        }
        IOSession iosession = entry.getConnection();
        ClientAsyncConnection conn = (ClientAsyncConnection) iosession.getAttribute(
                IOEventDispatch.CONNECTION_KEY);
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
        ClientAsyncConnection conn = (ClientAsyncConnection) iosession.getAttribute(
                IOEventDispatch.CONNECTION_KEY);
        try {
            conn.shutdown();
        } catch (IOException ignore) {
        }
        this.manager.releaseConnection(this, this.duration, TimeUnit.MILLISECONDS);
        this.poolEntry = null;
    }

    @Override
    public synchronized String toString() {
        if (this.poolEntry != null) {
            return this.poolEntry.toString();
        } else {
            return "released";
        }
    }

}
