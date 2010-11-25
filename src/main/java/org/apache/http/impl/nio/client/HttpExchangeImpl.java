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
import java.util.concurrent.Future;

import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.nio.client.HttpAsyncExchangeHandler;
import org.apache.http.nio.concurrent.BasicFuture;
import org.apache.http.nio.concurrent.FutureCallback;
import org.apache.http.nio.conn.IOSessionManager;
import org.apache.http.nio.conn.ManagedIOSession;
import org.apache.http.nio.reactor.IOSession;

class HttpExchangeImpl<T> {

    public static final String HTTP_EXCHANGE = "http.nio.http-exchange";

    private final Future<ManagedIOSession> sessionFuture;
    private final BasicFuture<T> resultFuture;
    private final HttpAsyncExchangeHandler<T> handler;

    private ManagedIOSession managedSession;

    public HttpExchangeImpl(
            final HttpRoute route,
            final Object state,
            final IOSessionManager<HttpRoute> sessmrg,
            final HttpAsyncExchangeHandler<T> handler,
            final FutureCallback<T> callback) {
        super();
        this.sessionFuture = sessmrg.leaseSession(route, state, new InternalFutureCallback());
        this.resultFuture = new BasicFuture<T>(callback);
        this.handler = handler;
    }

    public HttpAsyncExchangeHandler<T> getHandler() {
        return this.handler;
    }

    public Future<T> getResultFuture() {
        return this.resultFuture;
    }

    public synchronized void completed() {
        try {
            if (this.managedSession != null) {
                this.managedSession.releaseSession();
            }
            this.managedSession = null;
            T result = this.handler.completed();
            this.resultFuture.completed(result);
        } catch (RuntimeException runex) {
            this.resultFuture.failed(runex);
            throw runex;
        }
    }

    public synchronized void shutdown() {
        try {
            this.sessionFuture.cancel(true);
            if (this.managedSession != null) {
                this.managedSession.abortSession();
            }
            this.managedSession = null;
            this.handler.cancelled();
        } catch (RuntimeException runex) {
            this.resultFuture.failed(runex);
            throw runex;
        }
    }

    public synchronized void failed(final Exception ex) {
        try {
            this.sessionFuture.cancel(true);
            if (this.managedSession != null) {
                this.managedSession.abortSession();
            }
            this.managedSession = null;
            this.handler.failed(ex);
        } catch (RuntimeException runex) {
            this.resultFuture.failed(ex);
            throw runex;
        }
    }

    private synchronized void sessionRequestCompleted(final ManagedIOSession session) {
        this.managedSession = session;
        IOSession iosession = session.getSession();
        iosession.setAttribute(HTTP_EXCHANGE, this);
        iosession.setEvent(SelectionKey.OP_WRITE);
    }

    private synchronized void sessionRequestFailed(final Exception ex) {
        try {
            this.handler.failed(ex);
        } finally {
            this.resultFuture.failed(ex);
        }
    }

    private synchronized void sessionRequestCancelled() {
        try {
            this.handler.cancelled();
        } finally {
            this.resultFuture.cancel(true);
        }
    }

    class InternalFutureCallback implements FutureCallback<ManagedIOSession> {

        public void completed(final ManagedIOSession session) {
            sessionRequestCompleted(session);
        }

        public void failed(final Exception ex) {
            sessionRequestFailed(ex);
        }

        public void cancelled() {
            sessionRequestCancelled();
        }

    }

}
