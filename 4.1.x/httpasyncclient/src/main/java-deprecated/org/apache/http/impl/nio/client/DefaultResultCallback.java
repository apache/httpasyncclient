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

import java.util.Queue;

import org.apache.http.concurrent.BasicFuture;
import org.apache.http.nio.protocol.HttpAsyncRequestExecutionHandler;

@Deprecated
class DefaultResultCallback<T> implements ResultCallback<T> {

    private final BasicFuture<T> future;
    private final Queue<HttpAsyncRequestExecutionHandler<?>> queue;

    DefaultResultCallback(
            final BasicFuture<T> future, final Queue<HttpAsyncRequestExecutionHandler<?>> queue) {
        super();
        this.future = future;
        this.queue = queue;
    }

    @Override
    public void completed(final T result, final HttpAsyncRequestExecutionHandler<T> handler) {
        this.future.completed(result);
        this.queue.remove(handler);
    }

    @Override
    public void failed(final Exception ex, final HttpAsyncRequestExecutionHandler<T> handler) {
        this.future.failed(ex);
        this.queue.remove(handler);
    }

    @Override
    public void cancelled(final HttpAsyncRequestExecutionHandler<T> handler) {
        this.future.cancel(true);
        this.queue.remove(handler);
    }

    @Override
    public boolean isDone() {
        return this.future.isDone();
    }

}
