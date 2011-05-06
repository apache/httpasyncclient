/*
 * ====================================================================
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 */

package org.apache.http.impl.nio.client;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultTargetAuthenticationHandler;
import org.apache.http.localserver.AsyncHttpTestBase;
import org.apache.http.localserver.BasicAuthTokenExtractor;
import org.apache.http.localserver.LocalTestServer;
import org.apache.http.localserver.RequestBasicAuth;
import org.apache.http.localserver.ResponseBasicUnauthorized;
import org.apache.http.nio.entity.NByteArrayEntity;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpExpectationVerifier;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class TestClientAuthentication extends AsyncHttpTestBase {

    @Override
    protected LocalTestServer createServer() throws Exception {
        BasicHttpProcessor httpproc = new BasicHttpProcessor();
        httpproc.addInterceptor(new ResponseDate());
        httpproc.addInterceptor(new ResponseServer());
        httpproc.addInterceptor(new ResponseContent());
        httpproc.addInterceptor(new ResponseConnControl());
        httpproc.addInterceptor(new RequestBasicAuth());
        httpproc.addInterceptor(new ResponseBasicUnauthorized());

        LocalTestServer localServer = new LocalTestServer(httpproc, null);
        localServer.register("*", new AuthHandler());
        return localServer;
    }

    static class AuthHandler implements HttpRequestHandler {

        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {
            String creds = (String) context.getAttribute("creds");
            if (creds == null || !creds.equals("test:test")) {
                response.setStatusCode(HttpStatus.SC_UNAUTHORIZED);
            } else {
                response.setStatusCode(HttpStatus.SC_OK);
                StringEntity entity = new StringEntity("success", HTTP.ASCII);
                response.setEntity(entity);
            }
        }

    }

    static class TestTargetAuthenticationHandler extends DefaultTargetAuthenticationHandler {

        private int count;

        public TestTargetAuthenticationHandler() {
            super();
            this.count = 0;
        }

        @Override
        public boolean isAuthenticationRequested(
                final HttpResponse response,
                final HttpContext context) {
            boolean res = super.isAuthenticationRequested(response, context);
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

    static class AuthExpectationVerifier implements HttpExpectationVerifier {

        private final BasicAuthTokenExtractor authTokenExtractor;

        public AuthExpectationVerifier() {
            super();
            this.authTokenExtractor = new BasicAuthTokenExtractor();
        }

        public void verify(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException {
            String creds = this.authTokenExtractor.extract(request);
            if (creds == null || !creds.equals("test:test")) {
                response.setStatusCode(HttpStatus.SC_UNAUTHORIZED);
            } else {
                response.setStatusCode(HttpStatus.SC_CONTINUE);
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

        public Credentials getCredentials(AuthScope authscope) {
            this.authscope = authscope;
            return this.creds;
        }

        public void setCredentials(AuthScope authscope, Credentials credentials) {
        }

        public AuthScope getAuthScope() {
            return this.authscope;
        }

    }

    @Test
    public void testBasicAuthenticationNoCreds() throws Exception {
        TestCredentialsProvider credsProvider = new TestCredentialsProvider(null);
        this.httpclient.setCredentialsProvider(credsProvider);

        HttpGet httpget = new HttpGet("/");
        Future<HttpResponse> future = this.httpclient.execute(this.target, httpget, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, response.getStatusLine().getStatusCode());
        AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationFailure() throws Exception {
        TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "all-wrong"));
        this.httpclient.setCredentialsProvider(credsProvider);

        HttpGet httpget = new HttpGet("/");
        Future<HttpResponse> future = this.httpclient.execute(this.target, httpget, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, response.getStatusLine().getStatusCode());
        AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationSuccess() throws Exception {
        TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));
        this.httpclient.setCredentialsProvider(credsProvider);

        HttpGet httpget = new HttpGet("/");
        Future<HttpResponse> future = this.httpclient.execute(this.target, httpget, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationSuccessWithNonRepeatableExpectContinue() throws Exception {
        stopServer();

        BasicHttpProcessor httpproc = new BasicHttpProcessor();
        httpproc.addInterceptor(new ResponseDate());
        httpproc.addInterceptor(new ResponseServer());
        httpproc.addInterceptor(new ResponseContent());
        httpproc.addInterceptor(new ResponseConnControl());
        httpproc.addInterceptor(new RequestBasicAuth());
        httpproc.addInterceptor(new ResponseBasicUnauthorized());
        this.localServer = new LocalTestServer(
                httpproc, null, null, new AuthExpectationVerifier(), null, null);
        this.localServer.start();
        this.localServer.register("*", new AuthHandler());
        int port = this.localServer.getServiceAddress().getPort();
        this.target = new HttpHost("localhost", port);

        TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));

        this.httpclient.setCredentialsProvider(credsProvider);

        HttpPut httpput = new HttpPut("/");

        NByteArrayEntity entity = new NByteArrayEntity(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }) {

            @Override
            public boolean isRepeatable() {
                return false;
            }

        };

        httpput.setEntity(entity);
        httpput.getParams().setBooleanParameter(CoreProtocolPNames.USE_EXPECT_CONTINUE, true);

        Future<HttpResponse> future = this.httpclient.execute(this.target, httpput, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
    }

    @Test(expected=ExecutionException.class) @Ignore
    public void testBasicAuthenticationFailureWithNonRepeatableEntityExpectContinueOff() throws Exception {
        TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));

        this.httpclient.setCredentialsProvider(credsProvider);

        HttpPut httpput = new HttpPut("/");

        NByteArrayEntity requestEntity = new NByteArrayEntity(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }) {

            @Override
            public boolean isRepeatable() {
                return false;
            }

        };

        httpput.setEntity(requestEntity);
        httpput.getParams().setBooleanParameter(CoreProtocolPNames.USE_EXPECT_CONTINUE, false);

        try {
            Future<HttpResponse> future = this.httpclient.execute(this.target, httpput, null);
            future.get();
            Assert.fail("ExecutionException should have been thrown");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            Assert.assertNotNull(cause);
            throw ex;
        }
    }

    @Test
    public void testBasicAuthenticationSuccessOnRepeatablePost() throws Exception {
        TestCredentialsProvider credsProvider = new TestCredentialsProvider(
                new UsernamePasswordCredentials("test", "test"));

        this.httpclient.setCredentialsProvider(credsProvider);

        HttpPost httppost = new HttpPost("/");
        httppost.setEntity(new NStringEntity("some important stuff", HTTP.ISO_8859_1));

        Future<HttpResponse> future = this.httpclient.execute(this.target, httppost, null);
        HttpResponse response = future.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

    @Test
    public void testBasicAuthenticationCredentialsCaching() throws Exception {
        BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials("test", "test"));

        TestTargetAuthenticationHandler authHandler = new TestTargetAuthenticationHandler();

        this.httpclient.setCredentialsProvider(credsProvider);
        this.httpclient.setTargetAuthenticationHandler(authHandler);

        HttpContext context = new BasicHttpContext();

        HttpGet httpget1 = new HttpGet("/");
        Future<HttpResponse> future1 = this.httpclient.execute(this.target, httpget1, context, null);
        HttpResponse response1 = future1.get();
        Assert.assertNotNull(response1);
        Assert.assertEquals(HttpStatus.SC_OK, response1.getStatusLine().getStatusCode());

        HttpGet httpget2 = new HttpGet("/");
        Future<HttpResponse> future2 = this.httpclient.execute(this.target, httpget2, context, null);
        HttpResponse response2 = future2.get();
        Assert.assertNotNull(response2);
        Assert.assertEquals(HttpStatus.SC_OK, response2.getStatusLine().getStatusCode());

        Assert.assertEquals(1, authHandler.getCount());
    }

}
