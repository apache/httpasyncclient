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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.apache.http.HttpConnection;
import org.apache.http.annotation.NotThreadSafe;
import org.apache.http.impl.conn.ConnectionShutdownException;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.conn.ManagedNHttpClientConnection;
import org.apache.http.util.Asserts;

@NotThreadSafe
class CPoolProxy implements InvocationHandler {

    private static final Method CLOSE_METHOD;
    private static final Method SHUTDOWN_METHOD;
    private static final Method IS_OPEN_METHOD;
    private static final Method IS_STALE_METHOD;

    static {
        try {
            CLOSE_METHOD = HttpConnection.class.getMethod("close");
            SHUTDOWN_METHOD = HttpConnection.class.getMethod("shutdown");
            IS_OPEN_METHOD = HttpConnection.class.getMethod("isOpen");
            IS_STALE_METHOD = HttpConnection.class.getMethod("isStale");
        } catch (final NoSuchMethodException ex) {
            throw new Error(ex);
        }
    }

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

    NHttpClientConnection getConnection() {
        final CPoolEntry local = this.poolEntry;
        if (local == null) {
            return null;
        }
        return local.getConnection();
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
            return conn.isStale() || !conn.isOpen();
        } else {
            return true;
        }
    }

    public Object invoke(
            final Object proxy, final Method method, final Object[] args) throws Throwable {
        if (method.equals(CLOSE_METHOD)) {
            close();
            return null;
        } else if (method.equals(SHUTDOWN_METHOD)) {
            shutdown();
            return null;
        } else if (method.equals(IS_OPEN_METHOD)) {
            return Boolean.valueOf(isOpen());
        } else if (method.equals(IS_STALE_METHOD)) {
            return Boolean.valueOf(isStale());
        } else {
            final NHttpClientConnection conn = getConnection();
            if (conn == null) {
                if (method.getDeclaringClass().equals(IOControl.class)) {
                    // Ignore IOControl operations on closed connections
                    return null;
                } else {
                    throw new ConnectionShutdownException();
                }
            }
            try {
                return method.invoke(conn, args);
            } catch (final InvocationTargetException ex) {
                final Throwable cause = ex.getCause();
                if (cause != null) {
                    throw cause;
                } else {
                    throw ex;
                }
            }
        }
    }

    public static NHttpClientConnection newProxy(
            final CPoolEntry poolEntry) {
        return (NHttpClientConnection) Proxy.newProxyInstance(
                CPoolProxy.class.getClassLoader(),
                new Class<?>[] { ManagedNHttpClientConnection.class },
                new CPoolProxy(poolEntry));
    }

    private static CPoolProxy getHandler(
            final NHttpClientConnection proxy) {
        final InvocationHandler handler = Proxy.getInvocationHandler(proxy);
        Asserts.check(CPoolProxy.class.isInstance(handler),
                "Unexpected proxy handler class: %s", handler.getClass());
        return CPoolProxy.class.cast(handler);
    }

    public static CPoolEntry getPoolEntry(final NHttpClientConnection proxy) {
        final CPoolEntry entry = getHandler(proxy).getPoolEntry();
        if (entry == null) {
            throw new ConnectionShutdownException();
        }
        return entry;
    }

    public static CPoolEntry detach(final NHttpClientConnection proxy) {
        return getHandler(proxy).detach();
    }

}
