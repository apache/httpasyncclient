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

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;

class HttpExchange {

    private MessageState requestState;
    private MessageState responseState;
    private HttpRequest request;
    private HttpResponse response;
    private boolean valid;
    private int timeout;

    public HttpExchange() {
        super();
        this.valid = true;
        this.requestState = MessageState.READY;
        this.responseState = MessageState.READY;
    }

    public MessageState getRequestState() {
        return this.requestState;
    }

    public void setRequestState(final MessageState state) {
        this.requestState = state;
    }

    public MessageState getResponseState() {
        return this.responseState;
    }

    public void setResponseState(final MessageState state) {
        this.responseState = state;
    }

    public HttpRequest getRequest() {
        return this.request;
    }

    public void setRequest(final HttpRequest request) {
        this.request = request;
    }

    public HttpResponse getResponse() {
        return this.response;
    }

    public void setResponse(final HttpResponse response) {
        this.response = response;
    }

    public int getTimeout() {
        return this.timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public void resetInput() {
        this.response = null;
        this.responseState = MessageState.READY;
    }

    public void resetOutput() {
        this.request = null;
        this.requestState = MessageState.READY;
    }

    public void reset() {
        resetInput();
        resetOutput();
    }

    public boolean isValid() {
        return this.valid;
    }

    public void invalidate() {
        this.valid = false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[request state=");
        buf.append(this.requestState);
        if (this.request != null) {
            buf.append(",request=");
            buf.append(this.request.getRequestLine());
        }
        buf.append(",response state=");
        buf.append(this.responseState);
        if (this.response != null) {
            buf.append(",response=");
            buf.append(this.response.getStatusLine());
        }
        buf.append(",valid=");
        buf.append(this.valid);
        buf.append("]");
        return buf.toString();
    }

}