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

import org.apache.http.HttpResponse;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.HttpAsyncResponseConsumer;
import org.apache.http.nio.entity.BufferingNHttpEntity;
import org.apache.http.nio.entity.ConsumingNHttpEntity;
import org.apache.http.nio.util.HeapByteBufferAllocator;

public class BasicHttpAsyncResponseConsumer implements HttpAsyncResponseConsumer<HttpResponse> {

    private volatile HttpResponse response;
    private volatile ConsumingNHttpEntity contentConsumingEntity;
    private volatile HttpResponse result;
    private volatile Exception ex;
    private volatile boolean completed;

    public BasicHttpAsyncResponseConsumer() {
        super();
    }

    protected ConsumingNHttpEntity createConsumingHttpEntity(
            final HttpResponse response) throws IOException {
        if (response.getEntity() != null) {
            return new BufferingNHttpEntity(response.getEntity(), new HeapByteBufferAllocator());
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

    private void releaseResources() {
        if (this.contentConsumingEntity != null) {
            try {
                this.contentConsumingEntity.finish();
                this.contentConsumingEntity = null;
            } catch (IOException ex) {
            }
        }
    }

    public synchronized void responseReceived(final HttpResponse response) {
        if (this.response != null) {
            throw new IllegalStateException("HTTP response already set");
        }
        this.response = response;
    }

    public synchronized void consumeContent(
            final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
        ConsumingNHttpEntity consumer = getConsumingHttpEntity();
        consumer.consumeContent(decoder, ioctrl);
        if (decoder.isCompleted()) {
            this.response.setEntity(consumer);
            releaseResources();
        }
    }

    public synchronized void responseCompleted() {
        if (this.completed) {
            return;
        }
        this.completed = true;
        if (this.response != null) {
            this.result = this.response;
        }
        releaseResources();
    }

    public synchronized void cancel() {
        if (this.completed) {
            return;
        }
        this.completed = true;
        this.response = null;
        releaseResources();
    }

    public synchronized void failed(final Exception ex) {
        if (this.completed) {
            return;
        }
        this.completed = true;
        this.ex = ex;
        this.response = null;
        releaseResources();
    }

    public Exception getException() {
        return this.ex;
    }

    public void setEx(Exception ex) {
        this.ex = ex;
    }

    public HttpResponse getResult() {
        return this.result;
    }

}
