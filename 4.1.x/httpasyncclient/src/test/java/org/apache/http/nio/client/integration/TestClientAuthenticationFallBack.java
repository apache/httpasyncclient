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
import java.util.concurrent.Future;

import org.apache.http.Consts;
import org.apache.http.localserver.HttpAsyncTestBase;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AUTH;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.localserver.RequestBasicAuth;
import org.apache.http.nio.protocol.BasicAsyncRequestHandler;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.util.EntityUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestClientAuthenticationFallBack extends HttpAsyncTestBase {

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        this.serverBootstrap.addInterceptorFirst(new RequestBasicAuth());
        this.serverBootstrap.addInterceptorLast(new ResponseBasicUnauthorized());
    }

    public class ResponseBasicUnauthorized implements HttpResponseInterceptor {

        @Override
        public void process(
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
                response.addHeader(AUTH.WWW_AUTH, "Digest realm=\"test realm\" invalid");
                response.addHeader(AUTH.WWW_AUTH, "Basic realm=\"test realm\"");
            }
        }

    }

    static class AuthHandler implements HttpRequestHandler {

        @Override
        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {
            final String creds = (String) context.getAttribute("creds");
            if (creds == null || !creds.equals("test:test")) {
                response.setStatusCode(HttpStatus.SC_UNAUTHORIZED);
            } else {
                response.setStatusCode(HttpStatus.SC_OK);
                final StringEntity entity = new StringEntity("success", Consts.ASCII);
                response.setEntity(entity);
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

        @Override
        public void clear() {
        }

        @Override
        public Credentials getCredentials(final AuthScope authscope) {
            this.authscope = authscope;
            return this.creds;
        }

        @Override
        public void setCredentials(final AuthScope authscope, final Credentials credentials) {
        }

        public AuthScope getAuthScope() {
            return this.authscope;
        }

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
        final HttpEntity entity = response.getEntity();
        Assert.assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        Assert.assertNotNull(entity);
        EntityUtils.consume(entity);
        final AuthScope authscope = credsProvider.getAuthScope();
        Assert.assertNotNull(authscope);
        Assert.assertEquals("test realm", authscope.getRealm());
    }

}
