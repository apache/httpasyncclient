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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.http.HttpResponse;
import org.apache.http.concurrent.FutureCallback;

/**
 * Implementation that wraps a Future and overrides the result/exception.
 *
 * @author James Leigh
 *
 */
class FutureHttpResponse implements Future<HttpResponse>,
        FutureCallback<HttpResponse> {
    private final FutureCallback<HttpResponse> callback;
    private Future<HttpResponse> delegate;
    private HttpResponse response;
    private Throwable thrown;

    public FutureHttpResponse(FutureCallback<HttpResponse> callback) {
        this.callback = callback;
    }

    public String toString() {
        return String.valueOf(response);
    }

    public synchronized Future<HttpResponse> getDelegate() {
        return delegate;
    }

    public synchronized void setDelegate(Future<HttpResponse> delegate) {
        this.delegate = delegate;
    }

    public boolean isCancelled() {
        Future<?> delegate = getDelegate();
        return delegate != null && delegate.isCancelled();
    }

    public boolean isDone() {
        Future<?> delegate = getDelegate();
        return delegate != null && getDelegate().isDone();
    }

    public HttpResponse get() throws InterruptedException, ExecutionException {
        try {
            getDelegate().get();
        } catch (ExecutionException e) {
            // ignore
        }
        HttpResponse result = getResponse();
        Throwable thrown = getThrown();
        if (thrown != null)
            throw new ExecutionException(thrown);
        return result;
    }

    public HttpResponse get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            getDelegate().get(timeout, unit);
        } catch (ExecutionException e) {
            // ignore
        }
        HttpResponse result = getResponse();
        Throwable thrown = getThrown();
        if (thrown != null)
            throw new ExecutionException(thrown);
        return result;
    }

    public void completed(final HttpResponse result) {
        setResponse(result);
        if (this.callback != null) {
            this.callback.completed(result);
        }
    }

    public void failed(final Exception exception) {
        setThrown(exception);
        if (this.callback != null) {
            this.callback.failed(exception);
        }
    }

    public boolean cancel(final boolean mayInterruptIfRunning) {
        if (this.callback != null) {
            this.callback.cancelled();
        }
        return getDelegate().cancel(mayInterruptIfRunning);
    }

    public boolean cancel() {
        return cancel(true);
    }

    public void cancelled() {
        if (this.callback != null) {
            this.callback.cancelled();
        }
    }

    private synchronized HttpResponse getResponse() {
        return response;
    }

    private synchronized void setResponse(HttpResponse response) {
        this.response = response;
    }

    private synchronized Throwable getThrown() {
        return thrown;
    }

    private synchronized void setThrown(Throwable thrown) {
        this.thrown = thrown;
    }

}
