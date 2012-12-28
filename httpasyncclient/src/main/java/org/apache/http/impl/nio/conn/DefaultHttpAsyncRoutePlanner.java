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

import java.net.InetAddress;

import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.params.ConnRouteParams;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.nio.conn.scheme.AsyncScheme;
import org.apache.http.nio.conn.scheme.AsyncSchemeRegistry;
import org.apache.http.nio.conn.scheme.LayeringStrategy;
import org.apache.http.protocol.HttpContext;

public class DefaultHttpAsyncRoutePlanner implements HttpRoutePlanner {

    private final AsyncSchemeRegistry schemeRegistry;

    public DefaultHttpAsyncRoutePlanner(final AsyncSchemeRegistry schemeRegistry) {
        super();
        this.schemeRegistry = schemeRegistry;
    }

    private AsyncSchemeRegistry getSchemeRegistry(final HttpContext context) {
        AsyncSchemeRegistry reg = (AsyncSchemeRegistry) context.getAttribute(
                ClientContext.SCHEME_REGISTRY);
        if (reg == null) {
            reg = this.schemeRegistry;
        }
        return reg;
    }
    
    public HttpRoute determineRoute(
            final HttpHost target,
            final HttpRequest request,
            final HttpContext context) throws HttpException {
        if (request == null) {
            throw new IllegalStateException("Request may not be null");
        }
        HttpRoute route = ConnRouteParams.getForcedRoute(request.getParams());
        if (route != null) {
            return route;
        }
        if (target == null) {
            throw new IllegalStateException("Target host may be null");
        }
        InetAddress local = ConnRouteParams.getLocalAddress(request.getParams());
        HttpHost proxy = ConnRouteParams.getDefaultProxy(request.getParams());
        AsyncScheme scheme;
        try {
            AsyncSchemeRegistry registry = getSchemeRegistry(context);
            scheme = registry.getScheme(target);
        } catch (IllegalStateException ex) {
            throw new HttpException(ex.getMessage());
        }
        LayeringStrategy layeringStrategy = scheme.getLayeringStrategy();
        boolean secure = layeringStrategy != null && layeringStrategy.isSecure();
        if (proxy == null) {
            route = new HttpRoute(target, local, secure);
        } else {
            route = new HttpRoute(target, local, proxy, secure);
        }
        return route;
    }

}
