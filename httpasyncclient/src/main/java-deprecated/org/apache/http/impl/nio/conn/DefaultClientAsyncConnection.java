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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
import org.apache.http.impl.nio.DefaultNHttpClientConnection;
import org.apache.http.nio.conn.ClientAsyncConnection;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.util.ByteBufferAllocator;
import org.apache.http.params.HttpParams;

@Deprecated
public class DefaultClientAsyncConnection
                    extends DefaultNHttpClientConnection implements ClientAsyncConnection {

    private final Log headerlog = LogFactory.getLog("org.apache.http.headers");
    private final Log wirelog   = LogFactory.getLog("org.apache.http.wire");
    private final Log log;

    private final String id;
    private IOSession original;

    public DefaultClientAsyncConnection(
            final String id,
            final IOSession iosession,
            final HttpResponseFactory responseFactory,
            final ByteBufferAllocator allocator,
            final HttpParams params) {
        super(iosession, responseFactory, allocator, params);
        this.id = id;
        this.original = iosession;
        this.log = LogFactory.getLog(iosession.getClass());
        if (this.log.isDebugEnabled() || this.wirelog.isDebugEnabled()) {
            bind(new LoggingIOSession(iosession, this.id, this.log, this.wirelog));
        }
    }

    @Override
    public void upgrade(final IOSession iosession) {
        this.original = iosession;
        if (this.log.isDebugEnabled() || this.wirelog.isDebugEnabled()) {
            this.log.debug(this.id + " Upgrade session " + iosession);
            bind(new LoggingIOSession(iosession, this.id, this.headerlog, this.wirelog));
        } else {
            bind(iosession);
        }
    }

    @Override
    public IOSession getIOSession() {
        return this.original;
    }

    public String getId() {
        return this.id;
    }

    @Override
    protected void onResponseReceived(final HttpResponse response) {
        if (response != null && this.headerlog.isDebugEnabled()) {
            this.headerlog.debug(this.id + " << " + response.getStatusLine().toString());
            final Header[] headers = response.getAllHeaders();
            for (final Header header : headers) {
                this.headerlog.debug(this.id + " << " + header.toString());
            }
        }
    }

    @Override
    protected void onRequestSubmitted(final HttpRequest request) {
        if (request != null && this.headerlog.isDebugEnabled()) {
            this.headerlog.debug(this.id + " >> " + request.getRequestLine().toString());
            final Header[] headers = request.getAllHeaders();
            for (final Header header : headers) {
                this.headerlog.debug(this.id + " >> " + header.toString());
            }
        }
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        buf.append(this.id);
        buf.append(" [");
        switch (this.status) {
        case ACTIVE:
            buf.append("ACTIVE");
            if (this.inbuf.hasData()) {
                buf.append("(").append(this.inbuf.length()).append(")");
            }
            break;
        case CLOSING:
            buf.append("CLOSING");
            break;
        case CLOSED:
            buf.append("CLOSED");
            break;
        }
        buf.append("]");
        return buf.toString();
    }

}
