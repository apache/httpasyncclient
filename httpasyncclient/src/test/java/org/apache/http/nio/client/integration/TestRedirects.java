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
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.http.Header;
import org.apache.http.HttpAsyncTestBase;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpInetConnection;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ProtocolException;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.CircularRedirectException;
import org.apache.http.client.CookieStore;
import org.apache.http.client.RedirectException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.cookie.SM;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.impl.nio.DefaultNHttpServerConnectionFactory;
import org.apache.http.localserver.HttpServerNio;
import org.apache.http.localserver.RandomHandler;
import org.apache.http.message.BasicHeader;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.nio.protocol.BasicAsyncRequestHandler;
import org.apache.http.nio.protocol.HttpAsyncExpectationVerifier;
import org.apache.http.nio.protocol.HttpAsyncRequestHandlerRegistry;
import org.apache.http.nio.protocol.HttpAsyncRequestHandlerResolver;
import org.apache.http.nio.protocol.HttpAsyncService;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.nio.reactor.ListenerEndpoint;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Redirection test cases.
 */
public class TestRedirects extends HttpAsyncTestBase {

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

    static class BasicRedirectService implements HttpRequestHandler {

        private final String schemeName;
        private final int statuscode;

        public BasicRedirectService(final String schemeName, int statuscode) {
            super();
            this.schemeName = schemeName;
            this.statuscode = statuscode;
        }

        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {
            HttpInetConnection conn = (HttpInetConnection) context.getAttribute(
                    ExecutionContext.HTTP_CONNECTION);
            ProtocolVersion ver = request.getRequestLine().getProtocolVersion();
            String uri = request.getRequestLine().getUri();
            if (uri.equals("/oldlocation/")) {
                String redirectUrl = this.schemeName + "://localhost:" + conn.getLocalPort() + "/newlocation/";
                response.setStatusLine(ver, this.statuscode);
                response.addHeader(new BasicHeader("Location", redirectUrl));
                response.addHeader(new BasicHeader("Connection", "close"));
            } else if (uri.equals("/newlocation/")) {
                response.setStatusLine(ver, HttpStatus.SC_OK);
                StringEntity entity = new StringEntity("Successful redirect");
                response.setEntity(entity);
            } else {
                response.setStatusLine(ver, HttpStatus.SC_NOT_FOUND);
            }
        }
    }

    static class CircularRedirectService implements HttpRequestHandler {

        public CircularRedirectService() {
            super();
        }

        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {
            ProtocolVersion ver = request.getRequestLine().getProtocolVersion();
            String uri = request.getRequestLine().getUri();
            if (uri.startsWith("/circular-oldlocation")) {
                response.setStatusLine(ver, HttpStatus.SC_MOVED_TEMPORARILY);
                response.addHeader(new BasicHeader("Location", "/circular-location2"));
            } else if (uri.startsWith("/circular-location2")) {
                response.setStatusLine(ver, HttpStatus.SC_MOVED_TEMPORARILY);
                response.addHeader(new BasicHeader("Location", "/circular-oldlocation"));
            } else {
                response.setStatusLine(ver, HttpStatus.SC_NOT_FOUND);
            }
        }
    }

    static class RelativeRedirectService implements HttpRequestHandler {

        public RelativeRedirectService() {
            super();
        }

        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {
            ProtocolVersion ver = request.getRequestLine().getProtocolVersion();
            String uri = request.getRequestLine().getUri();
            if (uri.equals("/oldlocation/")) {
                response.setStatusLine(ver, HttpStatus.SC_MOVED_TEMPORARILY);
                response.addHeader(new BasicHeader("Location", "/relativelocation/"));
            } else if (uri.equals("/relativelocation/")) {
                response.setStatusLine(ver, HttpStatus.SC_OK);
                StringEntity entity = new StringEntity("Successful redirect");
                response.setEntity(entity);
            } else {
                response.setStatusLine(ver, HttpStatus.SC_NOT_FOUND);
            }
        }
    }

    static class RelativeRedirectService2 implements HttpRequestHandler {

