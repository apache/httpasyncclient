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
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.http.localserver.HttpAsyncTestBase;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.localserver.EchoHandler;
import org.apache.http.localserver.RandomHandler;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.methods.HttpAsyncMethods;
import org.apache.http.nio.client.util.HttpAsyncClientUtils;
import org.apache.http.nio.entity.NByteArrayEntity;
import org.apache.http.nio.protocol.BasicAsyncRequestHandler;
import org.apache.http.nio.protocol.BasicAsyncResponseConsumer;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.util.EntityUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TestHttpAsync extends HttpAsyncTestBase {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> protocols() {
        return Arrays.asList(new Object[][]{
                { ProtocolScheme.http },
                { ProtocolScheme.https },
        });
    }

    public TestHttpAsync(final ProtocolScheme scheme) {
        super(scheme);
    }

    @Before @Override
    public void setUp() throws Exception {
        super.setUp();
        this.serverBootstrap.registerHandler("/echo/*", new BasicAsyncRequestHandler(new EchoHandler()));
        this.serverBootstrap.registerHandler("/random/*", new BasicAsyncRequestHandler(new RandomHandler()));
    }

    @Test
    public void testSingleGet() throws Exception {
        final HttpHost target = start();
        final HttpGet httpget = new HttpGet("/random/2048");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatusLine().getStatusCode());
    }

    @Test
    public void testSinglePost() throws Exception {
        final HttpHost target = start();
        final byte[] b1 = new byte[1024];
        final Random rnd = new Random(System.currentTimeMillis());
        rnd.nextBytes(b1);

        final HttpPost httppost = new HttpPost("/echo/stuff");
        httppost.setEntity(new NByteArrayEntity(b1));

        final Future<HttpResponse> future = this.httpclient.execute(target, httppost, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatusLine().getStatusCode());
        final HttpEntity entity = response.getEntity();
        Assert.assertNotNull(entity);
        final byte[] b2 = EntityUtils.toByteArray(entity);
        Assert.assertArrayEquals(b1, b2);
    }

    @Test
    public void testHttpAsyncMethods() throws Exception {
        final HttpHost target = start();
        final byte[] b1 = new byte[1024];
        final Random rnd = new Random(System.currentTimeMillis());
        rnd.nextBytes(b1);

        final Future<HttpResponse> future = this.httpclient.execute(
            HttpAsyncMethods.createPost(target + "/echo/post", b1, null),
            new BasicAsyncResponseConsumer(),
            null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatusLine().getStatusCode());
        final HttpEntity entity = response.getEntity();
        Assert.assertNotNull(entity);
        final byte[] b2 = EntityUtils.toByteArray(entity);
        Assert.assertArrayEquals(b1, b2);
    }

    @Test
    public void testMultiplePostsOverMultipleConnections() throws Exception {
        final HttpHost target = start();
        final byte[] b1 = new byte[1024];
        final Random rnd = new Random(System.currentTimeMillis());
        rnd.nextBytes(b1);

        final int reqCount = 20;

        this.connMgr.setDefaultMaxPerRoute(reqCount);
        this.connMgr.setMaxTotal(100);

        final Queue<Future<HttpResponse>> queue = new LinkedList<Future<HttpResponse>>();

        for (int i = 0; i < reqCount; i++) {
            final HttpPost httppost = new HttpPost("/echo/stuff");
            httppost.setEntity(new NByteArrayEntity(b1));
            queue.add(this.httpclient.execute(target, httppost, null));
        }

        while (!queue.isEmpty()) {
            final Future<HttpResponse> future = queue.remove();
            final HttpResponse response = future.get();
            Assert.assertNotNull(response);
            Assert.assertEquals(200, response.getStatusLine().getStatusCode());
            final HttpEntity entity = response.getEntity();
            Assert.assertNotNull(entity);
            final byte[] b2 = EntityUtils.toByteArray(entity);
            Assert.assertArrayEquals(b1, b2);
        }
    }

    @Test
    public void testMultiplePostsOverSingleConnection() throws Exception {
        final HttpHost target = start();
        final byte[] b1 = new byte[1024];
        final Random rnd = new Random(System.currentTimeMillis());
        rnd.nextBytes(b1);

        final int reqCount = 20;

        this.connMgr.setDefaultMaxPerRoute(1);
        this.connMgr.setMaxTotal(100);

        final Queue<Future<HttpResponse>> queue = new LinkedList<Future<HttpResponse>>();

        for (int i = 0; i < reqCount; i++) {
            final HttpPost httppost = new HttpPost("/echo/stuff");
            httppost.setEntity(new NByteArrayEntity(b1));
            queue.add(this.httpclient.execute(target, httppost, null));
        }

        while (!queue.isEmpty()) {
            final Future<HttpResponse> future = queue.remove();
            final HttpResponse response = future.get();
            Assert.assertNotNull(response);
            Assert.assertEquals(200, response.getStatusLine().getStatusCode());
            final HttpEntity entity = response.getEntity();
            Assert.assertNotNull(entity);
            final byte[] b2 = EntityUtils.toByteArray(entity);
            Assert.assertArrayEquals(b1, b2);
        }
    }

    @Test
    public void testRequestFailure() throws Exception {
        final HttpHost target = start();
        final HttpGet httpget = new HttpGet("/random/2048");
        final HttpAsyncRequestProducer requestProducer = HttpAsyncMethods.create(target, httpget) ;
        final BasicAsyncResponseConsumer responseConsumer = new BasicAsyncResponseConsumer() {

            @Override
            public void onContentReceived(final ContentDecoder decoder, final IOControl ioctrl)
                    throws IOException {
                throw new IOException("Kaboom");
            }

        };
        final Future<HttpResponse> future = this.httpclient.execute(requestProducer, responseConsumer, null);
        try {
            future.get();
            Assert.fail("ExecutionException expected");
        } catch (final ExecutionException ex) {
            final Throwable t = ex.getCause();
            Assert.assertNotNull(t);
            Assert.assertTrue(t instanceof IOException);
            Assert.assertEquals("Kaboom", t.getMessage());
        }
    }

    @Test
    public void testSharedPool() throws Exception {
        final HttpHost target = start();
        final HttpGet httpget = new HttpGet("/random/2048");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatusLine().getStatusCode());


        final CloseableHttpAsyncClient httpclient2 = HttpAsyncClients.custom()
                .setConnectionManager(this.connMgr)
                .setConnectionManagerShared(true)
                .build();
        try {
            httpclient2.start();
            final HttpGet httpget2 = new HttpGet("/random/2048");
            final Future<HttpResponse> future2 = httpclient2.execute(target, httpget2, null);
            final HttpResponse response2 = future2.get();
            Assert.assertNotNull(response2);
            Assert.assertEquals(200, response2.getStatusLine().getStatusCode());

        } finally {
            httpclient2.close();
        }

        final HttpGet httpget3 = new HttpGet("/random/2048");
        final Future<HttpResponse> future3 = this.httpclient.execute(target, httpget3, null);
        final HttpResponse response3 = future3.get();
        Assert.assertNotNull(response3);
        Assert.assertEquals(200, response3.getStatusLine().getStatusCode());
    }

    @Test
    public void testClientCloseloseQuietly() throws Exception {
        final HttpHost target = start();
        final HttpGet httpget = new HttpGet("/random/2048");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatusLine().getStatusCode());

        HttpAsyncClientUtils.closeQuietly(this.httpclient);
        // Close it twice
        HttpAsyncClientUtils.closeQuietly(this.httpclient);
    }

}
