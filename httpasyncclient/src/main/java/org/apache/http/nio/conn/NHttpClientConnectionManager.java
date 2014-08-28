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
package org.apache.http.nio.conn;

import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.protocol.HttpContext;

/**
 * Represents a manager of persistent client connections.
 * <p>
 * The purpose of an HTTP connection manager is to serve as a factory for new
 * HTTP connections, manage persistent connections and synchronize access to
 * persistent connections making sure that only one thread of execution can
 * have access to a connection at a time.
 * <p>
 * Implementations of this interface must be thread-safe. Access to shared
 * data must be synchronized as methods of this interface may be executed
 * from multiple threads.
 *
 * @since 4.0
 */
public interface NHttpClientConnectionManager {

    /**
     * Returns a {@link Future} for a {@link NHttpClientConnection}.
     * <p>
     * Please note that the consumer of that connection is responsible
     * for fully establishing the route the to the connection target
     * by calling {@link #startRoute(org.apache.http.nio.NHttpClientConnection,
     *   org.apache.http.conn.routing.HttpRoute,
     *   org.apache.http.protocol.HttpContext) startRoute} in order to start
     * the process of connection initialization, optionally calling
     * {@link #upgrade(org.apache.http.nio.NHttpClientConnection,
     *   org.apache.http.conn.routing.HttpRoute,
     *   org.apache.http.protocol.HttpContext) upgrade} method to upgrade
     * the connection after having executed {@code CONNECT} method to
     * all intermediate proxy hops and and finally calling
     * {@link #routeComplete(org.apache.http.nio.NHttpClientConnection,
     *   org.apache.http.conn.routing.HttpRoute,
     *   org.apache.http.protocol.HttpContext) routeComplete} to mark the route
     * as fully completed.
     *
     * @param route HTTP route of the requested connection.
     * @param state expected state of the connection or {@code null}
     *              if the connection is not expected to carry any state.
     * @param connectTimeout connect timeout.
     * @param connectionRequestTimeout  connection request timeout.
     * @param timeUnit time unit of the previous two timeout values.
     * @param callback future callback.
     */
    Future<NHttpClientConnection> requestConnection(
            HttpRoute route,
            Object state,
            long connectTimeout,
            long connectionRequestTimeout,
            TimeUnit timeUnit,
            FutureCallback<NHttpClientConnection> callback);

    /**
     * Releases the connection back to the manager making it potentially
     * re-usable by other consumers. Optionally, the maximum period
     * of how long the manager should keep the connection alive can be
     * defined using {@code validDuration} and {@code timeUnit}
     * parameters.
     *
     * @param conn      the managed connection to release.
     * @param validDuration the duration of time this connection is valid for reuse.
     * @param timeUnit the time unit.
     *
     * @see #closeExpiredConnections()
     */
    void releaseConnection(
            NHttpClientConnection conn, Object newState, long validDuration, TimeUnit timeUnit);

    /**
     * Starts the process of connection initialization. Connection route may consist of several
     * intermediate hops and may require a protocol upgrade. Once the route is fully established
     * the {@link #routeComplete(org.apache.http.nio.NHttpClientConnection,
     *   org.apache.http.conn.routing.HttpRoute,
     *   org.apache.http.protocol.HttpContext) routeComplete} method must be called.
     *
     * @param conn the managed connection to initialize.
     * @param route the connection route.
     * @param context the context
     */
    void startRoute(
            NHttpClientConnection conn,
            HttpRoute route,
            HttpContext context) throws IOException;

    /**
     * Upgrades the underlying connection I/O session to TLS/SSL (or another layering
     * protocol) after having executed {@code CONNECT} method to all
     * intermediate proxy hops.
     *
     * @param conn the managed connection to upgrade.
     * @param route the connection route.
     * @param context the context
     */
    void upgrade(
            NHttpClientConnection conn,
            HttpRoute route,
            HttpContext context) throws IOException;

    /**
     * Marks the connection as fully established with all its intermediate
     * hops completed.
     *
     * @param conn the managed connection to mark as route complete.
     * @param route the connection route.
     * @param context the context
     */
    void routeComplete(
            NHttpClientConnection conn,
            HttpRoute route,
            HttpContext context);

    /**
     * Determines if the given connection has been fully established and
     * marked as route complete.
     *
     * @param conn the managed connection.
     */
    boolean isRouteComplete(NHttpClientConnection conn);

    /**
     * Closes idle connections in the pool.
     * <p>
     * Open connections in the pool that have not been used for the
     * timespan given by the argument will be closed.
     * Currently allocated connections are not subject to this method.
     * Times will be checked with milliseconds precision
     *
     * All expired connections will also be closed.
     *
     * @param idletime  the idle time of connections to be closed
     * @param tunit     the unit for the {@code idletime}
     *
     * @see #closeExpiredConnections()
     */
    void closeIdleConnections(long idletime, TimeUnit tunit);

    /**
     * Closes all expired connections in the pool.
     * <p>
     * Open connections in the pool that have not been used for
     * the timespan defined when the connection was released will be closed.
     * Currently allocated connections are not subject to this method.
     * Times will be checked with milliseconds precision.
     */
    void closeExpiredConnections();

    /**
     * Starts the underlying I/O reactor and initiates the dispatch of
     * I/O event notifications to the given {@link IOEventDispatch}.
     */
    void execute(IOEventDispatch eventDispatch) throws IOException;

    /**
     * Shuts down this connection manager and releases allocated resources.
     * This includes closing all connections, whether they are currently
     * used or not.
     */
    void shutdown() throws IOException;

}
