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
package org.apache.http.impl.nio.client;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.localserver.AsyncHttpTestBase;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.HttpAsyncRequestProducer;
import org.apache.http.nio.client.methods.BasicHttpAsyncResponseConsumer;
import org.apache.http.nio.client.methods.HttpAsyncMethods;
import org.apache.http.nio.entity.NByteArrayEntity;
import org.apache.http.util.EntityUtils;
import org.junit.Assert;
import org.junit.Test;

public class TestHttpAsync extends AsyncHttpTestBase {

    @Test
    public void testSingleGet() throws Exception {
        HttpGet httpget = new HttpGet("/random/2048");
        Future<HttpResponse> future = this.httpclient.execute(this.target, httpget, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatusLine().getStatusCode());
    }

    @Test
    public void testSinglePost() throws Exception {
        byte[] b1 = new byte[1024];
        Random rnd = new Random(System.currentTimeMillis());
        rnd.nextBytes(b1);

        HttpPost httppost = new HttpPost("/echo/stuff");
        httppost.setEntity(new NByteArrayEntity(b1));

        Future<HttpResponse> future = this.httpclient.execute(this.target, httppost, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatusLine().getStatusCode());
        HttpEntity entity = response.getEntity();
        Assert.assertNotNull(entity);
        byte[] b2 = EntityUtils.toByteArray(entity);
        Assert.assertArrayEquals(b1, b2);
    }

    @Test
    public void testMultiplePostsOverMultipleConnections() throws Exception {
        byte[] b1 = new byte[1024];
        Random rnd = new Random(System.currentTimeMillis());
        rnd.nextBytes(b1);

        int reqCount = 20;

        this.sessionManager.setDefaultMaxPerRoute(reqCount);
        this.sessionManager.setMaxTotal(100);

        Queue<Future<HttpResponse>> queue = new LinkedList<Future<HttpResponse>>();

        for (int i = 0; i < reqCount; i++) {
            HttpPost httppost = new HttpPost("/echo/stuff");
            httppost.setEntity(new NByteArrayEntity(b1));
            queue.add(this.httpclient.execute(this.target, httppost, null));
        }

        while (!queue.isEmpty()) {
            Future<HttpResponse> future = queue.remove();
            HttpResponse response = future.get();
            Assert.assertNotNull(response);
            Assert.assertEquals(200, response.getStatusLine().getStatusCode());
            HttpEntity entity = response.getEntity();
            Assert.assertNotNull(entity);
            byte[] b2 = EntityUtils.toByteArray(entity);
            Assert.assertArrayEquals(b1, b2);
        }
    }

    @Test
    public void testMultiplePostsOverSingleConnection() throws Exception {
        byte[] b1 = new byte[1024];
        Random rnd = new Random(System.currentTimeMillis());
        rnd.nextBytes(b1);

        int reqCount = 20;

        this.sessionManager.setDefaultMaxPerRoute(1);
        this.sessionManager.setMaxTotal(100);

        Queue<Future<HttpResponse>> queue = new LinkedList<Future<HttpResponse>>();

        for (int i = 0; i < reqCount; i++) {
            HttpPost httppost = new HttpPost("/echo/stuff");
            httppost.setEntity(new NByteArrayEntity(b1));
            queue.add(this.httpclient.execute(this.target, httppost, null));
        }

        while (!queue.isEmpty()) {
            Future<HttpResponse> future = queue.remove();
            HttpResponse response = future.get();
            Assert.assertNotNull(response);
            Assert.assertEquals(200, response.getStatusLine().getStatusCode());
            HttpEntity entity = response.getEntity();
            Assert.assertNotNull(entity);
            byte[] b2 = EntityUtils.toByteArray(entity);
            Assert.assertArrayEquals(b1, b2);
        }
    }

    @Test
    public void testRequestFailure() throws Exception {
        HttpGet httpget = new HttpGet("/random/2048");
        HttpAsyncRequestProducer requestProducer = HttpAsyncMethods.create(this.target, httpget) ;
        BasicHttpAsyncResponseConsumer responseConsumer = new BasicHttpAsyncResponseConsumer() {

            @Override
            public void consumeContent(final ContentDecoder decoder, final IOControl ioctrl)
                    throws IOException {
                throw new IOException("Kaboom");
            }

        };
        Future<HttpResponse> future = this.httpclient.execute(requestProducer, responseConsumer, null);
        try {
            future.get();
            Assert.fail("ExecutionException expected");
        } catch (ExecutionException ex) {
            Throwable t = ex.getCause();
            Assert.assertNotNull(t);
            Assert.assertTrue(t instanceof IOException);
            Assert.assertEquals("Kaboom", t.getMessage());
        }
    }

}
