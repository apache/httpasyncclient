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
package org.apache.http.nio.client.integration;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.http.HttpConnection;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.localserver.HttpAsyncTestBase;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.methods.HttpAsyncMethods;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.nio.protocol.BasicAsyncRequestConsumer;
import org.apache.http.nio.protocol.BasicAsyncResponseProducer;
import org.apache.http.nio.protocol.HttpAsyncExchange;
import org.apache.http.nio.protocol.HttpAsyncRequestConsumer;
import org.apache.http.nio.protocol.HttpAsyncRequestHandler;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.junit.Assert;
import org.junit.Test;

public class TestHttpAsyncPrematureTermination extends HttpAsyncTestBase {

    @Test
    public void testConnectionTerminatedProcessingRequest() throws Exception {
        this.serverBootstrap.registerHandler("*", new HttpAsyncRequestHandler<HttpRequest>() {

            public HttpAsyncRequestConsumer<HttpRequest> processRequest(
                    final HttpRequest request,
                    final HttpContext context) throws HttpException, IOException {
                final HttpConnection conn = (HttpConnection) context.getAttribute(
                        HttpCoreContext.HTTP_CONNECTION);
                conn.shutdown();
                return new BasicAsyncRequestConsumer();
            }

            public void handle(
                    final HttpRequest request,
                    final HttpAsyncExchange httpExchange,
                    final HttpContext context) throws HttpException, IOException {
                final HttpResponse response = httpExchange.getResponse();
                response.setEntity(new NStringEntity("all is well", ContentType.TEXT_PLAIN));
                httpExchange.submitResponse();
            }

        });

        final HttpHost target = start();
        final HttpGet httpget = new HttpGet("/");

        final CountDownLatch latch = new CountDownLatch(1);

        final FutureCallback<HttpResponse> callback = new FutureCallback<HttpResponse>() {

            public void cancelled() {
                latch.countDown();
            }

            public void failed(final Exception ex) {
                latch.countDown();
            }

            public void completed(final HttpResponse response) {
                Assert.fail();
            }

        };

        this.httpclient.execute(target, httpget, callback);
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testConnectionTerminatedHandlingRequest() throws Exception {
        this.serverBootstrap.registerHandler("*", new HttpAsyncRequestHandler<HttpRequest>() {

            public HttpAsyncRequestConsumer<HttpRequest> processRequest(
                    final HttpRequest request,
                    final HttpContext context) throws HttpException, IOException {
                return new BasicAsyncRequestConsumer();
            }

            public void handle(
                    final HttpRequest request,
                    final HttpAsyncExchange httpExchange,
                    final HttpContext context) throws HttpException, IOException {
                final HttpConnection conn = (HttpConnection) context.getAttribute(
                        HttpCoreContext.HTTP_CONNECTION);
                conn.shutdown();
                final HttpResponse response = httpExchange.getResponse();
                response.setEntity(new NStringEntity("all is well", ContentType.TEXT_PLAIN));
                httpExchange.submitResponse();
            }

        });

        final HttpHost target = start();
        final HttpGet httpget = new HttpGet("/");

        final CountDownLatch latch = new CountDownLatch(1);

        final FutureCallback<HttpResponse> callback = new FutureCallback<HttpResponse>() {

            public void cancelled() {
                latch.countDown();
            }

            public void failed(final Exception ex) {
                latch.countDown();
            }

            public void completed(final HttpResponse response) {
                Assert.fail();
            }

        };

        this.httpclient.execute(target, httpget, callback);
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testConnectionTerminatedSendingResponse() throws Exception {
        this.serverBootstrap.registerHandler("*", new HttpAsyncRequestHandler<HttpRequest>() {

            public HttpAsyncRequestConsumer<HttpRequest> processRequest(
                    final HttpRequest request,
                    final HttpContext context) throws HttpException, IOException {
                return new BasicAsyncRequestConsumer();
            }

            public void handle(
                    final HttpRequest request,
                    final HttpAsyncExchange httpExchange,
                    final HttpContext context) throws HttpException, IOException {
                final HttpResponse response = httpExchange.getResponse();
                response.setEntity(new NStringEntity("all is well", ContentType.TEXT_PLAIN));
                httpExchange.submitResponse(new BasicAsyncResponseProducer(response) {

                    @Override
                    public synchronized void produceContent(
                            final ContentEncoder encoder,
                            final IOControl ioctrl) throws IOException {
                        ioctrl.shutdown();
                    }

                });
            }

        });

        final HttpHost target = start();
        final HttpGet httpget = new HttpGet("/");

        final CountDownLatch latch = new CountDownLatch(1);

        final FutureCallback<HttpResponse> callback = new FutureCallback<HttpResponse>() {

            public void cancelled() {
                latch.countDown();
            }

            public void failed(final Exception ex) {
                latch.countDown();
            }

            public void completed(final HttpResponse response) {
                Assert.fail();
            }

        };

        this.httpclient.execute(target, httpget, callback);
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testConnectionRequestFailure() throws Exception {
        this.httpclient = HttpAsyncClients.custom()
                .setConnectionManager(this.connMgr)
                .build();
        this.httpclient.start();

        final HttpGet get = new HttpGet("http://stuff.invalid/");
        final HttpAsyncRequestProducer producer = HttpAsyncMethods.create(get);

        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean failed = new AtomicBoolean(false);

        final HttpAsyncResponseConsumer<?> consumer = new HttpAsyncResponseConsumer<Object>() {

            @Override
            public void close() throws IOException {
                closed.set(true);
            }

            @Override
            public boolean cancel() {
                cancelled.set(true);
                return false;
            }

            @Override
            public void failed(final Exception ex) {
                failed.set(true);
            }

            public void responseReceived(
                    final HttpResponse response) throws IOException, HttpException {
                throw new IllegalStateException();
            }

            public void consumeContent(
                    final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
                throw new IllegalStateException();
            }

            public void responseCompleted(final HttpContext context) {
                throw new IllegalStateException();
            }

            public Exception getException() {
                return null;
            }

            public String getResult() {
                return null;
            }

            @Override
            public boolean isDone() {
                return false;
            }
        };

        final Future<?> future = httpclient.execute(producer, consumer, null, null);
        try {
            future.get();
            Assert.fail();
        } catch (ExecutionException e) {
            Assert.assertTrue(e.getCause() instanceof UnknownHostException);
        }
        this.connMgr.shutdown(1000);

        Assert.assertTrue(closed.get());
        Assert.assertFalse(cancelled.get());
        Assert.assertTrue(failed.get());
    }

}
