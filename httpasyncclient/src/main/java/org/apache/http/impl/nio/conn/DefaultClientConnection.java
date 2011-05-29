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
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
import org.apache.http.impl.nio.DefaultNHttpClientConnection;
import org.apache.http.impl.nio.reactor.SSLIOSession;
import org.apache.http.nio.NHttpMessageParser;
import org.apache.http.nio.NHttpMessageWriter;
import org.apache.http.nio.conn.OperatedClientConnection;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.SessionInputBuffer;
import org.apache.http.nio.reactor.SessionOutputBuffer;
import org.apache.http.nio.util.ByteBufferAllocator;
import org.apache.http.params.HttpParams;

public class DefaultClientConnection
                        extends DefaultNHttpClientConnection implements OperatedClientConnection {

    private final Log headerlog = LogFactory.getLog("org.apache.http.headers");
    private final Log wirelog   = LogFactory.getLog("org.apache.http.wire");
    private final Log log;

    private static final AtomicLong COUNT = new AtomicLong();

    private String id;
    private SSLIOSession ssliosession;

    public DefaultClientConnection(
            final IOSession iosession,
            final HttpResponseFactory responseFactory,
            final ByteBufferAllocator allocator,
            final HttpParams params) {
        super(iosession, responseFactory, allocator, params);
        this.id = "http-outgoing-" + COUNT.incrementAndGet();
        this.log = LogFactory.getLog(iosession.getClass());
        if (this.log.isDebugEnabled() || this.wirelog.isDebugEnabled()) {
            this.session = new LoggingIOSession(iosession, this.id, this.log, this.wirelog);
        }
        if (iosession instanceof SSLIOSession) {
            this.ssliosession = (SSLIOSession) iosession;
        } else {
            this.ssliosession = null;
        }
    }

    public void upgrade(final IOSession iosession) {
        this.session.setBufferStatus(null);
        if (this.log.isDebugEnabled() || this.wirelog.isDebugEnabled()) {
            this.log.debug(this.id + " Upgrade session " + iosession);
            this.session = new LoggingIOSession(iosession, this.id, this.headerlog, this.wirelog);
        } else {
            this.session = iosession;
        }
        this.session.setBufferStatus(this);
        if (iosession instanceof SSLIOSession) {
            this.ssliosession = (SSLIOSession) iosession;
        } else {
            this.ssliosession = null;
        }
    }

    public SSLIOSession getSSLIOSession() {
        return this.ssliosession;
    }

    @Override
    protected NHttpMessageWriter<HttpRequest> createRequestWriter(
            final SessionOutputBuffer buffer,
            final HttpParams params) {
        return new LoggingNHttpMessageWriter(
                super.createRequestWriter(buffer, params));
    }

    @Override
    protected NHttpMessageParser<HttpResponse> createResponseParser(
            final SessionInputBuffer buffer,
            final HttpResponseFactory responseFactory,
            final HttpParams params) {
        return new LoggingNHttpMessageParser(
                super.createResponseParser(buffer, responseFactory, params));
    }

    @Override
    public String toString() {
        return this.id;
    }

    class LoggingNHttpMessageWriter implements NHttpMessageWriter<HttpRequest> {

        private final NHttpMessageWriter<HttpRequest> writer;

        public LoggingNHttpMessageWriter(final NHttpMessageWriter<HttpRequest> writer) {
            super();
            this.writer = writer;
        }

        public void reset() {
            this.writer.reset();
        }

        public void write(final HttpRequest request) throws IOException, HttpException {
            if (request != null && headerlog.isDebugEnabled()) {
                headerlog.debug(id + " >> " + request.getRequestLine().toString());
                Header[] headers = request.getAllHeaders();
                for (int i = 0; i < headers.length; i++) {
                    headerlog.debug(id + " >> " + headers[i].toString());
                }
            }
            this.writer.write(request);
        }

    }

    class LoggingNHttpMessageParser implements NHttpMessageParser<HttpResponse> {

        private final NHttpMessageParser<HttpResponse> parser;

        public LoggingNHttpMessageParser(final NHttpMessageParser<HttpResponse> parser) {
            super();
            this.parser = parser;
        }

        public void reset() {
            this.parser.reset();
        }

        public int fillBuffer(final ReadableByteChannel channel) throws IOException {
            return this.parser.fillBuffer(channel);
        }

        public HttpResponse parse() throws IOException, HttpException {
            HttpResponse response = this.parser.parse();
            if (headerlog.isDebugEnabled()) {
                if (response != null && headerlog.isDebugEnabled()) {
                    headerlog.debug(id + " << " + response.getStatusLine().toString());
                    Header[] headers = response.getAllHeaders();
                    for (int i = 0; i < headers.length; i++) {
                        headerlog.debug(id + " << " + headers[i].toString());
                    }
                }
            }
            return response;
        }

    }

}
