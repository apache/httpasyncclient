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

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.RouteTracker;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;

class InternalState {

    private static final AtomicLong COUNTER = new AtomicLong(1);

    private final long id;
    private final HttpAsyncRequestProducer requestProducer;
    private final HttpAsyncResponseConsumer<?> responseConsumer;
    private final HttpClientContext localContext;

    private boolean routeEstablished;
    private RouteTracker routeTracker;
    private boolean reusable;
    private long validDuration;

    private HttpRoute route;
    private HttpRequestWrapper mainRequest;
    private HttpResponse finalResponse;
    private HttpRequestWrapper currentRequest;
    private HttpResponse currentResponse;
    private ByteBuffer tmpbuf;
    private boolean requestContentProduced;
    private int execCount;

    private int redirectCount;
    private HttpUriRequest redirect;

    public InternalState(
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<?> responseConsumer,
            final HttpClientContext localContext) {
        super();
        this.id = COUNTER.getAndIncrement();
        this.requestProducer = requestProducer;
        this.responseConsumer = responseConsumer;
        this.localContext = localContext;
    }

    public long getId() {
        return id;
    }

    public HttpAsyncRequestProducer getRequestProducer() {
        return requestProducer;
    }

    public HttpAsyncResponseConsumer<?> getResponseConsumer() {
        return responseConsumer;
    }

    public HttpClientContext getLocalContext() {
        return localContext;
    }

    public boolean isRouteEstablished() {
        return routeEstablished;
    }

    public void setRouteEstablished(final boolean b) {
        this.routeEstablished = b;
    }

    public RouteTracker getRouteTracker() {
        return routeTracker;
    }

    public void setRouteTracker(final RouteTracker routeTracker) {
        this.routeTracker = routeTracker;
    }

    public boolean isReusable() {
        return reusable;
    }

    public void setReusable() {
        this.reusable = true;
    }

    public void setNonReusable() {
        this.reusable = false;
    }

    public long getValidDuration() {
        return validDuration;
    }

    public void setValidDuration(final long validDuration) {
        this.validDuration = validDuration;
    }

    public HttpRoute getRoute() {
        return route;
    }

    public void setRoute(final HttpRoute route) {
        this.route = route;
    }

    public HttpRequestWrapper getMainRequest() {
        return mainRequest;
    }

    public void setMainRequest(final HttpRequestWrapper mainRequest) {
        this.mainRequest = mainRequest;
    }

    public HttpResponse getFinalResponse() {
        return finalResponse;
    }

    public void setFinalResponse(final HttpResponse finalResponse) {
        this.finalResponse = finalResponse;
    }

    public HttpRequestWrapper getCurrentRequest() {
        return currentRequest;
    }

    public void setCurrentRequest(final HttpRequestWrapper currentRequest) {
        this.currentRequest = currentRequest;
    }

    public HttpResponse getCurrentResponse() {
        return currentResponse;
    }

    public void setCurrentResponse(final HttpResponse currentResponse) {
        this.currentResponse = currentResponse;
    }

    public ByteBuffer getTmpbuf() {
        if (tmpbuf == null) {
            tmpbuf = ByteBuffer.allocate(4 * 1024);
        }
        return tmpbuf;
    }

    public boolean isRequestContentProduced() {
        return requestContentProduced;
    }

    public void setRequestContentProduced() {
        this.requestContentProduced = true;
    }

    public int getExecCount() {
        return execCount;
    }

    public void incrementExecCount() {
        this.execCount++;
    }

    public int getRedirectCount() {
        return redirectCount;
    }

    public void incrementRedirectCount() {
        this.redirectCount++;
    }

    public HttpUriRequest getRedirect() {
        return redirect;
    }

    public void setRedirect(final HttpUriRequest redirect) {
        this.redirect = redirect;
    }

    @Override
    public String toString() {
        return Long.toString(id);
    }

}
