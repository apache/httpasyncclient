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
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpHost;
import org.apache.http.conn.ConnectionReleaseTrigger;
import org.apache.http.conn.HttpRoutedConnection;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;

public interface ManagedClientConnection
    extends HttpRoutedConnection, NHttpClientConnection, ConnectionReleaseTrigger {

    Object getState();

    void setState(Object state);

    void markReusable();

    void markNonReusable();

    boolean isReusable();

    void open(HttpRoute route, HttpContext context, HttpParams params) throws IOException;

    void tunnelTarget(HttpParams params) throws IOException;

    void tunnelProxy(HttpHost next, HttpParams params) throws IOException;

    void layerProtocol(HttpContext context, HttpParams params) throws IOException;

    void setIdleDuration(long duration, TimeUnit tunit);

}
