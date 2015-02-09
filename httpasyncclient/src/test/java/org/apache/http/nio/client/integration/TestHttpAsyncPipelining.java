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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpPipeliningClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.localserver.AbstractAsyncTest;
import org.apache.http.localserver.EchoHandler;
import org.apache.http.localserver.RandomHandler;
import org.apache.http.nio.client.methods.HttpAsyncMethods;
import org.apache.http.nio.protocol.BasicAsyncRequestHandler;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TestHttpAsyncPipelining extends AbstractAsyncTest {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> protocols() {
        return Arrays.asList(new Object[][]{
                {ProtocolScheme.http},
                {ProtocolScheme.https},
        });
    }

    protected CloseableHttpPipeliningClient httpclient;

    public TestHttpAsyncPipelining(final ProtocolScheme scheme) {
        super(scheme);
    }

    @Before @Override
    public void setUp() throws Exception {
        super.setUp();
        this.serverBootstrap.registerHandler("/echo/*", new BasicAsyncRequestHandler(new EchoHandler()));
        this.serverBootstrap.registerHandler("/random/*", new BasicAsyncRequestHandler(new RandomHandler()));

        this.httpclient = HttpAsyncClients.createPipelining(this.connMgr);
    }

    @After @Override
    public void shutDown() throws Exception {
        if (this.httpclient != null) {
            this.httpclient.close();
        }
        super.shutDown();
    }

    public HttpHost start() throws Exception {
        final HttpHost serverEndpoint = startServer();

        this.connMgr.setDefaultMaxPerRoute(1);
        this.httpclient.start();

        return serverEndpoint;
    }

    @Test
    public void testPipelinedGets() throws Exception {
        final HttpHost target = start();

        final Queue<Future<List<HttpResponse>>> queue = new ConcurrentLinkedQueue<Future<List<HttpResponse>>>();
        for (int i = 0; i < 10; i++) {
            final HttpRequest httpget1 = new HttpGet("/random/512");
            final HttpRequest httpget2 = new HttpGet("/random/1024");
            final HttpRequest httpget3 = new HttpGet("/random/2048");
            queue.add(this.httpclient.execute(target, Arrays.asList(httpget1, httpget2, httpget3), null));
        }

        while (!queue.isEmpty()) {
            final Future<List<HttpResponse>> future = queue.remove();
            final List<HttpResponse> responses = future.get();
            Assert.assertNotNull(responses);
            Assert.assertEquals(3, responses.size());
            final HttpResponse response1 = responses.get(0);
            Assert.assertEquals(200, response1.getStatusLine().getStatusCode());
            final byte[] bytes1 = EntityUtils.toByteArray(response1.getEntity());
            Assert.assertNotNull(bytes1);
            Assert.assertEquals(512, bytes1.length);
            final HttpResponse response2 = responses.get(1);
            Assert.assertEquals(200, response2.getStatusLine().getStatusCode());
            final byte[] bytes2 = EntityUtils.toByteArray(response2.getEntity());
            Assert.assertNotNull(bytes2);
            Assert.assertEquals(1024, bytes2.length);
            final HttpResponse response3 = responses.get(2);
            Assert.assertEquals(200, response3.getStatusLine().getStatusCode());
            final byte[] bytes3 = EntityUtils.toByteArray(response3.getEntity());
            Assert.assertNotNull(bytes3);
            Assert.assertEquals(2048, bytes3.length);
        }

    }

    @Test
    public void testPipelinedPostsAndGets() throws Exception {
        final HttpHost target = start();

        final Queue<Future<List<HttpResponse>>> queue = new ConcurrentLinkedQueue<Future<List<HttpResponse>>>();
        for (int i = 0; i < 10; i++) {
            final HttpEntityEnclosingRequest httppost1 = new HttpPost("/echo/");
            httppost1.setEntity(new StringEntity("this and that"));
            final HttpRequest httpget2 = new HttpGet("/echo/");
            final HttpEntityEnclosingRequest httppost3 = new HttpPost("/echo/");
            httppost3.setEntity(new StringEntity("all sorts of things"));
            queue.add(this.httpclient.execute(target, Arrays.asList(httppost1, httpget2, httppost3), null));
        }

        while (!queue.isEmpty()) {
            final Future<List<HttpResponse>> future = queue.remove();
            final List<HttpResponse> responses = future.get();
            Assert.assertNotNull(responses);
            Assert.assertEquals(3, responses.size());
            final HttpResponse response1 = responses.get(0);
            Assert.assertEquals(200, response1.getStatusLine().getStatusCode());
            final String s1 = EntityUtils.toString(response1.getEntity());
            Assert.assertNotNull(s1);
            Assert.assertEquals("this and that", s1);
            final HttpResponse response2 = responses.get(1);
            Assert.assertEquals(200, response2.getStatusLine().getStatusCode());
            final String s2 = EntityUtils.toString(response2.getEntity());
            Assert.assertNotNull(s2);
            Assert.assertEquals("", s2);
            final HttpResponse response3 = responses.get(2);
            Assert.assertEquals(200, response3.getStatusLine().getStatusCode());
            final String s3 = EntityUtils.toString(response3.getEntity());
            Assert.assertNotNull(s3);
            Assert.assertEquals("all sorts of things", s3);
        }

    }

    @Test @Ignore //TODO: re-enable afyer upgrade to HttpCore 4.4.1
    public void testPipelinedRequestsUnexpectedConnectionClosure() throws Exception {
        final HttpHost target = start();

        for (int i = 0; i < 20; i++) {
            final HttpAsyncRequestProducer p1 = HttpAsyncMethods.create(target, new HttpGet("/random/512"));
            final HttpAsyncRequestProducer p2 = HttpAsyncMethods.create(target, new HttpGet("/pampa"));
            final HttpAsyncRequestProducer p3 = HttpAsyncMethods.create(target, new HttpGet("/random/512"));
            final HttpAsyncRequestProducer p4 = HttpAsyncMethods.create(target, new HttpGet("/random/512"));
            final List<HttpAsyncRequestProducer> requestProducers = new ArrayList<HttpAsyncRequestProducer>();
            requestProducers.add(p1);
            requestProducers.add(p2);
            requestProducers.add(p3);
            requestProducers.add(p4);

            final HttpAsyncResponseConsumer<HttpResponse> c1 = HttpAsyncMethods.createConsumer();
            final HttpAsyncResponseConsumer<HttpResponse> c2 = HttpAsyncMethods.createConsumer();
            final HttpAsyncResponseConsumer<HttpResponse> c3 = HttpAsyncMethods.createConsumer();
            final HttpAsyncResponseConsumer<HttpResponse> c4 = HttpAsyncMethods.createConsumer();
            final List<HttpAsyncResponseConsumer<HttpResponse>> responseConsumers = new ArrayList<HttpAsyncResponseConsumer<HttpResponse>>();
            responseConsumers.add(c1);
            responseConsumers.add(c2);
            responseConsumers.add(c3);
            responseConsumers.add(c4);

            final Future<List<HttpResponse>> future = this.httpclient.execute(
                    target,
                    requestProducers,
                    responseConsumers,
                    null, null);
            try {
                future.get();
            } catch (ExecutionException ex) {
                final Throwable cause = ex.getCause();
                Assert.assertTrue(cause instanceof ConnectionClosedException);
            }
            Assert.assertTrue(c1.isDone());
            Assert.assertNotNull(c1.getResult());
            Assert.assertTrue(c2.isDone());
            Assert.assertNotNull(c2.getResult());
            Assert.assertTrue(c3.isDone());
            Assert.assertNull(c3.getResult());
            Assert.assertTrue(c4.isDone());
            Assert.assertNull(c4.getResult());
        }

    }

}
