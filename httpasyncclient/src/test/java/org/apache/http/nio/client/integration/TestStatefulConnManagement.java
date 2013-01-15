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
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import org.apache.http.HttpAsyncTestBase;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.UserTokenHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.impl.nio.DefaultNHttpServerConnectionFactory;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.conn.ManagedClientAsyncConnection;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.nio.protocol.AbstractAsyncResponseConsumer;
import org.apache.http.nio.protocol.BasicAsyncRequestHandler;
import org.apache.http.nio.protocol.BasicAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncExpectationVerifier;
import org.apache.http.nio.protocol.HttpAsyncRequestHandlerRegistry;
import org.apache.http.nio.protocol.HttpAsyncRequestHandlerResolver;
import org.apache.http.nio.protocol.HttpAsyncService;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.nio.reactor.ListenerEndpoint;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestStatefulConnManagement extends HttpAsyncTestBase {

    @Before
    public void setUp() throws Exception {
        initServer();
        initClient();
    }

    @After
    public void tearDown() throws Exception {
        shutDownClient();
        shutDownServer();
    }

    @Override
    protected NHttpConnectionFactory<DefaultNHttpServerConnection> createServerConnectionFactory(
            final HttpParams params) throws Exception {
        return new DefaultNHttpServerConnectionFactory(params);
    }

    @Override
    protected String getSchemeName() {
        return "http";
    }

    private HttpHost start(
            final HttpAsyncRequestHandlerResolver requestHandlerResolver,
            final HttpAsyncExpectationVerifier expectationVerifier) throws Exception {
        HttpAsyncService serviceHandler = new HttpAsyncService(
                this.serverHttpProc,
                new DefaultConnectionReuseStrategy(),
                new DefaultHttpResponseFactory(),
                requestHandlerResolver,
                expectationVerifier,
                this.serverParams);
        this.server.start(serviceHandler);
        this.httpclient.start();

        ListenerEndpoint endpoint = this.server.getListenerEndpoint();
        endpoint.waitFor();

        Assert.assertEquals("Test server status", IOReactorStatus.ACTIVE, this.server.getStatus());
        InetSocketAddress address = (InetSocketAddress) endpoint.getAddress();
        HttpHost target = new HttpHost("localhost", address.getPort(), getSchemeName());
        return target;
    }

    static class SimpleService implements HttpRequestHandler {

        public SimpleService() {
            super();
        }

        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {
            response.setStatusCode(HttpStatus.SC_OK);
            NStringEntity entity = new NStringEntity("Whatever");
            response.setEntity(entity);
        }
    }

    @Test
    public void testStatefulConnections() throws Exception {
        this.httpclient.setUserTokenHandler(new UserTokenHandler() {

            public Object getUserToken(final HttpContext context) {
                Integer id = (Integer) context.getAttribute("user");
                return id;
            }

        });

        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(new SimpleService()));

        HttpHost target = start(registry, null);

        int workerCount = 2;
        int requestCount = 5;

        HttpParams params = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(params, 10);

        HttpContext[] contexts = new HttpContext[workerCount];
        HttpWorker[] workers = new HttpWorker[workerCount];
        for (int i = 0; i < contexts.length; i++) {
            HttpContext context = new BasicHttpContext();
            Object token = Integer.valueOf(i);
            context.setAttribute("user", token);
            contexts[i] = context;
            workers[i] = new HttpWorker(context, requestCount, target, this.httpclient);
        }

        for (HttpWorker worker : workers) {
            worker.start();
        }
        for (HttpWorker worker : workers) {
            worker.join(10000);
        }
        for (HttpWorker worker : workers) {
            Exception ex = worker.getException();
            if (ex != null) {
                throw ex;
            }
            Assert.assertEquals(requestCount, worker.getCount());
        }

        for (HttpContext context : contexts) {
            Integer id = (Integer) context.getAttribute("user");

            for (int r = 0; r < requestCount; r++) {
                Integer state = (Integer) context.getAttribute("r" + r);
                Assert.assertEquals(id, state);
            }
        }

    }

    static class HttpWorker extends Thread {

        private final HttpContext context;
        private final int requestCount;
        private final HttpHost target;
        private final HttpAsyncClient httpclient;

        private volatile Exception exception;
        private volatile int count;

        public HttpWorker(
                final HttpContext context,
                final int requestCount,
                final HttpHost target,
                final HttpAsyncClient httpclient) {
            super();
            this.context = context;
            this.requestCount = requestCount;
            this.target = target;
            this.httpclient = httpclient;
            this.count = 0;
        }

        public int getCount() {
            return this.count;
        }

        public Exception getException() {
            return this.exception;
        }

        @Override
        public void run() {
            try {
                for (int r = 0; r < this.requestCount; r++) {
                    HttpGet httpget = new HttpGet("/");
                    Future<Object> future = this.httpclient.execute(
                            new BasicAsyncRequestProducer(this.target, httpget),
                            new AbstractAsyncResponseConsumer<Object>() {

                                @Override
                                protected void onResponseReceived(final HttpResponse response) {
                                }

                                @Override
                                protected void onEntityEnclosed(
                                        final HttpEntity entity,
                                        final ContentType contentType) throws IOException {
                                }

                                @Override
                                protected void onContentReceived(
                                        final ContentDecoder decoder,
                                        final IOControl ioctrl) throws IOException {
                                    ByteBuffer buf = ByteBuffer.allocate(2048);
                                    decoder.read(buf);
                                }

                                @Override
                                protected Object buildResult(final HttpContext context) throws Exception {
                                    ManagedClientAsyncConnection conn = (ManagedClientAsyncConnection) context.getAttribute(
                                            IOEventDispatch.CONNECTION_KEY);
                                    return conn.getState();
                                }

                                @Override
                                protected void releaseResources() {
                                }

                            },
                            this.context,
                            null);
                    this.count++;
                    Object state = future.get();
                    this.context.setAttribute("r" + r, state);
                }

            } catch (Exception ex) {
                this.exception = ex;
            }
        }

    }

    @Test
    public void testRouteSpecificPoolRecylcing() throws Exception {
        // This tests what happens when a maxed connection pool needs
        // to kill the last idle connection to a route to build a new
        // one to the same route.
        this.httpclient.setUserTokenHandler(new UserTokenHandler() {

            public Object getUserToken(final HttpContext context) {
                return context.getAttribute("user");
            }

        });

        int maxConn = 2;
        // We build a client with 2 max active // connections, and 2 max per route.
        this.connMgr.setMaxTotal(maxConn);
        this.connMgr.setDefaultMaxPerRoute(maxConn);

        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(new SimpleService()));

        HttpHost target = start(registry, null);

        // Bottom of the pool : a *keep alive* connection to Route 1.
        HttpContext context1 = new BasicHttpContext();
        context1.setAttribute("user", "stuff");

        Future<HttpResponse> future1 = this.httpclient.execute(
                target, new HttpGet("/"), context1, null);
        HttpResponse response1 = future1.get();
        Assert.assertNotNull(response1);
        Assert.assertEquals(200, response1.getStatusLine().getStatusCode());

        // The ConnPoolByRoute now has 1 free connection, out of 2 max
        // The ConnPoolByRoute has one RouteSpcfcPool, that has one free connection
        // for [localhost][stuff]

        Thread.sleep(100);

        // Send a very simple HTTP get (it MUST be simple, no auth, no proxy, no 302, no 401, ...)
        // Send it to another route. Must be a keepalive.
        HttpContext context2 = new BasicHttpContext();

        Future<HttpResponse> future2 = this.httpclient.execute(
                new HttpHost("127.0.0.1", target.getPort(), target.getSchemeName()),
                new HttpGet("/"), context2, null);
        HttpResponse response2 = future2.get();
        Assert.assertNotNull(response2);
        Assert.assertEquals(200, response2.getStatusLine().getStatusCode());

        // ConnPoolByRoute now has 2 free connexions, out of its 2 max.
        // The [localhost][stuff] RouteSpcfcPool is the same as earlier
        // And there is a [127.0.0.1][null] pool with 1 free connection

        Thread.sleep(100);

        // This will put the ConnPoolByRoute to the targeted state :
        // [localhost][stuff] will not get reused because this call is [localhost][null]
        // So the ConnPoolByRoute will need to kill one connection (it is maxed out globally).
        // The killed conn is the oldest, which means the first HTTPGet ([localhost][stuff]).
        // When this happens, the RouteSpecificPool becomes empty.
        HttpContext context3 = new BasicHttpContext();
        Future<HttpResponse> future3 = this.httpclient.execute(
                target, new HttpGet("/"), context3, null);
        HttpResponse response3 = future3.get();
        Assert.assertNotNull(response3);
        Assert.assertEquals(200, response3.getStatusLine().getStatusCode());
    }

}
