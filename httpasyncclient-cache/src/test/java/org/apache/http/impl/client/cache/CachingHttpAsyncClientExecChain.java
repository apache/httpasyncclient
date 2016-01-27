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
package org.apache.http.impl.client.cache;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpExecutionAware;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.execchain.ClientExecChain;

public class CachingHttpAsyncClientExecChain implements ClientExecChain {

    private final CachingHttpAsyncClient client;

    public CachingHttpAsyncClientExecChain(final ClientExecChain backend) {
        this(backend, new BasicHttpCache(), CacheConfig.DEFAULT);
    }

    public CachingHttpAsyncClientExecChain(
            final ClientExecChain backend,
            final HttpCache cache,
            final CacheConfig config) {
        this.client = new CachingHttpAsyncClient(
                new ClientExecChainAsyncClient(backend), cache, config);
    }

    CachingHttpAsyncClientExecChain(
            final ClientExecChain backend, final HttpCache responseCache,
            final CacheValidityPolicy validityPolicy,
            final ResponseCachingPolicy responseCachingPolicy,
            final CachedHttpResponseGenerator responseGenerator,
            final CacheableRequestPolicy cacheableRequestPolicy,
            final CachedResponseSuitabilityChecker suitabilityChecker,
            final ConditionalRequestBuilder conditionalRequestBuilder,
            final ResponseProtocolCompliance responseCompliance,
            final RequestProtocolCompliance requestCompliance) {
        this.client = new CachingHttpAsyncClient(
                new ClientExecChainAsyncClient(backend), validityPolicy,
                responseCachingPolicy, responseCache, responseGenerator,
                cacheableRequestPolicy, suitabilityChecker,
                conditionalRequestBuilder, responseCompliance,
                requestCompliance);
    }

    public boolean supportsRangeAndContentRangeHeaders() {
        return client.supportsRangeAndContentRangeHeaders();
    }

    public CloseableHttpResponse execute(
            final HttpRoute route,
            final HttpRequestWrapper request) throws IOException, HttpException {
        return execute(route, request, HttpClientContext.create(), null);
    }

    public CloseableHttpResponse execute(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context) throws IOException, HttpException {
        return execute(route, request, context, null);
    }

    @Override
    public CloseableHttpResponse execute(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context,
            final HttpExecutionAware execAware) throws IOException, HttpException {
        try {
            final Future<HttpResponse> future = client.execute(route.getTargetHost(), request, context, null);
            return Proxies.enhanceResponse(future.get());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final ExecutionException e) {
            try {
                throw e.getCause();
            } catch (final IOException ex) {
                throw ex;
            } catch (final HttpException ex) {
                throw ex;
            } catch (final RuntimeException ex) {
                throw ex;
            } catch (final Error ex) {
                throw ex;
            } catch (final Throwable ex) {
                throw new UndeclaredThrowableException(ex);
            }
        }
    }

    public long getCacheHits() {
        return client.getCacheHits();
    }

    public long getCacheMisses() {
        return client.getCacheMisses();
    }

    public long getCacheUpdates() {
        return client.getCacheUpdates();
    }

}
