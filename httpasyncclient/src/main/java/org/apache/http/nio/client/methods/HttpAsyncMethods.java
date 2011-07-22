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
import java.io.UnsupportedEncodingException;
import java.net.URI;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpTrace;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.nio.client.HttpAsyncRequestProducer;
import org.apache.http.nio.client.HttpAsyncResponseConsumer;
import org.apache.http.nio.entity.NByteArrayEntity;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.protocol.HTTP;

public final class HttpAsyncMethods {

    public static HttpAsyncRequestProducer create(final HttpHost target, final HttpRequest request) {
        if (target == null) {
            throw new IllegalArgumentException("HTTP host may not be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("HTTP request may not be null");
        }
        return new HttpAsyncRequestProducerImpl(target, request);
    }

    public static HttpAsyncRequestProducer create(final HttpUriRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("HTTP request may not be null");
        }
        HttpHost target = URIUtils.extractHost(request.getURI());
        return new HttpAsyncRequestProducerImpl(target, request);
    }

    public static HttpAsyncRequestProducer createGet(final URI requestURI) {
        return create(new HttpGet(requestURI));
    }

    public static HttpAsyncRequestProducer createGet(final String requestURI) {
        return create(new HttpGet(URI.create(requestURI)));
    }

    public static HttpAsyncRequestProducer createHead(final URI requestURI) {
        return create(new HttpGet(requestURI));
    }

    public static HttpAsyncRequestProducer createHead(final String requestURI) {
        return create(new HttpGet(URI.create(requestURI)));
    }

    public static HttpAsyncRequestProducer createDelete(final URI requestURI) {
        return create(new HttpDelete(requestURI));
    }

    public static HttpAsyncRequestProducer createDelete(final String requestURI) {
        return create(new HttpDelete(URI.create(requestURI)));
    }

    public static HttpAsyncRequestProducer createOptions(final URI requestURI) {
        return create(new HttpOptions(requestURI));
    }

    public static HttpAsyncRequestProducer createOptions(final String requestURI) {
        return create(new HttpOptions(URI.create(requestURI)));
    }

    public static HttpAsyncRequestProducer createTrace(final URI requestURI) {
        return create(new HttpTrace(requestURI));
    }

    public static HttpAsyncRequestProducer createTrace(final String requestURI) {
        return create(new HttpTrace(URI.create(requestURI)));
    }

    public static HttpAsyncRequestProducer createPost(
            final URI requestURI,
            final String content,
            String mimeType, String charset) throws UnsupportedEncodingException {
        HttpPost httppost = new HttpPost(requestURI);
        if (mimeType == null) {
            mimeType = HTTP.PLAIN_TEXT_TYPE;
        }
        if (charset == null) {
            charset = HTTP.DEFAULT_CONTENT_CHARSET;
        }
        NStringEntity entity = new NStringEntity(content, charset);
        entity.setContentType(mimeType + HTTP.CHARSET_PARAM + charset);
        httppost.setEntity(entity);
        HttpHost target = URIUtils.extractHost(requestURI);
        return new HttpAsyncRequestProducerImpl(target, httppost, entity);
    }

    public static HttpAsyncRequestProducer createPost(
            final String requestURI,
            final String content,
            String mimeType, String charset) throws UnsupportedEncodingException {
        return createPost(URI.create(requestURI), content, mimeType, charset);
    }

    public static HttpAsyncRequestProducer createPost(
            final URI requestURI,
            final byte[] content, final String contentType) {
        HttpPost httppost = new HttpPost(requestURI);
        NByteArrayEntity entity = new NByteArrayEntity(content);
        entity.setContentType(contentType);
        HttpHost target = URIUtils.extractHost(requestURI);
        return new HttpAsyncRequestProducerImpl(target, httppost, entity);
    }

    public static HttpAsyncRequestProducer createPost(
            final String requestURI,
            final byte[] content, final String contentType) {
        return createPost(URI.create(requestURI), content, contentType);
    }

    public static HttpAsyncRequestProducer createPut(
            final URI requestURI,
            final String content,
            String mimeType, String charset) throws UnsupportedEncodingException {
        HttpPut httpput = new HttpPut(requestURI);
        if (mimeType == null) {
            mimeType = HTTP.PLAIN_TEXT_TYPE;
        }
        if (charset == null) {
            charset = HTTP.DEFAULT_CONTENT_CHARSET;
        }
        NStringEntity entity = new NStringEntity(content, charset);
        entity.setContentType(mimeType + HTTP.CHARSET_PARAM + charset);
        httpput.setEntity(entity);
        HttpHost target = URIUtils.extractHost(requestURI);
        return new HttpAsyncRequestProducerImpl(target, httpput, entity);
    }

    public static HttpAsyncRequestProducer createPut(
            final String requestURI,
            final String content,
            String mimeType, String charset) throws UnsupportedEncodingException {
        return createPut(URI.create(requestURI), content, mimeType, charset);
    }

    public static HttpAsyncRequestProducer createPut(
            final URI requestURI,
            final byte[] content,
            final String contentType) {
        HttpPut httpput = new HttpPut(requestURI);
        NByteArrayEntity entity = new NByteArrayEntity(content);
        entity.setContentType(contentType);
        HttpHost target = URIUtils.extractHost(requestURI);
        return new HttpAsyncRequestProducerImpl(target, httpput, entity);
    }

    public static HttpAsyncRequestProducer createPut(
            final String requestURI,
            final byte[] content, final String contentType) {
        return createPut(URI.create(requestURI), content, contentType);
    }

    public static HttpAsyncRequestProducer createZeroCopyPost(
            final URI requestURI,
            final File content,
            final String contentType) {
        return new ZeroCopyPost(requestURI, content, contentType);
    }

    public static HttpAsyncRequestProducer createZeroCopyPost(
            final String requestURI,
            final File content,
            final String contentType) {
        return new ZeroCopyPost(URI.create(requestURI), content, contentType);
    }

    public static HttpAsyncRequestProducer createZeroCopyPut(
            final URI requestURI,
            final File content,
            final String contentType) {
        return new ZeroCopyPut(requestURI, content, contentType);
    }

    public static HttpAsyncRequestProducer createZeroCopyPut(
            final String requestURI,
            final File content,
            final String contentType) {
        return new ZeroCopyPut(URI.create(requestURI), content, contentType);
    }

    public static HttpAsyncResponseConsumer<HttpResponse> createConsumer() {
        return new BasicHttpAsyncResponseConsumer();
    }

    public static HttpAsyncResponseConsumer<HttpResponse> createZeroCopyConsumer(final File file) {
        return new ZeroCopyConsumer<HttpResponse>(file) {

            @Override
            protected HttpResponse process(final HttpResponse response, final File file) throws Exception {
                return response;
            }

        };
    }

}
