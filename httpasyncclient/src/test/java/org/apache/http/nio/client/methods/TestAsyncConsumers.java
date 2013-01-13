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
package org.apache.http.nio.client.methods;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.http.Consts;
import org.apache.http.HttpAsyncTestBase;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.impl.nio.DefaultNHttpServerConnectionFactory;
import org.apache.http.localserver.EchoHandler;
import org.apache.http.localserver.RandomHandler;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.protocol.BasicAsyncRequestHandler;
import org.apache.http.nio.protocol.HttpAsyncExpectationVerifier;
import org.apache.http.nio.protocol.HttpAsyncRequestHandlerRegistry;
import org.apache.http.nio.protocol.HttpAsyncRequestHandlerResolver;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncService;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.nio.reactor.ListenerEndpoint;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class TestAsyncConsumers extends HttpAsyncTestBase {

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

    private HttpHost start() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("/echo/*", new BasicAsyncRequestHandler(new EchoHandler()));
        registry.register("/random/*", new BasicAsyncRequestHandler(new RandomHandler()));
        return start(registry, null);
    }

    static class ByteCountingConsumer extends AsyncByteConsumer<Long> {

        public ByteCountingConsumer() {
            super();
        }

        public ByteCountingConsumer(int bufSize) {
            super(bufSize);
        }

        private final AtomicLong count = new AtomicLong(0);

        @Override
        protected void onResponseReceived(final HttpResponse response) {
        }

        @Override
        protected void onByteReceived(final ByteBuffer buf, final IOControl ioctrl) {
            this.count.addAndGet(buf.remaining());
        }

        @Override
        protected Long buildResult(final HttpContext context) throws Exception {
            return count.get();
        }

    }

    @Test
    public void testByteConsumer() throws Exception {
        HttpHost target = start();
        for (int i = 0; i < 5; i++) {
            HttpAsyncRequestProducer httpget = HttpAsyncMethods.createGet(target.toURI() + "/random/20480");
            AsyncByteConsumer<Long> consumer = new ByteCountingConsumer();
            Future<Long> future = this.httpclient.execute(httpget, consumer, null);
            Long count = future.get();
            Assert.assertEquals(20480, count.longValue());
        }
    }

    @Test
    public void testByteConsumerSmallBufffer() throws Exception {
        HttpHost target = start();
        for (int i = 0; i < 5; i++) {
            HttpAsyncRequestProducer httpget = HttpAsyncMethods.createGet(target.toURI() + "/random/20480");
            AsyncByteConsumer<Long> consumer = new ByteCountingConsumer(512);
            Future<Long> future = this.httpclient.execute(httpget, consumer, null);
            Long count = future.get();
            Assert.assertEquals(20480, count.longValue());
        }
    }

    static class BufferingCharConsumer extends AsyncCharConsumer<String> {

        public BufferingCharConsumer() {
            super();
        }

        public BufferingCharConsumer(int bufSize) {
            super(bufSize);
        }

        private final StringBuilder sb = new StringBuilder();

        @Override
        public void onResponseReceived(final HttpResponse response) {
        }

        @Override
        protected void onCharReceived(final CharBuffer buf, final IOControl ioctrl) throws IOException {
            while (buf.hasRemaining()) {
                this.sb.append(buf.get());
            }
        }

        @Override
        protected void releaseResources() {
            super.releaseResources();
            this.sb.setLength(0);
        }

        @Override
        protected String buildResult(final HttpContext context) throws Exception {
            return this.sb.toString();
        }

    }

    @Test
    public void testCharConsumer() throws Exception {
        HttpHost target = start();
        StringBuilder sb = new StringBuilder();
        for (int i= 0; i < 25; i++) {
            sb.append("blah blah blah blah\r\n");
            sb.append("yada yada yada yada\r\n");
        }
        String s = sb.toString();

        for (int i = 0; i < 5; i++) {
            HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                    target.toURI() + "/echo/stuff", s,
                    ContentType.create("text/plain", Consts.ASCII));
            AsyncCharConsumer<String> consumer = new BufferingCharConsumer();
            Future<String> future = this.httpclient.execute(httppost, consumer, null);
            String result = future.get();
            Assert.assertEquals(s, result);
        }
    }

    @Test
    public void testCharConsumerSmallBufffer() throws Exception {
        HttpHost target = start();
        StringBuilder sb = new StringBuilder();
        for (int i= 0; i < 25; i++) {
            sb.append("blah blah blah blah\r\n");
            sb.append("yada yada yada yada\r\n");
        }
        String s = sb.toString();

        for (int i = 0; i < 5; i++) {
            HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                    target.toURI() + "/echo/stuff", s,
                    ContentType.create("text/plain", Consts.ASCII));
            AsyncCharConsumer<String> consumer = new BufferingCharConsumer(512);
            Future<String> future = this.httpclient.execute(httppost, consumer, null);
            String result = future.get();
            Assert.assertEquals(s, result);
        }
    }

    @Test
    public void testResourceReleaseOnSuccess() throws Exception {
        HttpHost target = start();
        StringBuilder sb = new StringBuilder();
        for (int i= 0; i < 25; i++) {
            sb.append("blah blah blah blah\r\n");
            sb.append("yada yada yada yada\r\n");
        }
        String s = sb.toString();

        HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                target.toURI() + "/echo/stuff", s,
                ContentType.create("text/plain", Consts.ASCII));
        BufferingCharConsumer consumer = Mockito.spy(new BufferingCharConsumer());
        Future<String> future = this.httpclient.execute(httppost, consumer, null);
        String result = future.get();
        Assert.assertEquals(s, result);
        Mockito.verify(consumer).buildResult(Mockito.any(HttpContext.class));
        Mockito.verify(consumer).releaseResources();
    }

    @Test
    public void testResourceReleaseOnException() throws Exception {
        HttpHost target = start();
        HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                target.toURI() + "/echo/stuff", "stuff",
                ContentType.create("text/plain", Consts.ASCII));
        AsyncCharConsumer<String> consumer = Mockito.spy(new BufferingCharConsumer());
        Mockito.doThrow(new IOException("Kaboom")).when(consumer).onCharReceived(
                Mockito.any(CharBuffer.class), Mockito.any(IOControl.class));

        Future<String> future = this.httpclient.execute(httppost, consumer, null);
        try {
            future.get();
            Assert.fail("ExecutionException expected");
        } catch (ExecutionException ex) {
            Throwable t = ex.getCause();
            Assert.assertNotNull(t);
            Assert.assertTrue(t instanceof IOException);
            Assert.assertEquals("Kaboom", t.getMessage());
        }
        Mockito.verify(consumer).releaseResources();
    }

    @Test
    public void testResourceReleaseOnBuildFailure() throws Exception {
        HttpHost target = start();
        HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                target.toURI() + "/echo/stuff", "stuff",
                ContentType.create("text/plain", Consts.ASCII));
        BufferingCharConsumer consumer = Mockito.spy(new BufferingCharConsumer());
        Mockito.doThrow(new HttpException("Kaboom")).when(consumer).buildResult(Mockito.any(HttpContext.class));

        Future<String> future = this.httpclient.execute(httppost, consumer, null);
        try {
            future.get();
            Assert.fail("ExecutionException expected");
        } catch (ExecutionException ex) {
            Throwable t = ex.getCause();
            Assert.assertNotNull(t);
            Assert.assertTrue(t instanceof HttpException);
            Assert.assertEquals("Kaboom", t.getMessage());
        }
        Mockito.verify(consumer).releaseResources();
    }

}
