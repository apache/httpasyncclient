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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.channels.FileChannel;

import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.ContentEncoderChannel;
import org.apache.http.nio.FileContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Args;

abstract class BaseZeroCopyRequestProducer implements HttpAsyncRequestProducer {

    private final URI requestURI;
    private final File file;
    private final RandomAccessFile accessfile;
    private final ContentType contentType;

    private FileChannel fileChannel;
    private long idx = -1;

    protected BaseZeroCopyRequestProducer(
            final URI requestURI,
            final File file, final ContentType contentType) throws FileNotFoundException {
        super();
        Args.notNull(requestURI, "Request URI");
        Args.notNull(file, "Source file");
        this.requestURI = requestURI;
        this.file = file;
        this.accessfile = new RandomAccessFile(file, "r");
        this.contentType = contentType;
    }

    private void closeChannel() throws IOException {
        if (this.fileChannel != null) {
            this.fileChannel.close();
            this.fileChannel = null;
        }
    }

    protected abstract HttpEntityEnclosingRequest createRequest(final URI requestURI, final HttpEntity entity);

    @Override
    public HttpRequest generateRequest() throws IOException, HttpException {
        final BasicHttpEntity entity = new BasicHttpEntity();
        entity.setChunked(false);
        entity.setContentLength(this.file.length());
        if (this.contentType != null) {
            entity.setContentType(this.contentType.toString());
        }
        return createRequest(this.requestURI, entity);
    }

    @Override
    public synchronized HttpHost getTarget() {
        return URIUtils.extractHost(this.requestURI);
    }

    @Override
    public synchronized void produceContent(
            final ContentEncoder encoder, final IOControl ioControl) throws IOException {
        if (this.fileChannel == null) {
            this.fileChannel = this.accessfile.getChannel();
            this.idx = 0;
        }
        final long transferred;
        if (encoder instanceof FileContentEncoder) {
            transferred = ((FileContentEncoder)encoder).transfer(
                    this.fileChannel, this.idx, Integer.MAX_VALUE);
        } else {
            transferred = this.fileChannel.transferTo(
                    this.idx, Integer.MAX_VALUE, new ContentEncoderChannel(encoder));
        }
        if (transferred > 0) {
            this.idx += transferred;
        }

        if (this.idx >= this.fileChannel.size()) {
            encoder.complete();
            closeChannel();
        }
    }

    @Override
    public void requestCompleted(final HttpContext context) {
    }

    @Override
    public void failed(final Exception ex) {
    }

    @Override
    public boolean isRepeatable() {
        return true;
    }

    @Override
    public synchronized void resetRequest() throws IOException {
        closeChannel();
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            this.accessfile.close();
        } catch (final IOException ignore) {
        }
    }

}
