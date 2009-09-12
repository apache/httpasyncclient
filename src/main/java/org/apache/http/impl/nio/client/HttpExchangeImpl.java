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

import java.nio.channels.SelectionKey;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.nio.client.HttpExchange;
import org.apache.http.nio.concurrent.BasicFuture;
import org.apache.http.nio.concurrent.FutureCallback;
import org.apache.http.nio.conn.IOSessionManager;
import org.apache.http.nio.conn.ManagedIOSession;
import org.apache.http.nio.reactor.IOSession;

class HttpExchangeImpl implements HttpExchange {

    private final HttpRequest request;
    private final Future<ManagedIOSession> sessionFuture;
    private final BasicFuture<HttpResponse> responseFuture;

    private ManagedIOSession managedSession;
    
    public HttpExchangeImpl(
            final HttpRequest request,
            final HttpRoute route,
            final Object state,
            final IOSessionManager<HttpRoute> sessmrg) {
        super();
        this.request = request;
        this.sessionFuture = sessmrg.leaseSession(route, state, new InternalFutureCallback());
        this.responseFuture = new BasicFuture<HttpResponse>(null);
    }

    public boolean isCompleted() {
        return this.responseFuture.isDone();
    }
    
    public HttpRequest getRequest() {
        return this.request;
    }
    
    public HttpResponse awaitResponse() throws ExecutionException, InterruptedException {
        return this.responseFuture.get();
    }

    public synchronized void completed(final HttpResponse response) {
        if (this.managedSession != null) {
            this.managedSession.releaseSession();
        }
        this.responseFuture.completed(response);
    }
    
    public synchronized void cancel() {
        this.sessionFuture.cancel(true);
        if (this.managedSession != null) {
            this.managedSession.abortSession();
        }
        this.responseFuture.cancel(true);
    }

    private synchronized void sessionRequestCompleted() {
        try {
            this.managedSession = this.sessionFuture.get();
            if (this.managedSession == null || this.sessionFuture.isCancelled()) {
                this.responseFuture.cancel(true);
            } else {
                IOSession iosession = this.managedSession.getSession();
                iosession.setAttribute(InternalRequestExecutionHandler.HTTP_EXCHANGE, this);
                iosession.setEvent(SelectionKey.OP_WRITE);
            }
        } catch (InterruptedException ex) {
            this.responseFuture.cancel(true);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause(); 
            if (cause != null && cause instanceof Exception) {
                this.responseFuture.failed((Exception) cause);
            } else {
                this.responseFuture.failed(ex);
            }
        }
    }
    
    class InternalFutureCallback implements FutureCallback<ManagedIOSession> {

        public void completed(final Future<ManagedIOSession> future) {
            sessionRequestCompleted();
        }

        public void failed(final Future<ManagedIOSession> future) {
            sessionRequestCompleted();
        }
        
        public void cancelled(final Future<ManagedIOSession> future) {
            sessionRequestCompleted();            
        }

    }
    
}
