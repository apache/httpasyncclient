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

package org.apache.http.localserver;

import java.io.IOException;
import java.util.Locale;

import org.apache.http.Consts;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NByteArrayEntity;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;

/**
 * A handler that generates random data.
 */
public class RandomHandler implements HttpRequestHandler {

    private final static byte[] RANGE;
    static {
        RANGE = ("abcdefghijklmnopqrstuvwxyz" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "0123456789"
        ).getBytes(Consts.ASCII);
    }

    /**
     * Handles a request by generating random data.
     * The length of the response can be specified in the request URI
     * as a number after the last /. For example /random/whatever/20
     * will generate 20 random bytes in the printable ASCII range.
     * If the request URI ends with /, a random number of random bytes
     * is generated, but at least one.
     *
     * @param request   the request
     * @param response  the response
     * @param context   the context
     *
     * @throws HttpException    in case of a problem
     * @throws IOException      in case of an IO problem
     */
    public void handle(final HttpRequest request,
                       final HttpResponse response,
                       final HttpContext context)
        throws HttpException, IOException {

        final String method = request.getRequestLine().getMethod().toUpperCase(Locale.ROOT);
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            throw new MethodNotSupportedException
                (method + " not supported by " + getClass().getName());
        }

        final String uri = request.getRequestLine().getUri();
        final int  slash = uri.lastIndexOf('/');
        int length = -1;
        if (slash < uri.length()-1) {
            try {
                // no more than Integer, 2 GB ought to be enough for anybody
                length = Integer.parseInt(uri.substring(slash+1));

                if (length < 0) {
                    response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
                    response.setReasonPhrase("LENGTH " + length);
                }
            } catch (final NumberFormatException nfx) {
                response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
                response.setReasonPhrase(nfx.toString());
            }
        } else {
            // random length, but make sure at least something is sent
            length = 1 + (int)(Math.random() * 79.0);
        }

        if (length >= 0) {
            final byte[] data = new byte[length];
            for (int i = 0; i < length; i++) {
                double value = 0.0;
                // we get 5 random characters out of one random value
                if (i%5 == 0) {
                    value = Math.random();
                }
                value = value * RANGE.length;
                final int d = (int) value;
                value = value - d;
                data[i] = RANGE[d];
            }
            final NByteArrayEntity bae = new NByteArrayEntity(data, ContentType.DEFAULT_TEXT);
            response.setEntity(bae);
        }
    }

}
