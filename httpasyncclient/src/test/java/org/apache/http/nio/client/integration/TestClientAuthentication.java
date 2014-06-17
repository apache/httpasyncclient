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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.http.Consts;
import org.apache.http.HttpAsyncTestBase;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpInetConnection;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
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
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.TargetAuthenticationStrategy;
import org.apache.http.localserver.BasicAuthTokenExtractor;
import org.apache.http.localserver.RequestBasicAuth;
import org.apache.http.localserver.ResponseBasicUnauthorized;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.nio.entity.NByteArrayEntity;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.nio.protocol.BasicAsyncRequestHandler;
import org.apache.http.nio.protocol.BasicAsyncResponseProducer;
import org.apache.http.nio.protocol.HttpAsyncExchange;
import org.apache.http.nio.protocol.HttpAsyncExpectationVerifier;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TestClientAuthentication extends HttpAsyncTestBase {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> protocols() {
        return Arrays.asList(new Object[][]{
                {ProtocolScheme.http},
                {ProtocolScheme.https},
        });
    }

    public TestClientAuthentication(final ProtocolScheme scheme) {
        super(scheme);
    }

    @Before @Override
    public void setUp() throws Exception {
        super.setUp();
        this.serverBootstrap.addInterceptorFirst(new RequestBasicAuth());
        this.serverBootstrap.addInterceptorLast(new ResponseBasicUnauthorized());
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
            if (res) {
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
        this.serverBootstrap.registerHandler("*", new BasicAsyncRequestHandler(new AuthHandler()));
        final HttpHost target = start();

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(null);
        final HttpClientContext context = HttpClientContext.create();
        context.setCredentialsProvider(credsProvider);
        final HttpGet httpget = new HttpGet("/");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, response.getStatusLine().getStatusCode());
        final AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationFailure() throws Exception {
        this.serverBootstrap.registerHandler("*", new BasicAsyncRequestHandler(new AuthHandler()));
        final HttpHost target = start();

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "all-wrong"));
        final HttpClientContext context = HttpClientContext.create();
        context.setCredentialsProvider(credsProvider);
        final HttpGet httpget = new HttpGet("/");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, response.getStatusLine().getStatusCode());
        final AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationSuccess() throws Exception {
        this.serverBootstrap.registerHandler("*", new BasicAsyncRequestHandler(new AuthHandler()));
        final HttpHost target = start();

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));
        final HttpClientContext context = HttpClientContext.create();
        context.setCredentialsProvider(credsProvider);
        final HttpGet httpget = new HttpGet("/");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        final AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationSuccessNonPersistentConnection() throws Exception {
        this.serverBootstrap.registerHandler("*", new BasicAsyncRequestHandler(new AuthHandler(false)));
        final HttpHost target = start();

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));
        final HttpClientContext context = HttpClientContext.create();
        context.setCredentialsProvider(credsProvider);

        final HttpGet httpget = new HttpGet("/");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, context, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        final AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationSuccessWithNonRepeatableExpectContinue() throws Exception {
        this.serverBootstrap.registerHandler("*", new BasicAsyncRequestHandler(new AuthHandler()));
        this.serverBootstrap.setExpectationVerifier(new AuthExpectationVerifier());
        final HttpHost target = start();

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));
        final HttpClientContext context = HttpClientContext.create();
        context.setCredentialsProvider(credsProvider);
        final HttpPut httpput = new HttpPut("/");

        final NByteArrayEntity entity = new NByteArrayEntity(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }) {

            @Override
            public boolean isRepeatable() {
                return false;
            }

        };

        httpput.setEntity(entity);
        httpput.setConfig(RequestConfig.custom().setExpectContinueEnabled(true).build());

        final Future<HttpResponse> future = this.httpclient.execute(target, httpput, context, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
    }

    @Test(expected=ExecutionException.class)
    public void testBasicAuthenticationFailureWithNonRepeatableEntityExpectContinueOff() throws Exception {
        this.serverBootstrap.registerHandler("*", new BasicAsyncRequestHandler(new AuthHandler()));
        final HttpHost target = start();

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));
        final HttpClientContext context = HttpClientContext.create();
        context.setCredentialsProvider(credsProvider);
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
            final Future<HttpResponse> future = this.httpclient.execute(target, httpput, context, null);
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
        this.serverBootstrap.registerHandler("*", new BasicAsyncRequestHandler(new AuthHandler()));
        final HttpHost target = start();

        final TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));
        final HttpClientContext context = HttpClientContext.create();
        context.setCredentialsProvider(credsProvider);
        final HttpPost httppost = new HttpPost("/");
        httppost.setEntity(new NStringEntity("some important stuff", Consts.ISO_8859_1));

        final Future<HttpResponse> future = this.httpclient.execute(target, httppost, context, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        final AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationCredentialsCaching() throws Exception {
        this.serverBootstrap.registerHandler("*", new BasicAsyncRequestHandler(new AuthHandler()));
        final TestTargetAuthenticationStrategy authStrategy = new TestTargetAuthenticationStrategy();
        this.clientBuilder.setTargetAuthenticationStrategy(authStrategy);
        final HttpHost target = start();

        final BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials("test", "test"));
        final HttpClientContext context = HttpClientContext.create();
        context.setCredentialsProvider(credsProvider);

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
        this.serverBootstrap.registerHandler("*", new BasicAsyncRequestHandler(new AuthHandler()));
        final HttpHost target = start();

        final HttpGet httpget = new HttpGet("http://test:test@" +  target.toHostString() + "/");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        final HttpEntity entity = response.getEntity();
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        Assert.assertNotNull(entity);
    }

    @Test
    public void testAuthenticationUserinfoInRequestFailure() throws Exception {
        this.serverBootstrap.registerHandler("*", new BasicAsyncRequestHandler(new AuthHandler()));
        final HttpHost target = start();

        final HttpGet httpget = new HttpGet("http://test:all-wrong@" +  target.toHostString() + "/");

        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        final HttpEntity entity = response.getEntity();
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, response.getStatusLine().getStatusCode());
        Assert.assertNotNull(entity);
    }

    private class RedirectHandler implements HttpRequestHandler {

        public RedirectHandler() {
            super();
        }

        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {
            final HttpInetConnection conn = (HttpInetConnection) context.getAttribute(HttpCoreContext.HTTP_CONNECTION);
            final int port = conn.getLocalPort();
            response.setStatusCode(HttpStatus.SC_MOVED_PERMANENTLY);
            response.addHeader(new BasicHeader("Location", getSchemeName() + "://test:test@localhost:" + port + "/"));
        }

    }

    @Test
    public void testAuthenticationUserinfoInRedirectSuccess() throws Exception {
        this.serverBootstrap.registerHandler("*", new BasicAsyncRequestHandler(new AuthHandler()));
        this.serverBootstrap.registerHandler("/thatway", new BasicAsyncRequestHandler(new RedirectHandler()));
        final HttpHost target = start();

        final HttpGet httpget = new HttpGet(target.getSchemeName() + "://test:test@" +  target.toHostString() + "/thatway");
        final Future<HttpResponse> future = this.httpclient.execute(target, httpget, null);
        final HttpResponse response = future.get();
        Assert.assertNotNull(response);
        final HttpEntity entity = response.getEntity();
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        Assert.assertNotNull(entity);
    }

}