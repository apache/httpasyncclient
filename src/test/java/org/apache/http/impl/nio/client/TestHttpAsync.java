package org.apache.http.impl.nio.client;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.nio.conn.BasicIOSessionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.localserver.ServerTestBase;
import org.apache.http.nio.client.AsyncHttpClient;
import org.apache.http.nio.client.HttpExchange;
import org.apache.http.nio.entity.NByteArrayEntity;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestHttpAsync extends ServerTestBase {

    private HttpHost target;
    private BasicIOSessionManager sessionManager;
    private AsyncHttpClient httpclient;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        this.localServer.registerDefaultHandlers();
        int port = this.localServer.getServiceAddress().getPort();
        this.target = new HttpHost("localhost", port);
        ConnectingIOReactor ioReactor = new DefaultConnectingIOReactor(2, new BasicHttpParams());
        this.sessionManager = new BasicIOSessionManager(ioReactor);
        this.httpclient = new BasicAsyncHttpClient(ioReactor, this.sessionManager, null);
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
        HttpExchange httpexg = this.httpclient.execute(this.target, httpget);
        HttpResponse response = httpexg.awaitResponse();
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

        HttpExchange httpexg = this.httpclient.execute(this.target, httppost);
        HttpResponse response = httpexg.awaitResponse();
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

        Queue<HttpExchange> queue = new LinkedList<HttpExchange>();

        for (int i = 0; i < reqCount; i++) {
            HttpPost httppost = new HttpPost("/echo/stuff");
            httppost.setEntity(new NByteArrayEntity(b1));
            queue.add(this.httpclient.execute(this.target, httppost));
        }

        while (!queue.isEmpty()) {
            HttpExchange httpexg = queue.remove();
            HttpResponse response = httpexg.awaitResponse();
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

        Queue<HttpExchange> queue = new LinkedList<HttpExchange>();

        for (int i = 0; i < reqCount; i++) {
            HttpPost httppost = new HttpPost("/echo/stuff");
            httppost.setEntity(new NByteArrayEntity(b1));
            queue.add(this.httpclient.execute(this.target, httppost));
        }

        while (!queue.isEmpty()) {
            HttpExchange httpexg = queue.remove();
            HttpResponse response = httpexg.awaitResponse();
            Assert.assertNotNull(response);
            Assert.assertEquals(200, response.getStatusLine().getStatusCode());
            HttpEntity entity = response.getEntity();
            Assert.assertNotNull(entity);
            byte[] b2 = EntityUtils.toByteArray(entity);
            Assert.assertArrayEquals(b1, b2);
        }
    }

}
