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
package org.apache.http.nio.conn;

import javax.net.ssl.SSLSession;

import org.apache.http.HttpInetConnection;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.reactor.IOSession;

/**
 * Represents a managed connection whose state and life cycle is managed by
 * a connection manager. This interface extends {@link NHttpClientConnection}
 * with methods to bind the connection to an arbitrary {@link IOSession} and
 * to obtain SSL session details.
 *
 * @since 4.0
 */
public interface ManagedNHttpClientConnection extends NHttpClientConnection, HttpInetConnection {

    /**
     * Returns connection ID which is expected to be unique
     * for the life span of the connection manager.
     */
    String getId();

    /**
     * Binds connection to the given I/O session.
     */
    void bind(IOSession iosession);

    /**
     * Returns the underlying I/O session.
     */
    IOSession getIOSession();

    /**
     * Obtains the SSL session of the underlying connection, if any.
     *
     * @return  the underlying SSL session if available,
     *          {@code null} otherwise
     */
    SSLSession getSSLSession();

}
