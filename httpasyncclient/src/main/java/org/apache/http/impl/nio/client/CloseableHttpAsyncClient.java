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

import java.io.Closeable;
import java.net.URI;
import java.util.concurrent.Future;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.annotation.ThreadSafe;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.client.methods.HttpAsyncMethods;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Args;

/**
 * Base implementation of {@link HttpAsyncClient} that also implements {@link Closeable}.
 *
 * @since 4.0
 */
@ThreadSafe
public abstract class CloseableHttpAsyncClient implements HttpAsyncClient, Closeable {

    public abstract boolean isRunning();

    public abstract void start();

    @Override
    public <T> Future<T> execute(
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final FutureCallback<T> callback) {
        return execute(requestProducer, responseConsumer, new BasicHttpContext(), callback);
    }

    @Override
    public Future<HttpResponse> execute(
            final HttpHost target, final HttpRequest request, final HttpContext context,
            final FutureCallback<HttpResponse> callback) {
        return execute(
                HttpAsyncMethods.create(target, request),
                HttpAsyncMethods.createConsumer(),
                context, callback);
    }

    @Override
    public Future<HttpResponse> execute(
            final HttpHost target, final HttpRequest request,
            final FutureCallback<HttpResponse> callback) {
        return execute(target, request, new BasicHttpContext(), callback);
    }

    @Override
    public Future<HttpResponse> execute(
            final HttpUriRequest request,
            final FutureCallback<HttpResponse> callback) {
        return execute(request, new BasicHttpContext(), callback);
    }

    @Override
    public Future<HttpResponse> execute(
            final HttpUriRequest request,
            final HttpContext context,
            final FutureCallback<HttpResponse> callback) {
        final HttpHost target;
        try {
            target = determineTarget(request);
        } catch (final ClientProtocolException ex) {
            final BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(callback);
            future.failed(ex);
            return future;
        }
        return execute(target, request, context, callback);
    }

    private HttpHost determineTarget(final HttpUriRequest request) throws ClientProtocolException {
        Args.notNull(request, "HTTP request");
        // A null target may be acceptable if there is a default target.
        // Otherwise, the null target is detected in the director.
        HttpHost target = null;

        final URI requestURI = request.getURI();
        if (requestURI.isAbsolute()) {
            target = URIUtils.extractHost(requestURI);
            if (target == null) {
                throw new ClientProtocolException(
                        "URI does not specify a valid host name: " + requestURI);
            }
        }
        return target;
    }

}