        public RelativeRedirectService2() {
            super();
        }

        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {
            ProtocolVersion ver = request.getRequestLine().getProtocolVersion();
            String uri = request.getRequestLine().getUri();
            if (uri.equals("/test/oldlocation")) {
                response.setStatusLine(ver, HttpStatus.SC_MOVED_TEMPORARILY);
                response.addHeader(new BasicHeader("Location", "relativelocation"));
            } else if (uri.equals("/test/relativelocation")) {
                response.setStatusLine(ver, HttpStatus.SC_OK);
                StringEntity entity = new StringEntity("Successful redirect");
                response.setEntity(entity);
            } else {
                response.setStatusLine(ver, HttpStatus.SC_NOT_FOUND);
            }
        }
    }

    static class BogusRedirectService implements HttpRequestHandler {

        private final String schemeName;
        private final String url;
        private final boolean absolute;

        public BogusRedirectService(final String schemeName, final String url, final boolean absolute) {
            super();
            this.schemeName = schemeName;
            this.url = url;
            this.absolute = absolute;
        }

        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {
            HttpInetConnection conn = (HttpInetConnection) context.getAttribute(
                    ExecutionContext.HTTP_CONNECTION);
            String redirectUrl = this.url;
            if (!this.absolute) {
                redirectUrl = this.schemeName + "://localhost:" + conn.getLocalPort() + redirectUrl;
            }

            ProtocolVersion ver = request.getRequestLine().getProtocolVersion();
            String uri = request.getRequestLine().getUri();
            if (uri.equals("/oldlocation/")) {
                response.setStatusLine(ver, HttpStatus.SC_MOVED_TEMPORARILY);
                response.addHeader(new BasicHeader("Location", redirectUrl));
            } else if (uri.equals("/relativelocation/")) {
                response.setStatusLine(ver, HttpStatus.SC_OK);
                StringEntity entity = new StringEntity("Successful redirect");
                response.setEntity(entity);
            } else {
                response.setStatusLine(ver, HttpStatus.SC_NOT_FOUND);
            }
        }
    }

