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
import org.apache.http.nio.concurrent.FutureCallback;
import org.apache.http.nio.conn.ClientConnectionManager;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;

public interface HttpAsyncClient {

    void start();

    void shutdown() throws InterruptedException;

    IOReactorStatus getStatus();

    ClientConnectionManager getConnectionManager();

    HttpParams getParams();

    <T> Future<T> execute(
            HttpAsyncRequestProducer requestProducer,
            HttpAsyncResponseConsumer<T> responseConsumer,
            HttpContext context,
            FutureCallback<T> callback);

    <T> Future<T> execute(
            HttpAsyncRequestProducer requestProducer,
            HttpAsyncResponseConsumer<T> responseConsumer,
            FutureCallback<T> callback);

    Future<HttpResponse> execute(
            HttpHost target, HttpRequest request, HttpContext context,
            FutureCallback<HttpResponse> callback);

    Future<HttpResponse> execute(
            HttpHost target, HttpRequest request,
            FutureCallback<HttpResponse> callback);

    Future<HttpResponse> execute(
            HttpUriRequest request, HttpContext context,
            FutureCallback<HttpResponse> callback);

    Future<HttpResponse> execute(
            HttpUriRequest request,
            FutureCallback<HttpResponse> callback);

}
