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

import java.io.IOException;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.nio.entity.BufferingNHttpEntity;
import org.apache.http.nio.entity.ConsumingNHttpEntity;
import org.apache.http.nio.protocol.NHttpRequestExecutionHandler;
import org.apache.http.nio.util.HeapByteBufferAllocator;
import org.apache.http.protocol.HttpContext;

class InternalRequestExecutionHandler implements NHttpRequestExecutionHandler {

    protected static final String HTTP_EXCHANGE = "http.async.http-exchange";
    
    InternalRequestExecutionHandler() {
        super();
    }

    public void initalizeContext(final HttpContext context, final Object attachment) {
    }

    public void finalizeContext(final HttpContext context) {
        HttpExchangeImpl httpx = (HttpExchangeImpl) context.removeAttribute(HTTP_EXCHANGE);
        if (httpx != null) {
            httpx.cancel();
        }
    }

    public HttpRequest submitRequest(final HttpContext context) {
        HttpExchangeImpl httpx = (HttpExchangeImpl) context.getAttribute(HTTP_EXCHANGE);
        if (httpx != null) {
            return httpx.getRequest();
        } else {
            return null;
        }
    }
    
    public ConsumingNHttpEntity responseEntity(
    		final HttpResponse response, final HttpContext context) throws IOException {
        return new BufferingNHttpEntity(
                response.getEntity(),
                new HeapByteBufferAllocator());
    }

    public void handleResponse(
    		final HttpResponse response, final HttpContext context) throws IOException {
        HttpExchangeImpl httpx = (HttpExchangeImpl) context.removeAttribute(HTTP_EXCHANGE);
        if (httpx != null) {
            httpx.completed(response);
        }
    }

}