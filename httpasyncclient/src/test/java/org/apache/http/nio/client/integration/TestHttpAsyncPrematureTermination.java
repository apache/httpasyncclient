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
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpAsyncTestBase;
import org.apache.http.HttpConnection;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.impl.nio.DefaultNHttpServerConnectionFactory;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.nio.protocol.BasicAsyncRequestConsumer;
import org.apache.http.nio.protocol.BasicAsyncResponseProducer;
import org.apache.http.nio.protocol.HttpAsyncExchange;
import org.apache.http.nio.protocol.HttpAsyncExpectationVerifier;
import org.apache.http.nio.protocol.HttpAsyncRequestConsumer;
import org.apache.http.nio.protocol.HttpAsyncRequestHandler;
import org.apache.http.nio.protocol.HttpAsyncRequestHandlerMapper;
import org.apache.http.nio.protocol.HttpAsyncService;
import org.apache.http.nio.protocol.UriHttpAsyncRequestHandlerMapper;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.nio.reactor.ListenerEndpoint;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestHttpAsyncPrematureTermination extends HttpAsyncTestBase {

    @Before
    public void setUp() throws Exception {
        initServer();
        initConnectionManager();
    }

    @After
    public void tearDown() throws Exception {
        shutDownClient();
        shutDownServer();
    }

    @Override
    protected NHttpConnectionFactory<DefaultNHttpServerConnection> createServerConnectionFactory(
            final ConnectionConfig config) throws Exception {
        return new DefaultNHttpServerConnectionFactory(config);
    }

    @Override
    protected String getSchemeName() {
        return "http";
    }

    private HttpHost start(
            final HttpAsyncRequestHandlerMapper requestHandlerResolver,
            final HttpAsyncExpectationVerifier expectationVerifier) throws Exception {
        final HttpAsyncService serviceHandler = new HttpAsyncService(
                this.serverHttpProc,
                DefaultConnectionReuseStrategy.INSTANCE,
                DefaultHttpResponseFactory.INSTANCE,
                requestHandlerResolver,
                expectationVerifier);

        this.httpclient = HttpAsyncClients.custom()
                .setConnectionManager(this.connMgr)
                .build();

        this.server.start(serviceHandler);
        this.httpclient.start();

        final ListenerEndpoint endpoint = this.server.getListenerEndpoint();
        endpoint.waitFor();

        Assert.assertEquals("Test server status", IOReactorStatus.ACTIVE, this.server.getStatus());
        final InetSocketAddress address = (InetSocketAddress) endpoint.getAddress();
        final HttpHost target = new HttpHost("localhost", address.getPort(), getSchemeName());
        return target;
    }

    @Test
    public void testConnectionTerminatedProcessingRequest() throws Exception {
        final UriHttpAsyncRequestHandlerMapper registry = new UriHttpAsyncRequestHandlerMapper();
        registry.register("*", new HttpAsyncRequestHandler<HttpRequest>() {

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

        this.httpclient = HttpAsyncClients.custom()
                .setConnectionManager(this.connMgr)
                .build();

        final HttpHost target = start(registry, null);
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
        final UriHttpAsyncRequestHandlerMapper registry = new UriHttpAsyncRequestHandlerMapper();
        registry.register("*", new HttpAsyncRequestHandler<HttpRequest>() {

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

        this.httpclient = HttpAsyncClients.custom()
                .setConnectionManager(this.connMgr)
                .build();

        final HttpHost target = start(registry, null);
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
        final UriHttpAsyncRequestHandlerMapper registry = new UriHttpAsyncRequestHandlerMapper();
        registry.register("*", new HttpAsyncRequestHandler<HttpRequest>() {

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

        this.httpclient = HttpAsyncClients.custom()
                .setConnectionManager(this.connMgr)
                .build();

        final HttpHost target = start(registry, null);
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

}
