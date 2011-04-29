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

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;

import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.ContentEncoderChannel;
import org.apache.http.nio.FileContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.HttpAsyncRequestProducer;

abstract class BaseZeroCopyRequestProducer implements HttpAsyncRequestProducer, Closeable {

    private final URI requestURI;
    private final File file;
    private final String contentType;

    private FileChannel fileChannel;
    private long idx = -1;

    protected BaseZeroCopyRequestProducer(
            final URI requestURI, final File file, final String contentType) {
        super();
        if (requestURI == null) {
            throw new IllegalArgumentException("Request URI may not be null");
        }
        if (file == null) {
            throw new IllegalArgumentException("Source file may not be null");
        }
        this.requestURI = requestURI;
        this.file = file;
        this.contentType = contentType;
    }

    protected abstract HttpEntityEnclosingRequest createRequest(final URI requestURI, final HttpEntity entity);

    public HttpRequest generateRequest() throws IOException, HttpException {
        BasicHttpEntity entity = new BasicHttpEntity();
        entity.setChunked(false);
        entity.setContentLength(this.file.length());
        entity.setContentType(this.contentType);
        return createRequest(this.requestURI, entity);
    }

    public synchronized HttpHost getTarget() {
        return URIUtils.extractHost(this.requestURI);
    }

    public synchronized void produceContent(
            final ContentEncoder encoder, final IOControl ioctrl) throws IOException {
        if (this.fileChannel == null) {
            FileInputStream in = new FileInputStream(this.file);
            this.fileChannel = in.getChannel();
            this.idx = 0;
        }
        long transferred;
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
            this.fileChannel.close();
            this.fileChannel = null;
        }
    }

    public synchronized boolean isRepeatable() {
        return true;
    }

    public synchronized void resetRequest() {
        try {
            close();
        } catch (IOException ignore) {
        }
    }

    public synchronized void close() throws IOException {
        if (this.fileChannel != null) {
            this.fileChannel.close();
            this.fileChannel = null;
        }
    }

}
