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
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.nio.codecs.DefaultHttpResponseParserFactory;
import org.apache.http.message.BasicLineParser;
import org.apache.http.nio.NHttpMessageParserFactory;
import org.apache.http.nio.conn.ClientAsyncConnection;
import org.apache.http.nio.conn.ClientAsyncConnectionFactory;
import org.apache.http.nio.conn.ManagedNHttpClientConnection;
import org.apache.http.nio.conn.NHttpConnectionFactory;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.util.ByteBufferAllocator;
import org.apache.http.nio.util.HeapByteBufferAllocator;
import org.apache.http.params.HttpParams;

@Deprecated
public class DefaultClientAsyncConnectionFactory
    implements ClientAsyncConnectionFactory, NHttpConnectionFactory<ManagedNHttpClientConnection> {

    private final Log headerlog = LogFactory.getLog("org.apache.http.headers");
    private final Log wirelog = LogFactory.getLog("org.apache.http.wire");
    private final Log log = LogFactory.getLog(ManagedNHttpClientConnectionImpl.class);

    public static final DefaultClientAsyncConnectionFactory INSTANCE = new DefaultClientAsyncConnectionFactory(null, null);

    private static AtomicLong COUNTER = new AtomicLong();

    private final HttpResponseFactory responseFactory;
    private final NHttpMessageParserFactory<HttpResponse> responseParserFactory;
    private final ByteBufferAllocator allocator;

    public DefaultClientAsyncConnectionFactory(
            final NHttpMessageParserFactory<HttpResponse> responseParserFactory,
            final ByteBufferAllocator allocator) {
        super();
        this.responseFactory = createHttpResponseFactory();
        this.responseParserFactory = responseParserFactory != null ? responseParserFactory :
            DefaultHttpResponseParserFactory.INSTANCE;
        this.allocator = allocator != null ? allocator : HeapByteBufferAllocator.INSTANCE;
    }

    public DefaultClientAsyncConnectionFactory() {
        super();
        this.responseFactory = createHttpResponseFactory();
        this.responseParserFactory = new DefaultHttpResponseParserFactory(
            BasicLineParser.INSTANCE, this.responseFactory);
        this.allocator = createByteBufferAllocator();
    }

    @Deprecated
    public ClientAsyncConnection create(
            final String id,
            final IOSession iosession,
            final HttpParams params) {
        return new DefaultClientAsyncConnection(
                id, iosession, this.responseFactory, this.allocator, params);
    }

    @Deprecated
    protected ByteBufferAllocator createByteBufferAllocator() {
        return HeapByteBufferAllocator.INSTANCE;
    }

    @Deprecated
    protected HttpResponseFactory createHttpResponseFactory() {
        return DefaultHttpResponseFactory.INSTANCE;
    }

    public ManagedNHttpClientConnection create(
            final IOSession iosession, final ConnectionConfig config) {
        final String id = "http-outgoing-" + Long.toString(COUNTER.getAndIncrement());
        CharsetDecoder chardecoder = null;
        CharsetEncoder charencoder = null;
        final Charset charset = config.getCharset();
        final CodingErrorAction malformedInputAction = config.getMalformedInputAction() != null ?
                config.getMalformedInputAction() : CodingErrorAction.REPORT;
        final CodingErrorAction unmappableInputAction = config.getUnmappableInputAction() != null ?
                config.getUnmappableInputAction() : CodingErrorAction.REPORT;
        if (charset != null) {
            chardecoder = charset.newDecoder();
            chardecoder.onMalformedInput(malformedInputAction);
            chardecoder.onUnmappableCharacter(unmappableInputAction);
            charencoder = charset.newEncoder();
            charencoder.onMalformedInput(malformedInputAction);
            charencoder.onUnmappableCharacter(unmappableInputAction);
        }
        final ManagedNHttpClientConnection conn = new ManagedNHttpClientConnectionImpl(
                id,
                this.log,
                this.headerlog,
                this.wirelog,
                iosession,
                config.getBufferSize(),
                config.getFragmentSizeHint(),
                this.allocator,
                chardecoder, charencoder, config.getMessageConstraints(),
                null, null, null,
                this.responseParserFactory);
        iosession.setAttribute(IOEventDispatch.CONNECTION_KEY, conn);
        return conn;
    }

}
