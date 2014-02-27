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

import java.util.concurrent.ExecutionException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.client.cache.HttpCacheContext;
import org.apache.http.client.cache.HttpCacheEntry;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;

/**
 * Class used to represent an asynchronous revalidation event, such as with
 * "stale-while-revalidate"
 */
class AsynchronousAsyncValidationRequest implements Runnable {
    private final AsynchronousAsyncValidator parent;
    private final CachingHttpAsyncClient cachingAsyncClient;
    private final HttpHost target;
    private final HttpRequestWrapper request;
    private final HttpCacheContext clientContext;
    private final HttpCacheEntry cacheEntry;
    private final String identifier;

    private final Log log = LogFactory.getLog(getClass());

    /**
     * Used internally by {@link AsynchronousValidator} to schedule a
     * revalidation.
     */
    AsynchronousAsyncValidationRequest(final AsynchronousAsyncValidator parent,
            final CachingHttpAsyncClient cachingClient, final HttpHost target, final HttpRequestWrapper request,
            final HttpCacheContext clientContext, final HttpCacheEntry cacheEntry, final String identifier) {
        this.parent = parent;
        this.cachingAsyncClient = cachingClient;
        this.target = target;
        this.request = request;
        this.clientContext = clientContext;
        this.cacheEntry = cacheEntry;
        this.identifier = identifier;
    }

    public void run() {
        try {
            final FutureCallback<HttpResponse> callback = new FutureCallback<HttpResponse>() {

                public void cancelled() {
                }

                public void completed(final HttpResponse httpResponse) {
                }

                public void failed(final Exception e) {
                    log.debug("Asynchronous revalidation failed", e);
                }
            };
            final BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(callback);
            this.cachingAsyncClient.revalidateCacheEntry(future, this.target, this.request,
                    this.clientContext, this.cacheEntry);
            future.get();
        } catch (final ProtocolException pe) {
            this.log.error("ProtocolException thrown during asynchronous revalidation", pe);
        } catch (ExecutionException e) {
            this.log.error("Exception thrown during asynchronous revalidation", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            this.parent.markComplete(this.identifier);
        }
    }

    String getIdentifier() {
        return this.identifier;
    }

}
