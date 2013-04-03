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

import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;

import javax.net.ssl.SSLSession;

import org.apache.commons.logging.Log;
import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.config.MessageConstraints;
import org.apache.http.entity.ContentLengthStrategy;
import org.apache.http.impl.nio.DefaultNHttpClientConnection;
import org.apache.http.nio.NHttpMessageParserFactory;
import org.apache.http.nio.NHttpMessageWriterFactory;
import org.apache.http.nio.conn.ManagedNHttpClientConnection;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.ssl.SSLIOSession;
import org.apache.http.nio.util.ByteBufferAllocator;
import org.apache.http.util.Args;
import org.apache.http.util.Asserts;

class ManagedNHttpClientConnectionImpl
                    extends DefaultNHttpClientConnection implements ManagedNHttpClientConnection {

    private final Log headerlog;
    private final Log wirelog;
    private final Log log;

    private final String id;
    private IOSession original;

    public ManagedNHttpClientConnectionImpl(
            final String id,
            final Log log,
            final Log headerlog,
            final Log wirelog,
            final IOSession iosession,
            final int buffersize,
            final int fragmentSizeHint,
            final ByteBufferAllocator allocator,
            final CharsetDecoder chardecoder,
            final CharsetEncoder charencoder,
            final MessageConstraints constraints,
            final ContentLengthStrategy incomingContentStrategy,
            final ContentLengthStrategy outgoingContentStrategy,
            final NHttpMessageWriterFactory<HttpRequest> requestWriterFactory,
            final NHttpMessageParserFactory<HttpResponse> responseParserFactory) {
        super(iosession, buffersize, fragmentSizeHint, allocator, chardecoder, charencoder, constraints,
                incomingContentStrategy, outgoingContentStrategy,
                requestWriterFactory, responseParserFactory);
        this.id = id;
        this.log = log;
        this.headerlog = headerlog;
        this.wirelog = wirelog;
        this.original = iosession;
        if (this.log.isDebugEnabled() || this.wirelog.isDebugEnabled()) {
            super.bind(new LoggingIOSession(iosession, this.id, this.log, this.wirelog));
        }
    }

    @Override
    public void bind(final IOSession iosession) {
        Args.notNull(iosession, "I/O session");
        Asserts.check(!iosession.isClosed(), "I/O session is closed");
        this.status = ACTIVE;
        this.original = iosession;
        if (this.log.isDebugEnabled() || this.wirelog.isDebugEnabled()) {
            this.log.debug(this.id + " Upgrade session " + iosession);
            super.bind(new LoggingIOSession(iosession, this.id, this.log, this.wirelog));
        } else {
            super.bind(iosession);
        }
    }

    public IOSession getIOSession() {
        return this.original;
    }

    public SSLSession getSSLSession() {
        if (this.original instanceof SSLIOSession) {
            return ((SSLIOSession) this.original).getSSLSession();
        } else {
            return null;
        }
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
