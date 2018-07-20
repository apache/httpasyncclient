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
package org.apache.http.impl.client.cache;

import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.execchain.ClientExecChain;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.nio.conn.ClientAsyncConnectionManager;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;
import java.util.concurrent.Future;

public class ClientExecChainAsyncClient extends CloseableHttpAsyncClient {

    private final ClientExecChain backend;

    public ClientExecChainAsyncClient(final ClientExecChain backend) {
        super();
        this.backend = backend;
    }

    @Override
    public void start() {
        // no-op
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    public void shutdown() throws InterruptedException {
        // no-op
    }

    public IOReactorStatus getStatus() {
        return null;
    }

    @SuppressWarnings("deprecation")
    public ClientAsyncConnectionManager getConnectionManager() {
        return null;
    }

    @SuppressWarnings("deprecation")
    public HttpParams getParams() {
        return null;
    }

    @Override
    public void close() throws IOException {
        // no-op
    }

    @Override
    public <T> Future<T> execute(
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final HttpContext context,
            final FutureCallback<T> callback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<HttpResponse> execute(
            final HttpHost target,
            final HttpRequest request,
            final HttpContext context,
            final FutureCallback<HttpResponse> callback) {
        final BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(
                callback);
        try {
            final HttpResponse result = backend.execute(new HttpRoute(target),
                    HttpRequestWrapper.wrap(request),
                    HttpClientContext.adapt(context), null);
            future.completed(result);
        } catch (final IOException e) {
            future.failed(e);
        } catch (final HttpException e) {
            future.failed(e);
        }
        return future;
    }

}
