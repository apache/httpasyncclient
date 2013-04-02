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
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpException;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.protocol.HttpAsyncRequestExecutor;

class LoggingAsyncRequestExecutor extends HttpAsyncRequestExecutor {

    private final Log log = LogFactory.getLog(HttpAsyncRequestExecutor.class);

    public LoggingAsyncRequestExecutor() {
        super();
    }

    @Override
    protected void log(final Exception ex) {
        this.log.debug(ex.getMessage(), ex);
    }

    @Override
    public void connected(
            final NHttpClientConnection conn,
            final Object attachment) throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + ": Connected");
        }
        super.connected(conn, attachment);
    }

    @Override
    public void closed(final NHttpClientConnection conn) {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + ": Disconnected");
        }
        super.closed(conn);
    }

    @Override
    public void requestReady(
            final NHttpClientConnection conn) throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Request ready");
        }
        super.requestReady(conn);
    }

    @Override
    public void inputReady(
            final NHttpClientConnection conn,
            final ContentDecoder decoder) throws IOException, HttpException {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Input ready");
        }
        super.inputReady(conn, decoder);
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
        super.outputReady(conn, encoder);
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
        super.responseReceived(conn);
    }

    @Override
    public void timeout(final NHttpClientConnection conn) throws IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Timeout");
        }
        super.timeout(conn);
    }

    @Override
    public void endOfInput(final NHttpClientConnection conn) throws IOException {
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " End of input");
        }
        super.endOfInput(conn);
    }

}
