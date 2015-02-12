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

import org.apache.commons.logging.Log;
import org.apache.http.HttpException;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.NHttpClientEventHandler;

class InternalRequestExecutor implements NHttpClientEventHandler {

    private final Log log;
    private final NHttpClientEventHandler handler;

    public InternalRequestExecutor(final Log log, final NHttpClientEventHandler handler) {
        this.log = log;
        this.handler = handler;
    }

    @Override
    public void connected(
            final NHttpClientConnection conn,
            final Object attachment) throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + ": Connected");
        }
        this.handler.connected(conn, attachment);
    }

    @Override
    public void closed(final NHttpClientConnection conn) {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + ": Disconnected");
        }
        this.handler.closed(conn);
    }

    @Override
    public void requestReady(
            final NHttpClientConnection conn) throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Request ready");
        }
        this.handler.requestReady(conn);
    }

    @Override
    public void inputReady(
            final NHttpClientConnection conn,
            final ContentDecoder decoder) throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Input ready");
        }
        this.handler.inputReady(conn, decoder);
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " " + decoder);
        }
    }

    @Override
    public void outputReady(
            final NHttpClientConnection conn,
            final ContentEncoder encoder) throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Output ready");
        }
        this.handler.outputReady(conn, encoder);
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " " + encoder);
        }
    }

    @Override
    public void responseReceived(
            final NHttpClientConnection conn) throws HttpException, IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Response received");
        }
        this.handler.responseReceived(conn);
    }

    @Override
    public void timeout(final NHttpClientConnection conn) throws HttpException, IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Timeout");
        }
        this.handler.timeout(conn);
    }

    @Override
    public void exception(final NHttpClientConnection conn, final Exception ex) {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Exception", ex);
        }
        this.handler.exception(conn, ex);
    }

    @Override
    public void endOfInput(final NHttpClientConnection conn) throws IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " End of input");
        }
        this.handler.endOfInput(conn);
    }

}
