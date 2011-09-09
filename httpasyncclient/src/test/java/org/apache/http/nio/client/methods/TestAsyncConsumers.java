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

import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ContentType;
import org.apache.http.localserver.AsyncHttpTestBase;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class TestAsyncConsumers extends AsyncHttpTestBase {

    static class ByteCountingConsumer extends AsyncByteConsumer<Long> {

        public ByteCountingConsumer() {
            super();
        }

        public ByteCountingConsumer(int bufSize) {
            super(bufSize);
        }

        private AtomicLong count = new AtomicLong(0);

        @Override
        protected void onResponseReceived(final HttpResponse response) {
        }

        @Override
        protected void onByteReceived(final ByteBuffer buf, final IOControl ioctrl) throws IOException {
            this.count.addAndGet(buf.remaining());
        }

        @Override
        protected Long buildResult() throws Exception {
            return count.get();
        }

    }

    @Test
    public void testByteConsumer() throws Exception {
        for (int i = 0; i < 5; i++) {
            HttpAsyncRequestProducer httpget = HttpAsyncMethods.createGet(this.target.toURI() + "/random/20480");
            AsyncByteConsumer<Long> consumer = new ByteCountingConsumer();
            Future<Long> future = this.httpclient.execute(httpget, consumer, null);
            Long count = future.get();
            Assert.assertEquals(20480, count.longValue());
        }
    }

    @Test
    public void testByteConsumerSmallBufffer() throws Exception {
        for (int i = 0; i < 5; i++) {
            HttpAsyncRequestProducer httpget = HttpAsyncMethods.createGet(this.target.toURI() + "/random/20480");
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

        private StringBuilder sb = new StringBuilder();

        @Override
        protected void onResponseReceived(final HttpResponse response) {
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
        protected String buildResult() throws Exception {
            return this.sb.toString();
        }

    }

    @Test
    public void testCharConsumer() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i= 0; i < 25; i++) {
            sb.append("blah blah blah blah\r\n");
            sb.append("yada yada yada yada\r\n");
        }
        String s = sb.toString();

        for (int i = 0; i < 5; i++) {
            HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                    this.target.toURI() + "/echo/stuff", s,
                    ContentType.create("text/plain", HTTP.ASCII));
            AsyncCharConsumer<String> consumer = new BufferingCharConsumer();
            Future<String> future = this.httpclient.execute(httppost, consumer, null);
            String result = future.get();
            Assert.assertEquals(s, result);
        }
    }

    @Test
    public void testCharConsumerSmallBufffer() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i= 0; i < 25; i++) {
            sb.append("blah blah blah blah\r\n");
            sb.append("yada yada yada yada\r\n");
        }
        String s = sb.toString();

        for (int i = 0; i < 5; i++) {
            HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                    this.target.toURI() + "/echo/stuff", s,
                    ContentType.create("text/plain", HTTP.ASCII));
            AsyncCharConsumer<String> consumer = new BufferingCharConsumer(512);
            Future<String> future = this.httpclient.execute(httppost, consumer, null);
            String result = future.get();
            Assert.assertEquals(s, result);
        }
    }

    @Test
    public void testResourceReleaseOnSuccess() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i= 0; i < 25; i++) {
            sb.append("blah blah blah blah\r\n");
            sb.append("yada yada yada yada\r\n");
        }
        String s = sb.toString();

        HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                this.target.toURI() + "/echo/stuff", s,
                ContentType.create("text/plain", HTTP.ASCII));
        BufferingCharConsumer consumer = Mockito.spy(new BufferingCharConsumer());
        Future<String> future = this.httpclient.execute(httppost, consumer, null);
        String result = future.get();
        Assert.assertEquals(s, result);
        Mockito.verify(consumer).responseCompleted(Mockito.any(HttpContext.class));
        Mockito.verify(consumer).buildResult();
        Mockito.verify(consumer).releaseResources();
    }

    @Test
    public void testResourceReleaseOnException() throws Exception {
        HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                this.target.toURI() + "/echo/stuff", "stuff",
                ContentType.create("text/plain", HTTP.ASCII));
        AsyncCharConsumer<String> consumer = Mockito.spy(new BufferingCharConsumer());
        Mockito.doThrow(new IOException("Kaboom")).when(consumer).consumeContent(
                Mockito.any(ContentDecoder.class), Mockito.any(IOControl.class));

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
        Mockito.verify(consumer).failed(Mockito.any(IOException.class));
        Mockito.verify(consumer).releaseResources();
    }

    @Test
    public void testResourceReleaseOnBuildFailure() throws Exception {
        HttpAsyncRequestProducer httppost = HttpAsyncMethods.createPost(
                this.target.toURI() + "/echo/stuff", "stuff",
                ContentType.create("text/plain", HTTP.ASCII));
        BufferingCharConsumer consumer = Mockito.spy(new BufferingCharConsumer());
        Mockito.doThrow(new HttpException("Kaboom")).when(consumer).buildResult();

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
        Mockito.verify(consumer).responseCompleted(Mockito.any(HttpContext.class));
        Mockito.verify(consumer).releaseResources();
    }

}
