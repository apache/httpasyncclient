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

import org.apache.http.nio.NHttpClientHandler;
import org.apache.http.nio.NHttpClientIOTarget;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.protocol.ExecutionContext;

class InternalClientEventDispatch implements IOEventDispatch {

    private final NHttpClientHandler handler;

    InternalClientEventDispatch(final NHttpClientHandler handler) {
        super();
        this.handler = handler;
    }

    private NHttpClientIOTarget getConnection(final IOSession session) {
        return (NHttpClientIOTarget) session.getAttribute(ExecutionContext.HTTP_CONNECTION);
    }

    private void assertValid(final NHttpClientIOTarget conn) {
        if (conn == null) {
            throw new IllegalStateException("HTTP connection is null");
        }
    }

    public void connected(final IOSession session) {
        NHttpClientIOTarget conn = getConnection(session);
        assertValid(conn);
        Object attachment = session.getAttribute(IOSession.ATTACHMENT_KEY);
        this.handler.connected(conn, attachment);
    }

    public void disconnected(final IOSession session) {
        NHttpClientIOTarget conn = getConnection(session);
        if (conn != null) {
            this.handler.closed(conn);
        }
    }

    public void inputReady(final IOSession session) {
        NHttpClientIOTarget conn = getConnection(session);
        assertValid(conn);
        conn.consumeInput(this.handler);
    }

    public void outputReady(final IOSession session) {
        NHttpClientIOTarget conn = getConnection(session);
        assertValid(conn);
        conn.produceOutput(this.handler);
    }

    public void timeout(IOSession session) {
        NHttpClientIOTarget conn = getConnection(session);
        if (conn != null) {
            this.handler.timeout(conn);
        }
    }

}