    @Test
    public void testBasicRedirect300() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(
                new BasicRedirectService(getSchemeName(), HttpStatus.SC_MULTIPLE_CHOICES)));
        HttpHost target = start(registry, null);

        HttpContext context = new BasicHttpContext();

        HttpGet httpget = new HttpGet("/oldlocation/");

        Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);

        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);

        Assert.assertEquals(HttpStatus.SC_MULTIPLE_CHOICES, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/oldlocation/", reqWrapper.getRequestLine().getUri());
    }

    @Test
    public void testBasicRedirect301() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(
                new BasicRedirectService(getSchemeName(), HttpStatus.SC_MOVED_PERMANENTLY)));
        HttpHost target = start(registry, null);

        HttpContext context = new BasicHttpContext();

        HttpGet httpget = new HttpGet("/oldlocation/");

        Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);

        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);
        HttpHost host = (HttpHost) context.getAttribute(
                ExecutionContext.HTTP_TARGET_HOST);

        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/newlocation/", reqWrapper.getRequestLine().getUri());
        Assert.assertEquals(target, host);
    }

    @Test
    public void testBasicRedirect302() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(
                new BasicRedirectService(getSchemeName(), HttpStatus.SC_MOVED_TEMPORARILY)));
        HttpHost target = start(registry, null);

        HttpContext context = new BasicHttpContext();

        HttpGet httpget = new HttpGet("/oldlocation/");

        Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);

        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);
        HttpHost host = (HttpHost) context.getAttribute(
                ExecutionContext.HTTP_TARGET_HOST);

        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/newlocation/", reqWrapper.getRequestLine().getUri());
        Assert.assertEquals(target, host);
    }

    @Test
    public void testBasicRedirect302NoLocation() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(new HttpRequestHandler() {

            public void handle(
                    final HttpRequest request,
                    final HttpResponse response,
                    final HttpContext context) throws HttpException, IOException {
                response.setStatusCode(HttpStatus.SC_MOVED_TEMPORARILY);
            }

        }));
        HttpHost target = start(registry, null);

        HttpContext context = new BasicHttpContext();

        HttpGet httpget = new HttpGet("/oldlocation/");

        Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);

        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);
        HttpHost host = (HttpHost) context.getAttribute(
                ExecutionContext.HTTP_TARGET_HOST);

        Assert.assertEquals(HttpStatus.SC_MOVED_TEMPORARILY, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/oldlocation/", reqWrapper.getRequestLine().getUri());
        Assert.assertEquals(target, host);
    }

    @Test
    public void testBasicRedirect303() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(
                new BasicRedirectService(getSchemeName(), HttpStatus.SC_SEE_OTHER)));
        HttpHost target = start(registry, null);

        HttpContext context = new BasicHttpContext();

        HttpGet httpget = new HttpGet("/oldlocation/");

        Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);

        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);
        HttpHost host = (HttpHost) context.getAttribute(
                ExecutionContext.HTTP_TARGET_HOST);

        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/newlocation/", reqWrapper.getRequestLine().getUri());
        Assert.assertEquals(target, host);
    }

    @Test
    public void testBasicRedirect304() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(
                new BasicRedirectService(getSchemeName(), HttpStatus.SC_NOT_MODIFIED)));
        HttpHost target = start(registry, null);

        HttpContext context = new BasicHttpContext();

        HttpGet httpget = new HttpGet("/oldlocation/");

        Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);

        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);

        Assert.assertEquals(HttpStatus.SC_NOT_MODIFIED, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/oldlocation/", reqWrapper.getRequestLine().getUri());
    }

    @Test
    public void testBasicRedirect305() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(
                new BasicRedirectService(getSchemeName(), HttpStatus.SC_USE_PROXY)));
        HttpHost target = start(registry, null);

        HttpContext context = new BasicHttpContext();

        HttpGet httpget = new HttpGet("/oldlocation/");

        Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);

        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);

        Assert.assertEquals(HttpStatus.SC_USE_PROXY, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/oldlocation/", reqWrapper.getRequestLine().getUri());
    }

    @Test
    public void testBasicRedirect307() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(
                new BasicRedirectService(getSchemeName(), HttpStatus.SC_TEMPORARY_REDIRECT)));
        HttpHost target = start(registry, null);

        HttpContext context = new BasicHttpContext();

        HttpGet httpget = new HttpGet("/oldlocation/");

        Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);

        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);
        HttpHost host = (HttpHost) context.getAttribute(
                ExecutionContext.HTTP_TARGET_HOST);

        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/newlocation/", reqWrapper.getRequestLine().getUri());
        Assert.assertEquals(target, host);
    }

    @Test(expected=ExecutionException.class)
    public void testMaxRedirectCheck() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(new CircularRedirectService()));
        HttpHost target = start(registry, null);

        this.httpclient.getParams().setBooleanParameter(ClientPNames.ALLOW_CIRCULAR_REDIRECTS, true);
        this.httpclient.getParams().setIntParameter(ClientPNames.MAX_REDIRECTS, 5);

        HttpGet httpget = new HttpGet("/circular-oldlocation/");
        try {
            Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
            future.get();
        } catch (ExecutionException e) {
            Assert.assertTrue(e.getCause() instanceof RedirectException);
            throw e;
        }
    }

    @Test(expected=ExecutionException.class)
    public void testCircularRedirect() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(new CircularRedirectService()));
        HttpHost target = start(registry, null);

        this.httpclient.getParams().setBooleanParameter(ClientPNames.ALLOW_CIRCULAR_REDIRECTS, false);

        HttpGet httpget = new HttpGet("/circular-oldlocation/");

        try {
            Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
            future.get();
        } catch (ExecutionException e) {
            Assert.assertTrue(e.getCause() instanceof CircularRedirectException);
            throw e;
        }
    }

    @Test
    public void testPostNoRedirect() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(
                new BasicRedirectService(getSchemeName(), HttpStatus.SC_MOVED_TEMPORARILY)));
        HttpHost target = start(registry, null);

        HttpContext context = new BasicHttpContext();

        HttpPost httppost = new HttpPost("/oldlocation/");
        httppost.setEntity(new NStringEntity("stuff"));

        Future<HttpResponse> future = this.httpclient.execute(target, httppost, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);

        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);

        Assert.assertEquals(HttpStatus.SC_MOVED_TEMPORARILY, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/oldlocation/", reqWrapper.getRequestLine().getUri());
        Assert.assertEquals("POST", reqWrapper.getRequestLine().getMethod());
    }

    @Test
    public void testPostRedirectSeeOther() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(
                new BasicRedirectService(getSchemeName(), HttpStatus.SC_SEE_OTHER)));
        HttpHost target = start(registry, null);

        HttpContext context = new BasicHttpContext();

        HttpPost httppost = new HttpPost("/oldlocation/");
        httppost.setEntity(new NStringEntity("stuff"));

        Future<HttpResponse> future = this.httpclient.execute(target, httppost, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);

        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);

        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/newlocation/", reqWrapper.getRequestLine().getUri());
        Assert.assertEquals("GET", reqWrapper.getRequestLine().getMethod());
    }

    @Test
    public void testRelativeRedirect() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(new RelativeRedirectService()));
        HttpHost target = start(registry, null);

        HttpContext context = new BasicHttpContext();

        this.httpclient.getParams().setBooleanParameter(
                ClientPNames.REJECT_RELATIVE_REDIRECT, false);
        HttpGet httpget = new HttpGet("/oldlocation/");

        Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);

        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);
        HttpHost host = (HttpHost) context.getAttribute(
                ExecutionContext.HTTP_TARGET_HOST);

        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/relativelocation/", reqWrapper.getRequestLine().getUri());
        Assert.assertEquals(target, host);
    }

    @Test
    public void testRelativeRedirect2() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(new RelativeRedirectService2()));
        HttpHost target = start(registry, null);

        HttpContext context = new BasicHttpContext();

        this.httpclient.getParams().setBooleanParameter(
                ClientPNames.REJECT_RELATIVE_REDIRECT, false);
        HttpGet httpget = new HttpGet("/test/oldlocation");

        Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);

        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);
        HttpHost host = (HttpHost) context.getAttribute(
                ExecutionContext.HTTP_TARGET_HOST);

        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/test/relativelocation", reqWrapper.getRequestLine().getUri());
        Assert.assertEquals(target, host);
    }

    @Test(expected=ExecutionException.class)
    public void testRejectRelativeRedirect() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(new RelativeRedirectService()));
        HttpHost target = start(registry, null);

        this.httpclient.getParams().setBooleanParameter(
                ClientPNames.REJECT_RELATIVE_REDIRECT, true);
        HttpGet httpget = new HttpGet("/oldlocation/");

        try {
            Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
            future.get();
        } catch (ExecutionException e) {
            Assert.assertTrue(e.getCause() instanceof ProtocolException);
            throw e;
        }
    }

    @Test(expected=ExecutionException.class)
    public void testRejectBogusRedirectLocation() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(
                new BogusRedirectService(getSchemeName(), "xxx://bogus", true)));
        HttpHost target = start(registry, null);

        HttpGet httpget = new HttpGet("/oldlocation/");

        try {
            Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
            future.get();
        } catch (ExecutionException e) {
            Assert.assertTrue(e.getCause() instanceof HttpException);
            throw e;
        }
    }

    @Test(expected=ExecutionException.class)
    public void testRejectInvalidRedirectLocation() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(
                new BogusRedirectService(getSchemeName(), "/newlocation/?p=I have spaces", false)));
        HttpHost target = start(registry, null);

        HttpGet httpget = new HttpGet("/oldlocation/");
        try {
            Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
            future.get();
        } catch (ExecutionException e) {
            Assert.assertTrue(e.getCause() instanceof ProtocolException);
            throw e;
        }
    }

    @Test
    public void testRedirectWithCookie() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(
                new BasicRedirectService(getSchemeName(), HttpStatus.SC_MOVED_TEMPORARILY)));
        HttpHost target = start(registry, null);

        CookieStore cookieStore = new BasicCookieStore();
        this.httpclient.setCookieStore(cookieStore);

        BasicClientCookie cookie = new BasicClientCookie("name", "value");
        cookie.setDomain(target.getHostName());
        cookie.setPath("/");

        cookieStore.addCookie(cookie);

        HttpContext context = new BasicHttpContext();
        HttpGet httpget = new HttpGet("/oldlocation/");

        Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);

        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);

        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/newlocation/", reqWrapper.getRequestLine().getUri());

        Header[] headers = reqWrapper.getHeaders(SM.COOKIE);
        Assert.assertEquals("There can only be one (cookie)", 1, headers.length);
    }

    @Test
    public void testDefaultHeadersRedirect() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("*", new BasicAsyncRequestHandler(
                new BasicRedirectService(getSchemeName(), HttpStatus.SC_MOVED_TEMPORARILY)));
        HttpHost target = start(registry, null);

        HttpContext context = new BasicHttpContext();

        List<Header> defaultHeaders = new ArrayList<Header>(1);
        defaultHeaders.add(new BasicHeader(HTTP.USER_AGENT, "my-test-client"));

        this.httpclient.getParams().setParameter(ClientPNames.DEFAULT_HEADERS, defaultHeaders);

        HttpGet httpget = new HttpGet("/oldlocation/");

        Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);


        HttpRequest reqWrapper = (HttpRequest) context.getAttribute(
                ExecutionContext.HTTP_REQUEST);

        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        Assert.assertEquals("/newlocation/", reqWrapper.getRequestLine().getUri());

        Header header = reqWrapper.getFirstHeader(HTTP.USER_AGENT);
        Assert.assertEquals("my-test-client", header.getValue());
    }
    
    static class CrossSiteRedirectService implements HttpRequestHandler {

        private final HttpHost host;

        public CrossSiteRedirectService(final HttpHost host) {
            super();
            this.host = host;
        }

        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {
            ProtocolVersion ver = request.getRequestLine().getProtocolVersion();
            String location;
            try {
                URIBuilder uribuilder = new URIBuilder(request.getRequestLine().getUri()); 
                uribuilder.setScheme(this.host.getSchemeName());
                uribuilder.setHost(this.host.getHostName());
                uribuilder.setPort(this.host.getPort());
                uribuilder.setPath("/random/1024");
                location = uribuilder.build().toASCIIString();
            } catch (URISyntaxException ex) {
                throw new ProtocolException("Invalid request URI", ex);
            }
            response.setStatusLine(ver, HttpStatus.SC_TEMPORARY_REDIRECT);
            response.addHeader(new BasicHeader("Location", location));
        }
    }

    @Test
    public void testCrossSiteRedirect() throws Exception {
        HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();
        registry.register("/random/*", new BasicAsyncRequestHandler(
                new RandomHandler()));
        HttpHost redirectTarget = start(registry, null);

        HttpAsyncRequestHandlerRegistry registry2 = new HttpAsyncRequestHandlerRegistry();
        registry2.register("/redirect/*", new BasicAsyncRequestHandler(
                new CrossSiteRedirectService(redirectTarget)));
        HttpServerNio secondServer = new HttpServerNio(createServerConnectionFactory(this.serverParams));
        secondServer.setExceptionHandler(new SimpleIOReactorExceptionHandler());
        HttpAsyncService serviceHandler = new HttpAsyncService(
                this.serverHttpProc,
                new DefaultConnectionReuseStrategy(),
                new DefaultHttpResponseFactory(),
                registry2,
                null,
                this.serverParams);
        secondServer.start(serviceHandler);
        try {
            ListenerEndpoint endpoint2 = secondServer.getListenerEndpoint();
            endpoint2.waitFor();

            Assert.assertEquals("Test server status", IOReactorStatus.ACTIVE, secondServer.getStatus());
            InetSocketAddress address2 = (InetSocketAddress) endpoint2.getAddress();
            HttpHost initialTarget = new HttpHost("localhost", address2.getPort(), getSchemeName());

            Queue<Future<HttpResponse>> queue = new ConcurrentLinkedQueue<Future<HttpResponse>>();
            for (int i = 0; i < 4; i++) {
                HttpContext context = new BasicHttpContext();
                HttpGet httpget = new HttpGet("/redirect/anywhere");
                queue.add(this.httpclient.execute(initialTarget, httpget, context, null));
            }
            while (!queue.isEmpty()) {
                Future<HttpResponse> future = queue.remove();
                HttpResponse response = future.get();
                Assert.assertNotNull(response);
                Assert.assertEquals(200, response.getStatusLine().getStatusCode());
            }
        } finally {
            this.server.shutdown();
        }
    }

}
