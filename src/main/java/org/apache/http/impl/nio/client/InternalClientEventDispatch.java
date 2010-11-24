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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.impl.nio.DefaultClientIOEventDispatch;
import org.apache.http.impl.nio.conn.LoggingIOSession;
import org.apache.http.impl.nio.conn.LoggingNHttpClientConnection;
import org.apache.http.nio.NHttpClientHandler;
import org.apache.http.nio.NHttpClientIOTarget;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.params.HttpParams;

class InternalClientEventDispatch extends DefaultClientIOEventDispatch {

    private static final String HEADERS = "org.apache.http.headers";
    private static final String WIRE = "org.apache.http.wire";

    private Log log;

    InternalClientEventDispatch(
            final NHttpClientHandler handler,
            final HttpParams params) {
        super(handler, params);
        this.log = LogFactory.getLog(getClass());
    }

    @Override
    protected NHttpClientIOTarget createConnection(IOSession session) {
        Log log = LogFactory.getLog(session.getClass());
        Log wirelog = LogFactory.getLog(WIRE);
        Log headerlog = LogFactory.getLog(HEADERS);
        if (log.isDebugEnabled() || wirelog.isDebugEnabled()) {
            session = new LoggingIOSession(session, log, wirelog);
        }
        if (headerlog.isDebugEnabled()) {
            return new LoggingNHttpClientConnection(
                    headerlog,
                    session,
                    createHttpResponseFactory(),
                    this.allocator,
                    this.params);
        } else {
            return super.createConnection(session);
        }
    }

    @Override
    public void connected(final IOSession session) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("Session connected: " + session);
        }
        super.connected(session);
    }

    @Override
    public void disconnected(final IOSession session) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("Session disconnected: " + session);
        }
        super.disconnected(session);
    }

    @Override
    public void inputReady(final IOSession session) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("Session input ready: " + session);
        }
        super.inputReady(session);
    }

    @Override
    public void outputReady(final IOSession session) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("Session output ready: " + session);
        }
        super.outputReady(session);
    }

    @Override
    public void timeout(IOSession session) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("Session timed out: " + session);
        }
        super.timeout(session);
    }

}