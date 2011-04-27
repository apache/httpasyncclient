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
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;

import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.HttpAsyncRequestProducer;
import org.apache.http.nio.entity.NByteArrayEntity;
import org.apache.http.nio.entity.NFileEntity;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.nio.entity.ProducingNHttpEntity;
import org.apache.http.protocol.HTTP;

abstract class BaseHttpAsyncEntityRequestProducer implements HttpAsyncRequestProducer, Closeable {

    private final URI requestURI;
    private final ProducingNHttpEntity producer;

    protected BaseHttpAsyncEntityRequestProducer(
            final URI requestURI, final String content, String mimeType, String charset) {
        super();
        if (requestURI == null) {
            throw new IllegalArgumentException("Request URI may not be null");
        }
        if (mimeType == null) {
            mimeType = HTTP.PLAIN_TEXT_TYPE;
        }
        if (charset == null) {
            charset = HTTP.DEFAULT_CONTENT_CHARSET;
        }
        this.requestURI = requestURI;
        try {
            NStringEntity entity = new NStringEntity(content, charset);
            entity.setContentType(mimeType + HTTP.CHARSET_PARAM + charset);
            this.producer = entity;
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalArgumentException("Unsupported charset: " + charset);
        }
    }

    protected BaseHttpAsyncEntityRequestProducer(
            final URI requestURI, final byte[] content, final String contentType) {
        super();
        if (requestURI == null) {
            throw new IllegalArgumentException("Request URI may not be null");
        }
        this.requestURI = requestURI;
        NByteArrayEntity entity = new NByteArrayEntity(content);
        entity.setContentType(contentType);
        this.producer = entity;
    }

    protected BaseHttpAsyncEntityRequestProducer(
            final URI requestURI, final File content, final String contentType) {
        super();
        if (requestURI == null) {
            throw new IllegalArgumentException("Request URI may not be null");
        }
        this.requestURI = requestURI;
        this.producer = new NFileEntity(content, contentType);
    }

    protected abstract HttpEntityEnclosingRequest createRequest(final URI requestURI, final HttpEntity entity);

    public HttpRequest generateRequest() throws IOException, HttpException {
        return createRequest(this.requestURI, this.producer);
    }

    public synchronized HttpHost getTarget() {
        return URIUtils.extractHost(this.requestURI);
    }

    public synchronized void produceContent(
            final ContentEncoder encoder, final IOControl ioctrl) throws IOException {
        this.producer.produceContent(encoder, ioctrl);
        if (encoder.isCompleted()) {
            this.producer.finish();
        }
    }

    public synchronized boolean isRepeatable() {
        return this.producer.isRepeatable();
    }

    public synchronized void resetRequest() {
        try {
            this.producer.finish();
        } catch (IOException ignore) {
        }
    }

    public synchronized void close() throws IOException {
        this.producer.finish();
    }

}
