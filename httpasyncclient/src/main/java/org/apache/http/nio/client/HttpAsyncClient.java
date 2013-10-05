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
package org.apache.http.nio.client;

import java.util.concurrent.Future;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HttpContext;

/**
 * This interface represents only the most basic contract for HTTP request
 * execution. It imposes no restrictions or particular details on the request
 * execution process and leaves the specifics of state management,
 * authentication and redirect handling up to individual implementations.
 *
 * @since 4.0
 */
public interface HttpAsyncClient {

    /**
     * Initiates asynchronous HTTP request execution using the given context.
     * <p/>
     * The request producer passed to this method will be used to generate
     * a request message and stream out its content without buffering it
     * in memory. The response consumer passed to this method will be used
     * to process a response message without buffering its content in memory.
     * <p/>
     * Please note it may be unsafe to interact with the context instance
     * while the request is still being executed.
     *
     * @param <T> the result type of request execution.
     * @param requestProducer request producer callback.
     * @param responseConsumer response consumer callaback.
     * @param context HTTP context
     * @param callback future callback.
     * @return future representing pending completion of the operation.
     */
    <T> Future<T> execute(
            HttpAsyncRequestProducer requestProducer,
            HttpAsyncResponseConsumer<T> responseConsumer,
            HttpContext context,
            FutureCallback<T> callback);

    /**
     * Initiates asynchronous HTTP request execution using the default
     * context.
     * <p/>
     * The request producer passed to this method will be used to generate
     * a request message and stream out its content without buffering it
     * in memory. The response consumer passed to this method will be used
     * to process a response message without buffering its content in memory.
     *
     * @param <T> the result type of request execution.
     * @param requestProducer request producer callback.
     * @param responseConsumer response consumer callaback.
     * @param callback future callback.
     * @return future representing pending completion of the operation.
     */
    <T> Future<T> execute(
            HttpAsyncRequestProducer requestProducer,
            HttpAsyncResponseConsumer<T> responseConsumer,
            FutureCallback<T> callback);

    /**
     * Initiates asynchronous HTTP request execution against the given target
     * using the given context.
     * <p/>
     * Please note it may be unsafe to interact with the context instance
     * while the request is still being executed.
     *
     * @param target    the target host for the request.
     *                  Implementations may accept <code>null</code>
     *                  if they can still determine a route, for example
     *                  to a default target or by inspecting the request.
     * @param request   the request to execute
     * @param context   the context to use for the execution, or
     *                  <code>null</code> to use the default context
     * @param callback future callback.
     * @return future representing pending completion of the operation.
     */
    Future<HttpResponse> execute(
            HttpHost target, HttpRequest request, HttpContext context,
            FutureCallback<HttpResponse> callback);

    /**
     * Initiates asynchronous HTTP request execution against the given target
     * using the default context.
     *
     * @param target    the target host for the request.
     *                  Implementations may accept <code>null</code>
     *                  if they can still determine a route, for example
     *                  to a default target or by inspecting the request.
     * @param request   the request to execute
     * @param callback future callback.
     * @return future representing pending completion of the operation.
     */
    Future<HttpResponse> execute(
            HttpHost target, HttpRequest request,
            FutureCallback<HttpResponse> callback);

    /**
     * Initiates asynchronous HTTP request execution using the given
     * context.
     * <p/>
     * Please note it may be unsafe to interact with the context instance
     * while the request is still being executed.
     *
     * @param request   the request to execute
     * @param context HTTP context
     * @param callback future callback.
     * @return future representing pending completion of the operation.
     */
    Future<HttpResponse> execute(
            HttpUriRequest request, HttpContext context,
            FutureCallback<HttpResponse> callback);

    /**
     * Initiates asynchronous HTTP request execution using the default
     * context.
     *
     * @param request   the request to execute
     * @param callback future callback.
     * @return future representing pending completion of the operation.
     */
    Future<HttpResponse> execute(
            HttpUriRequest request,
            FutureCallback<HttpResponse> callback);

}
