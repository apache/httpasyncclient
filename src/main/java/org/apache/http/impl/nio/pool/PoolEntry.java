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
package org.apache.http.impl.nio.pool;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.http.nio.reactor.IOSession;

public class PoolEntry<T> {

    private static AtomicLong COUNTER = new AtomicLong();

    private final long id;
    private final T route;
    private final IOSession session;
    private Object state;

    public PoolEntry(final T route, final IOSession session) {
        super();
        this.route = route;
        this.session = session;
        this.id = COUNTER.incrementAndGet();
    }

    public T getRoute() {
        return this.route;
    }

    public IOSession getIOSession() {
        return this.session;
    }
    public Object getState() {
        return this.state;
    }

    public void setState(final Object state) {
        this.state = state;
    }

    @Override
    public String toString() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("[id:");
        buffer.append(this.id);
        buffer.append("][route:");
        buffer.append(this.route);
        buffer.append("][state:");
        buffer.append(this.state);
        buffer.append("]");
        return buffer.toString();
    }

}
