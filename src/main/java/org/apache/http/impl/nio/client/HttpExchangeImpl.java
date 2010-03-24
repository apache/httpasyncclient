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
        this.responseFuture = new BasicFuture<HttpResponse>(null);
        this.sessionFuture = sessmrg.leaseSession(route, state, new InternalFutureCallback());
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

    private synchronized void requestCompleted(final ManagedIOSession session) {
        this.managedSession = session;
        IOSession iosession = session.getSession();
        iosession.setAttribute(InternalRequestExecutionHandler.HTTP_EXCHANGE, this);
        iosession.setEvent(SelectionKey.OP_WRITE);
    }
    
    private synchronized void requestFailed(final Exception ex) {
        this.responseFuture.failed(ex);
    }
    
    private synchronized void requestCancelled() {
        this.responseFuture.cancel(true);
    }
    
    class InternalFutureCallback implements FutureCallback<ManagedIOSession> {

        public void completed(final ManagedIOSession session) {
            requestCompleted(session);
        }

        public void failed(final Exception ex) {
            requestFailed(ex);
        }
        
        public void cancelled() {
            requestCancelled();            
        }

    }
    
}
