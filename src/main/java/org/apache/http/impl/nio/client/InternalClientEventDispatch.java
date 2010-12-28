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
package org.apache.http.impl.nio.client;

import java.io.IOException;

import org.apache.http.impl.nio.reactor.SSLIOSession;
import org.apache.http.nio.NHttpClientHandler;
import org.apache.http.nio.conn.OperatedClientConnection;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.protocol.ExecutionContext;

class InternalClientEventDispatch implements IOEventDispatch {

    private final NHttpClientHandler handler;

    InternalClientEventDispatch(final NHttpClientHandler handler) {
        super();
        this.handler = handler;
    }

    private OperatedClientConnection getConnection(final IOSession session) {
        return (OperatedClientConnection) session.getAttribute(ExecutionContext.HTTP_CONNECTION);
    }

    private void assertValid(final OperatedClientConnection conn) {
        if (conn == null) {
            throw new IllegalStateException("HTTP connection is null");
        }
    }

    public void connected(final IOSession session) {
        OperatedClientConnection conn = getConnection(session);
        assertValid(conn);
        Object attachment = session.getAttribute(IOSession.ATTACHMENT_KEY);
        this.handler.connected(conn, attachment);
    }

    public void disconnected(final IOSession session) {
        OperatedClientConnection conn = getConnection(session);
        if (conn != null) {
            this.handler.closed(conn);
        }
    }

    public void inputReady(final IOSession session) {
        OperatedClientConnection conn = getConnection(session);
        assertValid(conn);
        SSLIOSession ssliosession = conn.getSSLIOSession();
        if (ssliosession == null) {
            conn.consumeInput(this.handler);
        } else {
            try {
                if (ssliosession.isAppInputReady()) {
                    conn.consumeInput(this.handler);
                }
                ssliosession.inboundTransport();
            } catch (IOException ex) {
                this.handler.exception(conn, ex);
                ssliosession.shutdown();
            }
        }
    }

    public void outputReady(final IOSession session) {
        OperatedClientConnection conn = getConnection(session);
        assertValid(conn);
        SSLIOSession ssliosession = conn.getSSLIOSession();
        if (ssliosession == null) {
            conn.produceOutput(this.handler);
        } else {
            try {
                if (ssliosession.isAppOutputReady()) {
                    conn.produceOutput(this.handler);
                }
                ssliosession.outboundTransport();
            } catch (IOException ex) {
                this.handler.exception(conn, ex);
                ssliosession.shutdown();
            }
        }
    }

    public void timeout(IOSession session) {
        OperatedClientConnection conn = getConnection(session);
        if (conn != null) {
            SSLIOSession ssliosession = conn.getSSLIOSession();
            if (ssliosession == null) {
                this.handler.timeout(conn);
            } else {
                this.handler.timeout(conn);
                synchronized (ssliosession) {
                    if (ssliosession.isOutboundDone() && !ssliosession.isInboundDone()) {
                        // The session failed to terminate cleanly
                        ssliosession.shutdown();
                    }
                }
            }
        }
    }

}