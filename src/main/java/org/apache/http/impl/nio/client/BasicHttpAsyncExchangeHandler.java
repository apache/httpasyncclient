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
package org.apache.http.impl.nio.client;

import java.io.IOException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.HttpAsyncExchangeHandler;
import org.apache.http.nio.entity.BufferingNHttpEntity;
import org.apache.http.nio.entity.ConsumingNHttpEntity;
import org.apache.http.nio.entity.NHttpEntityWrapper;
import org.apache.http.nio.entity.ProducingNHttpEntity;
import org.apache.http.nio.util.HeapByteBufferAllocator;

public class BasicHttpAsyncExchangeHandler implements HttpAsyncExchangeHandler<HttpResponse> {

    private final HttpHost target;
    private final HttpRequest request;

    private HttpResponse response;
    private ProducingNHttpEntity contentProducingEntity;
    private ConsumingNHttpEntity contentConsumingEntity;

    public BasicHttpAsyncExchangeHandler(
            final HttpHost target, final HttpRequest request) {
        super();
        if (target == null) {
            throw new IllegalArgumentException("Target host may not be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("HTTP request may not be null");
        }
        this.target = target;
        this.request = request;
    }

    public HttpRequest getRequest() {
        return this.request;
    }

    public HttpHost getTarget() {
        return this.target;
    }

    protected ConsumingNHttpEntity createConsumingHttpEntity(
            final HttpResponse response) throws IOException {
        if (response.getEntity() != null) {
            return new BufferingNHttpEntity(response.getEntity(), new HeapByteBufferAllocator());
        } else {
            return null;
        }
    }

    protected ProducingNHttpEntity createProducingHttpEntity(
            final HttpRequest request) throws IOException {
        HttpEntityEnclosingRequest entityReq;
        HttpEntity entity = null;
        if (request instanceof HttpEntityEnclosingRequest) {
            entityReq = (HttpEntityEnclosingRequest) request;
            entity = entityReq.getEntity();
        }
        if (entity != null) {
            if (entity instanceof ProducingNHttpEntity) {
                return (ProducingNHttpEntity) entity;
            } else {
                return new NHttpEntityWrapper(entity);
            }
        } else {
            return null;
        }
    }

    private ConsumingNHttpEntity getConsumingHttpEntity() throws IOException {
        if (this.contentConsumingEntity == null) {
            this.contentConsumingEntity = createConsumingHttpEntity(this.response);
            if (this.contentConsumingEntity == null) {
                throw new IllegalStateException("Content consumer is null");
            }
        }
        return this.contentConsumingEntity;
    }

    private ProducingNHttpEntity getProducingHttpEntity() throws IOException {
        if (this.contentProducingEntity == null) {
            this.contentProducingEntity = createProducingHttpEntity(this.request);
            if (this.contentProducingEntity == null) {
                throw new IllegalStateException("Content producer is null");
            }
        }
        return this.contentProducingEntity;
    }

    public void produceContent(
            final ContentEncoder encoder, final IOControl ioctrl) throws IOException {
        ProducingNHttpEntity producer = getProducingHttpEntity();
        producer.produceContent(encoder, ioctrl);
    }

    public void responseReceived(final HttpResponse response) {
        if (this.response != null) {
            throw new IllegalStateException("HTTP response already set");
        }
        this.response = response;
    }

    public void consumeContent(
            final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
        ConsumingNHttpEntity consumer = getConsumingHttpEntity();
        consumer.consumeContent(decoder, ioctrl);
    }

    private void shutdown() {
        if (this.contentProducingEntity != null) {
            try {
                this.contentProducingEntity.finish();
            } catch (IOException ex) {
            }
        }
        if (this.contentConsumingEntity != null) {
            try {
                this.contentConsumingEntity.finish();
            } catch (IOException ex) {
            }
        }
    }

    public void cancelled() {
        shutdown();
    }

    public void failed(final Exception ex) {
        shutdown();
    }

    public HttpResponse completed() {
        if (this.response == null) {
            throw new IllegalStateException("HTTP response is null");
        }
        HttpResponse response = this.response;
        response.setEntity(this.contentConsumingEntity);
        shutdown();
        return response;
    }

}
