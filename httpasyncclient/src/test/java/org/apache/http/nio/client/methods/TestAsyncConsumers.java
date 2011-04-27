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
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.impl.nio.client.DefaultHttpAsyncClient;
import org.apache.http.impl.nio.conn.PoolingClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.localserver.ServerTestBase;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.conn.scheme.Scheme;
import org.apache.http.nio.conn.scheme.SchemeRegistry;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.params.BasicHttpParams;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestAsyncConsumers extends ServerTestBase {

    private HttpHost target;
    private PoolingClientConnectionManager sessionManager;
    private HttpAsyncClient httpclient;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        this.localServer.registerDefaultHandlers();
        int port = this.localServer.getServiceAddress().getPort();
        this.target = new HttpHost("localhost", port);

        ConnectingIOReactor ioReactor = new DefaultConnectingIOReactor(2, new BasicHttpParams());
        SchemeRegistry schemeRegistry = new SchemeRegistry();
        schemeRegistry.register(new Scheme("http", 80, null));
        this.sessionManager = new PoolingClientConnectionManager(ioReactor, schemeRegistry);
        this.httpclient = new DefaultHttpAsyncClient(this.sessionManager);
    }

    @After
    public void tearDown() throws Exception {
        this.httpclient.shutdown();
        super.tearDown();
    }

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
        protected void onCleanup() {
        }

        @Override
        protected Long buildResult() throws Exception {
            return count.get();
        }

    }

    @Test
    public void testByteConsumer() throws Exception {
        this.httpclient.start();
        for (int i = 0; i < 5; i++) {
            HttpAsyncGet httpget = new HttpAsyncGet(this.target.toURI() + "/random/20480");
            AsyncByteConsumer<Long> consumer = new ByteCountingConsumer();
            Future<Long> future = this.httpclient.execute(httpget, consumer, null);
            Long count = future.get();
            Assert.assertEquals(20480, count.longValue());
        }
    }

    @Test
    public void testByteConsumerSmallBufffer() throws Exception {
        this.httpclient.start();
        for (int i = 0; i < 5; i++) {
            HttpAsyncGet httpget = new HttpAsyncGet(this.target.toURI() + "/random/20480");
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
        protected void onCleanup() {
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

        this.httpclient.start();
        for (int i = 0; i < 5; i++) {
            HttpAsyncPost httppost = new HttpAsyncPost(this.target.toURI() + "/echo/stuff", s);
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

        this.httpclient.start();
        for (int i = 0; i < 5; i++) {
            HttpAsyncPost httppost = new HttpAsyncPost(this.target.toURI() + "/echo/stuff", s);
            AsyncCharConsumer<String> consumer = new BufferingCharConsumer(512);
            Future<String> future = this.httpclient.execute(httppost, consumer, null);
            String result = future.get();
            Assert.assertEquals(s, result);
        }
    }

}
