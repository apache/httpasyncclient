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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.http.Consts;
import org.apache.http.localserver.HttpAsyncTestBase;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ContentType;
import org.apache.http.localserver.EchoHandler;
import org.apache.http.localserver.RandomHandler;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.BasicAsyncRequestHandler;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.protocol.HttpContext;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.Mockito;

public class TestAsyncConsumers extends HttpAsyncTestBase {

    @Before @Override
    public void setUp() throws Exception {
        super.setUp();
        this.serverBootstrap.registerHandler("/echo/*", new BasicAsyncRequestHandler(new EchoHandler()));
        this.serverBootstrap.registerHandler("/random/*", new BasicAsyncRequestHandler(new RandomHandler()));
    }

    static class ByteCountingConsumer extends AsyncByteConsumer<Long> {

        public ByteCountingConsumer() {
            super();
        }

        public ByteCountingConsumer(final int bufSize) {
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
        final HttpHost target = start();
        for (int i = 0; i < 5; i++) {
            final HttpAsyncRequestProducer httpget = HttpAsyncMethods.createGet(target.toURI() + "/random/20480");
            final AsyncByteConsumer<Long> consumer = new ByteCountingConsumer();
            final Future<Long> future = this.httpclient.execute(httpget, consumer, null);
            final Long count = future.get();
            Assert.assertEquals(20480, count.longValue());
        }
    }

    @Test
    public void testByteConsumerSmallBufffer() throws Exception {
        final HttpHost target = start();
        for (int i = 0; i < 5; i++) {
            final HttpAsyncRequestProducer httpget = HttpAsyncMethods.createGet(target.toURI() + "/random/20480");
            final AsyncByteConsumer<Long> consumer = new ByteCountingConsumer(512);
            final Future<Long> future = this.httpclient.execute(httpget, consumer, null);
            final Long count = future.get();
            Assert.assertEquals(20480, count.longValue());
        }
    }

    static class BufferingCharConsumer extends AsyncCharConsumer<String> {

        public BufferingCharConsumer() {
            super();
        }

        public BufferingCharConsumer(final int bufSize) {
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
        final HttpHost target = start();
        final StringBuilder sb = new StringBuilder();
        for (int i= 0; i < 25; i++) {
            sb.append("blah blah blah blah\r\n");
            sb.append("yada yada yada yada\r\n");
        }
        final String s = sb.toString();

        for (int i = 0; i < 5; i++) {
            final HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                    target.toURI() + "/echo/stuff", s,
                    ContentType.create("text/plain", Consts.ASCII));
            final AsyncCharConsumer<String> consumer = new BufferingCharConsumer();
            final Future<String> future = this.httpclient.execute(httppost, consumer, null);
            final String result = future.get();
            Assert.assertEquals(s, result);
        }
    }

    @Test
    public void testCharConsumerSmallBufffer() throws Exception {
        final HttpHost target = start();
        final StringBuilder sb = new StringBuilder();
        for (int i= 0; i < 25; i++) {
            sb.append("blah blah blah blah\r\n");
            sb.append("yada yada yada yada\r\n");
        }
        final String s = sb.toString();

        for (int i = 0; i < 5; i++) {
            final HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                    target.toURI() + "/echo/stuff", s,
                    ContentType.create("text/plain", Consts.ASCII));
            final AsyncCharConsumer<String> consumer = new BufferingCharConsumer(512);
            final Future<String> future = this.httpclient.execute(httppost, consumer, null);
            final String result = future.get();
            Assert.assertEquals(s, result);
        }
    }

    @Test
    public void testResourceReleaseOnSuccess() throws Exception {
        final HttpHost target = start();
        final StringBuilder sb = new StringBuilder();
        for (int i= 0; i < 25; i++) {
            sb.append("blah blah blah blah\r\n");
            sb.append("yada yada yada yada\r\n");
        }
        final String s = sb.toString();

        final HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                target.toURI() + "/echo/stuff", s,
                ContentType.create("text/plain", Consts.ASCII));
        final BufferingCharConsumer consumer = Mockito.spy(new BufferingCharConsumer());
        final Future<String> future = this.httpclient.execute(httppost, consumer, null);
        final String result = future.get();
        Assert.assertEquals(s, result);
        Mockito.verify(consumer).buildResult(Matchers.any(HttpContext.class));
        Mockito.verify(consumer).releaseResources();
    }

    @Test
    public void testResourceReleaseOnException() throws Exception {
        final HttpHost target = start();
        final HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                target.toURI() + "/echo/stuff", "stuff",
                ContentType.create("text/plain", Consts.ASCII));
        final AsyncCharConsumer<String> consumer = Mockito.spy(new BufferingCharConsumer());
        Mockito.doThrow(new IOException("Kaboom")).when(consumer).onCharReceived(
                Matchers.any(CharBuffer.class), Matchers.any(IOControl.class));

        final Future<String> future = this.httpclient.execute(httppost, consumer, null);
        try {
            future.get();
            Assert.fail("ExecutionException expected");
        } catch (final ExecutionException ex) {
            final Throwable t = ex.getCause();
            Assert.assertNotNull(t);
            Assert.assertTrue(t instanceof IOException);
            Assert.assertEquals("Kaboom", t.getMessage());
        }
        Mockito.verify(consumer).releaseResources();
    }

    @Test
    public void testResourceReleaseOnBuildFailure() throws Exception {
        final HttpHost target = start();
        final HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                target.toURI() + "/echo/stuff", "stuff",
                ContentType.create("text/plain", Consts.ASCII));
        final BufferingCharConsumer consumer = Mockito.spy(new BufferingCharConsumer());
        Mockito.doThrow(new HttpException("Kaboom")).when(consumer).buildResult(Matchers.any(HttpContext.class));

        final Future<String> future = this.httpclient.execute(httppost, consumer, null);
        try {
            future.get();
            Assert.fail("ExecutionException expected");
        } catch (final ExecutionException ex) {
            final Throwable t = ex.getCause();
            Assert.assertNotNull(t);
            Assert.assertTrue(t instanceof HttpException);
            Assert.assertEquals("Kaboom", t.getMessage());
        }
        Mockito.verify(consumer).releaseResources();
    }

}
