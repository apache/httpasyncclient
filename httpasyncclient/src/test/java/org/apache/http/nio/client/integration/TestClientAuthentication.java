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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.http.Consts;
import org.apache.http.HttpAsyncTestBase;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.ProtocolVersion;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.TargetAuthenticationStrategy;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.impl.nio.DefaultNHttpServerConnectionFactory;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.localserver.BasicAuthTokenExtractor;
import org.apache.http.localserver.RequestBasicAuth;
import org.apache.http.localserver.ResponseBasicUnauthorized;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.entity.NByteArrayEntity;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.nio.protocol.BasicAsyncRequestHandler;
import org.apache.http.nio.protocol.BasicAsyncResponseProducer;
import org.apache.http.nio.protocol.HttpAsyncExchange;
import org.apache.http.nio.protocol.HttpAsyncExpectationVerifier;
import org.apache.http.nio.protocol.HttpAsyncRequestHandlerMapper;
import org.apache.http.nio.protocol.HttpAsyncService;
import org.apache.http.nio.protocol.UriHttpAsyncRequestHandlerMapper;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.nio.reactor.ListenerEndpoint;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestClientAuthentication extends HttpAsyncTestBase {

    @Before
    public void setUp() throws Exception {
        initServer();
        initConnectionManager();
    }

    @After
    public void tearDown() throws Exception {
        shutDownClient();
        shutDownServer();
    }

    @Override
    public void initServer() throws Exception {
        super.initServer();
        this.serverHttpProc = new ImmutableHttpProcessor(
                new HttpRequestInterceptor[] {
                        new RequestBasicAuth()
                },
                new HttpResponseInterceptor[] {
                        new ResponseDate(),
                        new ResponseServer(),
                        new ResponseContent(),
                        new ResponseConnControl(),
                        new ResponseBasicUnauthorized()
                }
        );
    }

    @Override
    protected NHttpConnectionFactory<DefaultNHttpServerConnection> createServerConnectionFactory(
            final ConnectionConfig config) throws Exception {
        return new DefaultNHttpServerConnectionFactory(config);
    }

    @Override
    protected String getSchemeName() {
        return "http";
    }

    private HttpHost start(
            final HttpAsyncRequestHandlerMapper requestHandlerResolver,
            final HttpAsyncExpectationVerifier expectationVerifier) throws Exception {
        final HttpAsyncService serviceHandler = new HttpAsyncService(
                this.serverHttpProc,
                DefaultConnectionReuseStrategy.INSTANCE,
                DefaultHttpResponseFactory.INSTANCE,
                requestHandlerResolver,
                expectationVerifier);
        this.server.start(serviceHandler);
        this.httpclient.start();

        final ListenerEndpoint endpoint = this.server.getListenerEndpoint();
        endpoint.waitFor();

        Assert.assertEquals("Test server status", IOReactorStatus.ACTIVE, this.server.getStatus());
        final InetSocketAddress address = (InetSocketAddress) endpoint.getAddress();
        final HttpHost target = new HttpHost("localhost", address.getPort(), getSchemeName());
        return target;
    }

    static class AuthHandler implements HttpRequestHandler {

        private final boolean keepAlive;

        AuthHandler(final boolean keepAlive) {
            super();
            this.keepAlive = keepAlive;
        }

        AuthHandler() {
            this(true);
        }

        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {
            final String creds = (String) context.getAttribute("creds");
            if (creds == null || !creds.equals("test:test")) {
                response.setStatusCode(HttpStatus.SC_UNAUTHORIZED);
            } else {
                response.setStatusCode(HttpStatus.SC_OK);
                final NStringEntity entity = new NStringEntity("success", Consts.ASCII);
                response.setEntity(entity);
            }
            response.setHeader(HTTP.CONN_DIRECTIVE,
                    this.keepAlive ? HTTP.CONN_KEEP_ALIVE : HTTP.CONN_CLOSE);
        }

    }

    static class TestTargetAuthenticationStrategy extends TargetAuthenticationStrategy {

        private int count;

        public TestTargetAuthenticationStrategy() {
            super();
            this.count = 0;
        }

        @Override
        public boolean isAuthenticationRequested(
                final HttpHost authhost,
                final HttpResponse response,
                final HttpContext context) {
            final boolean res = super.isAuthenticationRequested(authhost, response, context);
            if (res == true) {
                synchronized (this) {
                    this.count++;
                }
            }
            return res;
        }

        public int getCount() {
            synchronized (this) {
                return this.count;
            }
        }

    }

    static class AuthExpectationVerifier implements HttpAsyncExpectationVerifier {

        private final BasicAuthTokenExtractor authTokenExtractor;

        public AuthExpectationVerifier() {
            super();
            this.authTokenExtractor = new BasicAuthTokenExtractor();
        }

        public void verify(
                final HttpAsyncExchange httpexchange,
                final HttpContext context) throws HttpException, IOException {
            final HttpRequest request = httpexchange.getRequest();
            ProtocolVersion ver = request.getRequestLine().getProtocolVersion();
            if (!ver.lessEquals(HttpVersion.HTTP_1_1)) {
                ver = HttpVersion.HTTP_1_1;
            }
            final String creds = this.authTokenExtractor.extract(request);
            if (creds == null || !creds.equals("test:test")) {
                final HttpResponse response = new BasicHttpResponse(ver, HttpStatus.SC_UNAUTHORIZED, "UNAUTHORIZED");
                httpexchange.submitResponse(new BasicAsyncResponseProducer(response));
            } else {
                httpexchange.submitResponse();
            }
        }

    }

    static class TestCredentialsProvider implements CredentialsProvider {

        private final Credentials creds;
        private AuthScope authscope;

        TestCredentialsProvider(final Credentials creds) {
            super();
            this.creds = creds;
        }

        public void clear() {
        }

        public Credentials getCredentials(final AuthScope authscope) {
            this.authscope = authscope;
            return this.creds;
        }

        public void setCredentials(final AuthScope authscope, final Credentials credentials) {
        }

        public AuthScope getAuthScope() {
            return this.authscope;
        }

    }

    @Test
    public void testBasicAuthenticationNoCreds() throws Exception {
        final UriHttpAsyncRequestHandlerMapper registry = new UriHttpAsyncRequestHandlerMapper();
        registry.register("*", new BasicAsyncRequestHandler(new AuthHandler()));

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(null);
        this.httpclient = HttpAsyncClients.custom()
            .setConnectionManager(this.connMgr)
            .setDefaultCredentialsProvider(credsProvider)
            .build();

        final HttpHost target = start(registry, null);

        final HttpGet httpget = new HttpGet("/");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, response.getStatusLine().getStatusCode());
        final AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationFailure() throws Exception {
        final UriHttpAsyncRequestHandlerMapper registry = new UriHttpAsyncRequestHandlerMapper();
        registry.register("*", new BasicAsyncRequestHandler(new AuthHandler()));

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "all-wrong"));
        this.httpclient = HttpAsyncClients.custom()
            .setConnectionManager(this.connMgr)
            .setDefaultCredentialsProvider(credsProvider)
            .build();

        final HttpHost target = start(registry, null);

        final HttpGet httpget = new HttpGet("/");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, response.getStatusLine().getStatusCode());
        final AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationSuccess() throws Exception {
        final UriHttpAsyncRequestHandlerMapper registry = new UriHttpAsyncRequestHandlerMapper();
        registry.register("*", new BasicAsyncRequestHandler(new AuthHandler()));

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));
        this.httpclient = HttpAsyncClients.custom()
            .setConnectionManager(this.connMgr)
            .setDefaultCredentialsProvider(credsProvider)
            .build();

        final HttpHost target = start(registry, null);

        final HttpGet httpget = new HttpGet("/");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        final AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationSuccessNonPersistentConnection() throws Exception {
        final UriHttpAsyncRequestHandlerMapper registry = new UriHttpAsyncRequestHandlerMapper();
        registry.register("*", new BasicAsyncRequestHandler(new AuthHandler(false)));

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));
        this.httpclient = HttpAsyncClients.custom()
            .setConnectionManager(this.connMgr)
            .setDefaultCredentialsProvider(credsProvider)
            .build();

        final HttpHost target = start(registry, null);

        final HttpGet httpget = new HttpGet("/");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        final AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationSuccessWithNonRepeatableExpectContinue() throws Exception {
        final UriHttpAsyncRequestHandlerMapper registry = new UriHttpAsyncRequestHandlerMapper();
        registry.register("*", new BasicAsyncRequestHandler(new AuthHandler()));
        final AuthExpectationVerifier expectationVerifier = new AuthExpectationVerifier();

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));

        this.httpclient = HttpAsyncClients.custom()
            .setConnectionManager(this.connMgr)
            .setDefaultCredentialsProvider(credsProvider)
            .build();

        final HttpHost target = start(registry, expectationVerifier);

        final HttpPut httpput = new HttpPut("/");

        final NByteArrayEntity entity = new NByteArrayEntity(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }) {

            @Override
            public boolean isRepeatable() {
                return false;
            }

        };

        httpput.setEntity(entity);
        httpput.setConfig(RequestConfig.custom().setExpectContinueEnabled(true).build());

        final Future<HttpResponse> future = this.httpclient.execute(target, httpput, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
    }

    @Test(expected=ExecutionException.class)
    public void testBasicAuthenticationFailureWithNonRepeatableEntityExpectContinueOff() throws Exception {
        final UriHttpAsyncRequestHandlerMapper registry = new UriHttpAsyncRequestHandlerMapper();
        registry.register("*", new BasicAsyncRequestHandler(new AuthHandler()));

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));

        this.httpclient = HttpAsyncClients.custom()
            .setConnectionManager(this.connMgr)
            .setDefaultCredentialsProvider(credsProvider)
            .build();

        final HttpHost target = start(registry, null);

        final HttpPut httpput = new HttpPut("/");

        final NByteArrayEntity requestEntity = new NByteArrayEntity(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }) {

            @Override
            public boolean isRepeatable() {
                return false;
            }

        };

        httpput.setEntity(requestEntity);
        httpput.setConfig(RequestConfig.custom().setExpectContinueEnabled(false).build());

        try {
            final Future<HttpResponse> future = this.httpclient.execute(target, httpput, null);
            future.get();
            Assert.fail("ExecutionException should have been thrown");
        } catch (final ExecutionException ex) {
            final Throwable cause = ex.getCause();
            Assert.assertNotNull(cause);
            throw ex;
        }
    }

    @Test
    public void testBasicAuthenticationSuccessOnRepeatablePost() throws Exception {
        final UriHttpAsyncRequestHandlerMapper registry = new UriHttpAsyncRequestHandlerMapper();
        registry.register("*", new BasicAsyncRequestHandler(new AuthHandler()));

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));

        this.httpclient = HttpAsyncClients.custom()
            .setConnectionManager(this.connMgr)
            .setDefaultCredentialsProvider(credsProvider)
            .build();

        final HttpHost target = start(registry, null);

        final HttpPost httppost = new HttpPost("/");
        httppost.setEntity(new NStringEntity("some important stuff", Consts.ISO_8859_1));

        final Future<HttpResponse> future = this.httpclient.execute(target, httppost, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        final AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationCredentialsCaching() throws Exception {
        final UriHttpAsyncRequestHandlerMapper registry = new UriHttpAsyncRequestHandlerMapper();
        registry.register("*", new BasicAsyncRequestHandler(new AuthHandler()));

        final BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials("test", "test"));

        final TestTargetAuthenticationStrategy authStrategy = new TestTargetAuthenticationStrategy();

        this.httpclient = HttpAsyncClients.custom()
            .setConnectionManager(this.connMgr)
            .setTargetAuthenticationStrategy(authStrategy)
            .setDefaultCredentialsProvider(credsProvider)
            .build();

        final HttpHost target = start(registry, null);

        final HttpContext context = new BasicHttpContext();

        final HttpGet httpget1 = new HttpGet("/");
        final Future<HttpResponse> future1 = this.httpclient.execute(target, httpget1, context, null);
        final HttpResponse response1 = future1.get();
        Assert.assertNotNull(response1);
        Assert.assertEquals(HttpStatus.SC_OK, response1.getStatusLine().getStatusCode());

        final HttpGet httpget2 = new HttpGet("/");
        final Future<HttpResponse> future2 = this.httpclient.execute(target, httpget2, context, null);
        final HttpResponse response2 = future2.get();
        Assert.assertNotNull(response2);
        Assert.assertEquals(HttpStatus.SC_OK, response2.getStatusLine().getStatusCode());

        Assert.assertEquals(1, authStrategy.getCount());
    }

    @Test
    public void testAuthenticationUserinfoInRequestSuccess() throws Exception {
        final UriHttpAsyncRequestHandlerMapper registry = new UriHttpAsyncRequestHandlerMapper();
        registry.register("*", new BasicAsyncRequestHandler(new AuthHandler()));

        this.httpclient = HttpAsyncClients.custom()
            .setConnectionManager(this.connMgr)
            .build();

        final HttpHost target = start(registry, null);

        final HttpGet httpget = new HttpGet("http://test:test@" +  target.toHostString() + "/");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        final HttpEntity entity = response.getEntity();
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        Assert.assertNotNull(entity);
        EntityUtils.consume(entity);
    }

    @Test
    public void testAuthenticationUserinfoInRequestFailure() throws Exception {
        final UriHttpAsyncRequestHandlerMapper registry = new UriHttpAsyncRequestHandlerMapper();
        registry.register("*", new BasicAsyncRequestHandler(new AuthHandler()));

        this.httpclient = HttpAsyncClients.custom()
            .setConnectionManager(this.connMgr)
            .build();

        final HttpHost target = start(registry, null);

        final HttpGet httpget = new HttpGet("http://test:all-wrong@" +  target.toHostString() + "/");

        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        final HttpEntity entity = response.getEntity();
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, response.getStatusLine().getStatusCode());
        Assert.assertNotNull(entity);
        EntityUtils.consume(entity);
    }

}