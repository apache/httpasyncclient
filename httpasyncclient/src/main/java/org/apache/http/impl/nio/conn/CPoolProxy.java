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

import javax.net.ssl.SSLSession;

import org.apache.http.HttpConnectionMetrics;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.annotation.NotThreadSafe;
import org.apache.http.impl.conn.ConnectionShutdownException;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.conn.ManagedNHttpClientConnection;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.protocol.HttpContext;

@NotThreadSafe
class CPoolProxy implements ManagedNHttpClientConnection {

    private volatile CPoolEntry poolEntry;

    CPoolProxy(final CPoolEntry entry) {
        super();
        this.poolEntry = entry;
    }

    CPoolEntry getPoolEntry() {
        return this.poolEntry;
    }

    CPoolEntry detach() {
        final CPoolEntry local = this.poolEntry;
        this.poolEntry = null;
        return local;
    }

    ManagedNHttpClientConnection getConnection() {
        final CPoolEntry local = this.poolEntry;
        if (local == null) {
            return null;
        }
        return local.getConnection();
    }

    ManagedNHttpClientConnection getValidConnection() {
        final ManagedNHttpClientConnection conn = getConnection();
        if (conn == null) {
            throw new ConnectionShutdownException();
        }
        return conn;
    }

    public void close() throws IOException {
        final CPoolEntry local = this.poolEntry;
        if (local != null) {
            local.closeConnection();
        }
    }

    public void shutdown() throws IOException {
        final CPoolEntry local = this.poolEntry;
        if (local != null) {
            local.shutdownConnection();
        }
    }

    public HttpConnectionMetrics getMetrics() {
        return getValidConnection().getMetrics();
    }

    public void requestInput() {
        final NHttpClientConnection conn = getConnection();
        if (conn != null) {
            conn.requestInput();
        }
    }

    public void suspendInput() {
        final NHttpClientConnection conn = getConnection();
        if (conn != null) {
            conn.suspendInput();
        }
    }

    public void requestOutput() {
        final NHttpClientConnection conn = getConnection();
        if (conn != null) {
            conn.requestOutput();
        }
    }

    public void suspendOutput() {
        final NHttpClientConnection conn = getConnection();
        if (conn != null) {
            conn.suspendOutput();
        }
    }

    public InetAddress getLocalAddress() {
        return getValidConnection().getLocalAddress();
    }

    public int getLocalPort() {
        return getValidConnection().getLocalPort();
    }

    public InetAddress getRemoteAddress() {
        return getValidConnection().getRemoteAddress();
    }

    public int getRemotePort() {
        return getValidConnection().getRemotePort();
    }

    public boolean isOpen() {
        final CPoolEntry local = this.poolEntry;
        if (local != null) {
            return !local.isClosed();
        } else {
            return false;
        }
    }

    public boolean isStale() {
        final NHttpClientConnection conn = getConnection();
        if (conn != null) {
            return !conn.isOpen();
        } else {
            return false;
        }
    }

    public void setSocketTimeout(final int i) {
        getValidConnection().setSocketTimeout(i);
    }

    public int getSocketTimeout() {
        return getValidConnection().getSocketTimeout();
    }

    public void submitRequest(final HttpRequest request) throws IOException, HttpException {
        getValidConnection().submitRequest(request);
    }

    public boolean isRequestSubmitted() {
        return getValidConnection().isRequestSubmitted();
    }

    public void resetOutput() {
        getValidConnection().resetOutput();
    }

    public void resetInput() {
        getValidConnection().resetInput();
    }

    public int getStatus() {
        return getValidConnection().getStatus();
    }

    public HttpRequest getHttpRequest() {
        return getValidConnection().getHttpRequest();
    }

    public HttpResponse getHttpResponse() {
        return getValidConnection().getHttpResponse();
    }

    public HttpContext getContext() {
        return getValidConnection().getContext();
    }

    public static NHttpClientConnection newProxy(final CPoolEntry poolEntry) {
        return new CPoolProxy(poolEntry);
    }

    private static CPoolProxy getProxy(final NHttpClientConnection conn) {
        if (!CPoolProxy.class.isInstance(conn)) {
            throw new IllegalStateException("Unexpected connection proxy class: " + conn.getClass());
        }
        return CPoolProxy.class.cast(conn);
    }

    public static CPoolEntry getPoolEntry(final NHttpClientConnection proxy) {
        final CPoolEntry entry = getProxy(proxy).getPoolEntry();
        if (entry == null) {
            throw new ConnectionShutdownException();
        }
        return entry;
    }

    public static CPoolEntry detach(final NHttpClientConnection proxy) {
        return getProxy(proxy).detach();
    }

    public String getId() {
        return getValidConnection().getId();
    }

    public void bind(final IOSession iosession) {
        getValidConnection().bind(iosession);
    }

    public IOSession getIOSession() {
        return getValidConnection().getIOSession();
    }

    public SSLSession getSSLSession() {
        return getValidConnection().getSSLSession();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("CPoolProxy{");
        final ManagedNHttpClientConnection conn = getConnection();
        if (conn != null) {
            sb.append(conn);
        } else {
            sb.append("detached");
        }
        sb.append('}');
        return sb.toString();
    }

}
