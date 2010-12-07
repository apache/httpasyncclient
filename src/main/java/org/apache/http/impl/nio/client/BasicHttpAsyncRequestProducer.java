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
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.HttpAsyncRequestProducer;
import org.apache.http.nio.entity.NHttpEntityWrapper;
import org.apache.http.nio.entity.ProducingNHttpEntity;

public class BasicHttpAsyncRequestProducer implements HttpAsyncRequestProducer {

    private final HttpHost target;
    private final HttpRequest request;

    private volatile ProducingNHttpEntity contentProducingEntity;

    public BasicHttpAsyncRequestProducer(final HttpHost target, final HttpRequest request) {
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

    public HttpRequest generateRequest() {
        return this.request;
    }

    public HttpHost getTarget() {
        return this.target;
    }

    protected ProducingNHttpEntity createProducingHttpEntity(
            final HttpRequest request) throws IOException {
        HttpEntity entity = null;
        if (request instanceof HttpEntityEnclosingRequest) {
            entity = ((HttpEntityEnclosingRequest) request).getEntity();
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

    private ProducingNHttpEntity getProducingHttpEntity() throws IOException {
        if (this.contentProducingEntity == null) {
            this.contentProducingEntity = createProducingHttpEntity(this.request);
            if (this.contentProducingEntity == null) {
                throw new IllegalStateException("Content producer is null");
            }
        }
        return this.contentProducingEntity;
    }

    public synchronized void produceContent(
            final ContentEncoder encoder, final IOControl ioctrl) throws IOException {
        ProducingNHttpEntity producer = getProducingHttpEntity();
        producer.produceContent(encoder, ioctrl);
    }

    public synchronized boolean isRepeatable() {
        if (this.request instanceof HttpEntityEnclosingRequest) {
            HttpEntity entity = ((HttpEntityEnclosingRequest) this.request).getEntity();
            if (entity != null) {
                return entity.isRepeatable();
            } else {
                return true;
            }
        } else {
            return true;
        }
    }

    public synchronized void resetRequest() {
        if (this.contentProducingEntity != null) {
            try {
                this.contentProducingEntity.finish();
                this.contentProducingEntity = null;
            } catch (IOException ex) {
            }
        }
    }

}
