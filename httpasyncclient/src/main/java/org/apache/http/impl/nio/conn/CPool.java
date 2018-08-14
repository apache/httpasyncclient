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
package org.apache.http.impl.nio.conn;

import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.annotation.Contract;
import org.apache.http.annotation.ThreadingBehavior;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.conn.ManagedNHttpClientConnection;
import org.apache.http.nio.pool.AbstractNIOConnPool;
import org.apache.http.nio.pool.NIOConnFactory;
import org.apache.http.nio.pool.SocketAddressResolver;
import org.apache.http.nio.reactor.ConnectingIOReactor;

@Contract(threading = ThreadingBehavior.SAFE)
class CPool extends AbstractNIOConnPool<HttpRoute, ManagedNHttpClientConnection, CPoolEntry> {

    private final Log log = LogFactory.getLog(CPool.class);

    private final long timeToLive;
    private final TimeUnit timeUnit;

    public CPool(
            final ConnectingIOReactor ioReactor,
            final NIOConnFactory<HttpRoute, ManagedNHttpClientConnection> connFactory,
            final SocketAddressResolver<HttpRoute> addressResolver,
            final int defaultMaxPerRoute, final int maxTotal,
            final long timeToLive, final TimeUnit timeUnit) {
        super(ioReactor, connFactory, addressResolver, defaultMaxPerRoute, maxTotal);
        this.timeToLive = timeToLive;
        this.timeUnit = timeUnit;
    }

    @Override
    protected CPoolEntry createEntry(final HttpRoute route, final ManagedNHttpClientConnection conn) {
        final CPoolEntry entry =  new CPoolEntry(this.log, conn.getId(), route, conn, this.timeToLive, this.timeUnit);
        entry.setSocketTimeout(conn.getSocketTimeout());
        return entry;
    }

    @Override
    protected void onLease(final CPoolEntry entry) {
        final NHttpClientConnection conn = entry.getConnection();
        conn.setSocketTimeout(entry.getSocketTimeout());
    }

    @Override
    protected void onRelease(final CPoolEntry entry) {
        final NHttpClientConnection conn = entry.getConnection();
        conn.setSocketTimeout(0);
    }

}
