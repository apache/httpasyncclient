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
import java.net.URL;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.nio.conn.PoolingClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.localserver.LocalTestServer;
import org.apache.http.localserver.ServerTestBase;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.conn.scheme.Scheme;
import org.apache.http.nio.conn.scheme.SchemeRegistry;
import org.apache.http.nio.conn.ssl.SSLLayeringStrategy;
import org.apache.http.nio.entity.NByteArrayEntity;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestHttpsAsync extends ServerTestBase {

    private SSLContext serverSSLContext;
    private SSLContext clientSSLContext;
    private HttpHost target;
    private PoolingClientConnectionManager sessionManager;
    private HttpAsyncClient httpclient;

    private KeyManagerFactory createKeyManagerFactory() throws NoSuchAlgorithmException {
        String algo = KeyManagerFactory.getDefaultAlgorithm();
        try {
            return KeyManagerFactory.getInstance(algo);
        } catch (NoSuchAlgorithmException ex) {
            return KeyManagerFactory.getInstance("SunX509");
        }
    }

    private TrustManagerFactory createTrustManagerFactory() throws NoSuchAlgorithmException {
        String algo = TrustManagerFactory.getDefaultAlgorithm();
        try {
            return TrustManagerFactory.getInstance(algo);
        } catch (NoSuchAlgorithmException ex) {
            return TrustManagerFactory.getInstance("SunX509");
        }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();

        ClassLoader cl = getClass().getClassLoader();
        URL url = cl.getResource("test.keystore");
        KeyStore keystore  = KeyStore.getInstance("jks");
        char[] pwd = "nopassword".toCharArray();
        keystore.load(url.openStream(), pwd);

        TrustManagerFactory tmf = createTrustManagerFactory();
        tmf.init(keystore);
        TrustManager[] tm = tmf.getTrustManagers();

        KeyManagerFactory kmfactory = createKeyManagerFactory();
        kmfactory.init(keystore, pwd);
        KeyManager[] km = kmfactory.getKeyManagers();

        this.serverSSLContext = SSLContext.getInstance("TLS");
        this.serverSSLContext.init(km, tm, null);

        this.clientSSLContext = SSLContext.getInstance("TLS");
        this.clientSSLContext.init(null, tm, null);

        this.localServer = new LocalTestServer(this.serverSSLContext);
        this.localServer.registerDefaultHandlers();
        this.localServer.start();
        int port = this.localServer.getServiceAddress().getPort();
        this.target = new HttpHost("localhost", port, "https");

        ConnectingIOReactor ioReactor = new DefaultConnectingIOReactor(2, new BasicHttpParams());
        SchemeRegistry schemeRegistry = new SchemeRegistry();
        schemeRegistry.register(new Scheme("http", 80, null));
        schemeRegistry.register(new Scheme("https", 443, new SSLLayeringStrategy(this.clientSSLContext)));
        this.sessionManager = new PoolingClientConnectionManager(ioReactor, schemeRegistry);
        this.httpclient = new DefaultHttpAsyncClient(ioReactor, this.sessionManager);
    }

    @After
    public void tearDown() throws Exception {
        this.httpclient.shutdown();
        super.tearDown();
    }

    @Test
    public void testSingleGet() throws Exception {
        this.httpclient.start();
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

        this.httpclient.start();

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

        this.sessionManager.setDefaultMaxPerHost(reqCount);
        this.sessionManager.setTotalMax(100);
        this.httpclient.start();

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

        this.sessionManager.setDefaultMaxPerHost(1);
        this.sessionManager.setTotalMax(100);
        this.httpclient.start();

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
        this.httpclient.start();

        HttpGet httpget = new HttpGet("/random/2048");
        BasicHttpAsyncRequestProducer requestProducer = new BasicHttpAsyncRequestProducer(this.target, httpget) ;
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
