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
package org.apache.http.nio.client.methods;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.conn.ManagedNHttpClientConnection;
import org.apache.http.nio.protocol.AbstractAsyncResponseConsumer;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.Asserts;

/**
 * {@link org.apache.http.nio.protocol.HttpAsyncResponseConsumer} implementation that
 * provides convenience methods for processing of textual content entities enclosed
 * in an HTTP response.
 *
 * @since 4.0
 */
public abstract class AsyncCharConsumer<T> extends AbstractAsyncResponseConsumer<T> {

    private final int bufSize;
    private ContentType contentType;
    private CharsetDecoder chardecoder;
    private ByteBuffer bbuf;
    private CharBuffer cbuf;

    public AsyncCharConsumer(final int bufSize) {
        super();
        this.bufSize = bufSize;
    }

    public AsyncCharConsumer() {
        this(8 * 1024);
    }

    /**
     * Invoked to process a {@link CharBuffer chunk} of content.
     * The {@link IOControl} interface can be used to suspend input events
     * if the consumer is temporarily unable to consume more content.
     *
     * @param buf chunk of content.
     * @param ioctrl I/O control of the underlying connection.
     * @throws IOException in case of an I/O error
     */
    protected abstract void onCharReceived(
            CharBuffer buf, IOControl ioctrl) throws IOException;

    @Override
    protected final void onEntityEnclosed(
            final HttpEntity entity, final ContentType contentType) throws IOException {
        this.contentType = contentType != null ? contentType : ContentType.DEFAULT_TEXT;
        Charset charset = this.contentType.getCharset();
        if (charset == null) {
            charset = HTTP.DEF_CONTENT_CHARSET;
        }
        this.chardecoder = charset.newDecoder();
        this.bbuf = ByteBuffer.allocate(this.bufSize);
        this.cbuf = CharBuffer.allocate(this.bufSize);
    }

    @Override
    protected final void onContentReceived(
            final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
        Asserts.notNull(this.bbuf, "Byte buffer");

        //FIXME: IOControl needs to expose event mask in order to avoid this extreme ugliness
        final IOSession iosession;
        if (ioctrl instanceof ManagedNHttpClientConnection) {
            final ManagedNHttpClientConnection conn = (ManagedNHttpClientConnection) ioctrl;
            iosession = conn != null ? conn.getIOSession() : null;
        } else {
            iosession = null;
        }
        while (!this.isDone()) {
            final int bytesRead = decoder.read(this.bbuf);
            if (bytesRead <= 0) {
                break;
            }
            this.bbuf.flip();
            final boolean completed = decoder.isCompleted();
            CoderResult result = this.chardecoder.decode(this.bbuf, this.cbuf, completed);
            handleDecodingResult(result, ioctrl);
            this.bbuf.compact();
            if (completed) {
                result = this.chardecoder.flush(this.cbuf);
                handleDecodingResult(result, ioctrl);
                break;
            } else {
                if (iosession != null && (iosession.isClosed() ||
                        (iosession.getEventMask() & SelectionKey.OP_READ) == 0)) {
                    break;
                }
            }
        }
    }

    private void handleDecodingResult(
            final CoderResult result, final IOControl ioctrl) throws IOException {
        if (result.isError()) {
            result.throwException();
        }
        this.cbuf.flip();
        if (this.cbuf.hasRemaining()) {
            onCharReceived(this.cbuf, ioctrl);
        }
        this.cbuf.clear();
    }

    @Override
    protected void releaseResources() {
        this.chardecoder = null;
        this.bbuf = null;
        this.cbuf = null;
    }

}
