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
package org.apache.http.nio.client.methods;

import java.net.URI;

import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.client.methods.HttpPost;

/**
 * @deprecated use {@link HttpAsyncMethods#createPost(URI, String, String, String)}
 *  or {@link HttpAsyncMethods#createPost(URI, byte[], String)}
 */
@Deprecated
public class HttpAsyncPost extends BaseHttpAsyncEntityRequestProducer {

    public HttpAsyncPost(final URI requestURI, final byte[] content, final String contentType) {
        super(requestURI, content, contentType);
    }

    public HttpAsyncPost(final URI requestURI, final String content, String mimeType, String charset) {
        super(requestURI, content, mimeType, charset);
    }

    public HttpAsyncPost(final URI requestURI, final String content) {
        super(requestURI, content, null, null);
    }

    public HttpAsyncPost(final String requestURI, final byte[] content, final String contentType) {
        super(URI.create(requestURI), content, contentType);
    }

    public HttpAsyncPost(final String requestURI, final String content, String mimeType, String charset) {
        super(URI.create(requestURI), content, mimeType, charset);
    }

    public HttpAsyncPost(final String requestURI, final String content) {
        super(URI.create(requestURI), content, null, null);
    }

    @Override
    protected HttpEntityEnclosingRequest createRequest(final URI requestURI, final HttpEntity entity) {
        HttpPost httppost = new HttpPost(requestURI);
        httppost.setEntity(entity);
        return httppost;
    }

}
