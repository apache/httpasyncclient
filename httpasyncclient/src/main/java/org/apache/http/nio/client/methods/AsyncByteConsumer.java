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

import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.AbstractAsyncResponseConsumer;

public abstract class AsyncByteConsumer<T> extends AbstractAsyncResponseConsumer<T> {

    private final int bufSize;
    private ByteBuffer bbuf;

    public AsyncByteConsumer(int bufSize) {
        super();
        this.bufSize = bufSize;
    }

    public AsyncByteConsumer() {
        this(8 * 1024);
    }

    protected abstract void onByteReceived(
            ByteBuffer buf, IOControl ioctrl) throws IOException;

    @Override
    protected final void onEntityEnclosed(
            final HttpEntity entity, final ContentType contentType) {
        this.bbuf = ByteBuffer.allocate(this.bufSize);
    }

    @Override
    protected final void onContentReceived(
            final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
        if (this.bbuf == null) {
            throw new IllegalStateException("Byte buffer is null");
        }
        for (;;) {
            int bytesRead = decoder.read(this.bbuf);
            if (bytesRead <= 0) {
                break;
            }
            this.bbuf.flip();
            onByteReceived(this.bbuf, ioctrl);
            this.bbuf.clear();
        }
    }

    @Override
    protected void releaseResources() {
        this.bbuf = null;
    }

}
