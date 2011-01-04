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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.http.HttpHost;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.nio.pool.PoolEntryFactory;
import org.apache.http.impl.nio.pool.RouteResolver;
import org.apache.http.impl.nio.pool.SessionPool;
import org.apache.http.nio.conn.scheme.Scheme;
import org.apache.http.nio.conn.scheme.SchemeRegistry;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOSession;

class HttpSessionPool extends SessionPool<HttpRoute, HttpPoolEntry> {

    HttpSessionPool(
            final Log log,
            final ConnectingIOReactor ioreactor,
            final SchemeRegistry schemeRegistry,
            long timeToLive, final TimeUnit tunit) {
        super(ioreactor,
                new InternalEntryFactory(log, timeToLive, tunit),
                new InternalRouteResolver(schemeRegistry),
                20, 50);
    }

    static class InternalRouteResolver implements RouteResolver<HttpRoute> {

        private final SchemeRegistry schemeRegistry;

        InternalRouteResolver(final SchemeRegistry schemeRegistry) {
            super();
            this.schemeRegistry = schemeRegistry;
        }

        public SocketAddress resolveLocalAddress(final HttpRoute route) {
            return new InetSocketAddress(route.getLocalAddress(), 0);
        }

        public SocketAddress resolveRemoteAddress(final HttpRoute route) {
            HttpHost firsthop = route.getProxyHost();
            if (firsthop == null) {
                firsthop = route.getTargetHost();
            }
            String hostname = firsthop.getHostName();
            int port = firsthop.getPort();
            if (port < 0) {
                Scheme scheme = this.schemeRegistry.getScheme(firsthop);
                port = scheme.resolvePort(port);
            }
            return new InetSocketAddress(hostname, port);
        }

    }

    static class InternalEntryFactory implements PoolEntryFactory<HttpRoute, HttpPoolEntry> {

        private final Log log;
        private final long connTimeToLive;
        private final TimeUnit tunit;

        InternalEntryFactory(final Log log, final long connTimeToLive, final TimeUnit tunit) {
            super();
            this.log = log;
            this.connTimeToLive = connTimeToLive;
            this.tunit = tunit;
        }

        public HttpPoolEntry createEntry(final HttpRoute route, final IOSession session) {
            return new HttpPoolEntry(this.log, route, session, this.connTimeToLive, this.tunit);
        }

    };

}
