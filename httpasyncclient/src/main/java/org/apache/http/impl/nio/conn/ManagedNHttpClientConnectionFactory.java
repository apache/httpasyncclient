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

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.impl.nio.codecs.DefaultHttpRequestWriterFactory;
import org.apache.http.impl.nio.codecs.DefaultHttpResponseParserFactory;
import org.apache.http.nio.NHttpMessageParserFactory;
import org.apache.http.nio.NHttpMessageWriterFactory;
import org.apache.http.nio.conn.ManagedNHttpClientConnection;
import org.apache.http.nio.conn.NHttpConnectionFactory;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.util.ByteBufferAllocator;
import org.apache.http.nio.util.HeapByteBufferAllocator;

/**
 * Default factory for {@link ManagedNHttpClientConnection} instances.
 *
 * @since 4.0
 */
public class ManagedNHttpClientConnectionFactory implements NHttpConnectionFactory<ManagedNHttpClientConnection> {

    private final Log headerLog = LogFactory.getLog("org.apache.http.headers");
    private final Log wireLog = LogFactory.getLog("org.apache.http.wire");
    private final Log log = LogFactory.getLog(ManagedNHttpClientConnectionImpl.class);

    private static final AtomicLong COUNTER = new AtomicLong();

    public static final ManagedNHttpClientConnectionFactory INSTANCE = new ManagedNHttpClientConnectionFactory();

    private final ByteBufferAllocator allocator;
    private final NHttpMessageWriterFactory<HttpRequest> requestWriterFactory;
    private final NHttpMessageParserFactory<HttpResponse> responseParserFactory;

    public ManagedNHttpClientConnectionFactory(
            final NHttpMessageWriterFactory<HttpRequest> requestWriterFactory,
            final NHttpMessageParserFactory<HttpResponse> responseParserFactory,
            final ByteBufferAllocator allocator) {
        super();
        this.requestWriterFactory = requestWriterFactory != null ? requestWriterFactory :
            DefaultHttpRequestWriterFactory.INSTANCE;
        this.responseParserFactory = responseParserFactory != null ? responseParserFactory :
            DefaultHttpResponseParserFactory.INSTANCE;
        this.allocator = allocator != null ? allocator : HeapByteBufferAllocator.INSTANCE;
    }

    public ManagedNHttpClientConnectionFactory() {
        this(null, null, null);
    }

    @Override
    public ManagedNHttpClientConnection create(
            final IOSession ioSession, final ConnectionConfig config) {
        final String id = "http-outgoing-" + Long.toString(COUNTER.getAndIncrement());
        CharsetDecoder charDecoder = null;
        CharsetEncoder charEncoder = null;
        final Charset charset = config.getCharset();
        final CodingErrorAction malformedInputAction = config.getMalformedInputAction() != null ?
                config.getMalformedInputAction() : CodingErrorAction.REPORT;
        final CodingErrorAction unmappableInputAction = config.getUnmappableInputAction() != null ?
                config.getUnmappableInputAction() : CodingErrorAction.REPORT;
        if (charset != null) {
            charDecoder = charset.newDecoder();
            charDecoder.onMalformedInput(malformedInputAction);
            charDecoder.onUnmappableCharacter(unmappableInputAction);
            charEncoder = charset.newEncoder();
            charEncoder.onMalformedInput(malformedInputAction);
            charEncoder.onUnmappableCharacter(unmappableInputAction);
        }
        final ManagedNHttpClientConnection conn = new ManagedNHttpClientConnectionImpl(
                id,
                this.log,
                this.headerLog,
                this.wireLog,
                ioSession,
                config.getBufferSize(),
                config.getFragmentSizeHint(),
                this.allocator,
                charDecoder,
                charEncoder,
                config.getMessageConstraints(),
                null,
                null,
                this.requestWriterFactory,
                this.responseParserFactory);
        ioSession.setAttribute(IOEventDispatch.CONNECTION_KEY, conn);
        return conn;
    }

}
