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
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.UnsupportedCharsetException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.AbstractAsyncResponseConsumer;
import org.apache.http.protocol.HTTP;

public abstract class AsyncCharConsumer<T> extends AbstractAsyncResponseConsumer<T> {

    private final int bufSize;
    private ContentType contentType;
    private Charset charset;
    private CharsetDecoder chardecoder;
    private ByteBuffer bbuf;
    private CharBuffer cbuf;

    public AsyncCharConsumer(int bufSize) {
        super();
        this.bufSize = bufSize;
    }

    public AsyncCharConsumer() {
        this(8 * 1024);
    }

    protected abstract void onCharReceived(
            final CharBuffer buf, final IOControl ioctrl) throws IOException;

    @Override
    public synchronized void responseReceived(
            final HttpResponse response) throws IOException, HttpException {
        HttpEntity entity = response.getEntity();
        this.contentType = ContentType.getOrDefault(entity);
        super.responseReceived(response);
    }

    @Override
    protected void onContentReceived(
            final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
        if (this.charset == null) {
            try {
                String cs = this.contentType.getCharset();
                if (cs == null) {
                    cs = HTTP.DEFAULT_CONTENT_CHARSET;
                }
                this.charset = Charset.forName(cs);
            } catch (UnsupportedCharsetException ex) {
                throw new UnsupportedEncodingException(this.contentType.getCharset());
            }
            this.chardecoder = this.charset.newDecoder();
            this.bbuf = ByteBuffer.allocate(this.bufSize);
            this.cbuf = CharBuffer.allocate(this.bufSize);
        }
        for (;;) {
            int bytesRead = decoder.read(this.bbuf);
            if (bytesRead <= 0) {
                break;
            }
            this.bbuf.flip();
            boolean completed = decoder.isCompleted();
            CoderResult result = this.chardecoder.decode(this.bbuf, this.cbuf, completed);
            handleDecodingResult(result, ioctrl);
            this.bbuf.compact();
            if (completed) {
                result = this.chardecoder.flush(this.cbuf);
                handleDecodingResult(result, ioctrl);
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
        this.charset = null;
        this.chardecoder = null;
        this.bbuf = null;
        this.cbuf = null;
    }

}
