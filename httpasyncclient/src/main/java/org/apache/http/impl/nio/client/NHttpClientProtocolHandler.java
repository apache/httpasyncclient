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
import java.net.ProtocolException;
import java.net.SocketTimeoutException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.NHttpClientHandler;
import org.apache.http.nio.NHttpConnection;
import org.apache.http.nio.protocol.HttpAsyncClientExchangeHandler;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.protocol.HttpContext;

/**
 * Fully asynchronous HTTP client side protocol handler that implements the
 * essential requirements of the HTTP protocol for the server side message
 * processing as described by RFC 2616. It is capable of executing HTTP requests
 * with nearly constant memory footprint. Only HTTP message heads are stored in
 * memory, while content of message bodies is streamed directly from the entity
 * to the underlying channel (and vice versa) using {@link ConsumingNHttpEntity}
 * and {@link ProducingNHttpEntity} interfaces.
 */
class NHttpClientProtocolHandler implements NHttpClientHandler {

    private final Log log = LogFactory.getLog(getClass());

    private static final String HTTP_EXCHNAGE = "http.nio.exchange";

    public NHttpClientProtocolHandler() {
        super();
    }

    private void closeConnection(final NHttpClientConnection conn) {
        try {
            conn.close();
        } catch (IOException ex) {
            try {
                conn.shutdown();
            } catch (IOException ignore) {
                this.log.debug("I/O error terminating connection: " + ex.getMessage(), ex);
            }
        }
    }

    protected void shutdownConnection(final NHttpClientConnection conn) {
        try {
            conn.shutdown();
        } catch (IOException ex) {
            this.log.debug("I/O error terminating connection: " + ex.getMessage(), ex);
        }
    }

    public void connected(final NHttpClientConnection conn, final Object attachment) {
        HttpExchange httpexchange = new HttpExchange();
        HttpContext context = conn.getContext();
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + ": Connected");
        }
        context.setAttribute(HTTP_EXCHNAGE, httpexchange);
        requestReady(conn);
    }

    public void closed(final NHttpClientConnection conn) {
        HttpAsyncClientExchangeHandler<?> handler = getHandler(conn);
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + ": Disconnected");
        }
        if (handler != null) {
            handler.cancel();
        }
    }

    public void exception(final NHttpClientConnection conn, final HttpException ex) {
        HttpAsyncClientExchangeHandler<?> handler = getHandler(conn);
        if (this.log.isErrorEnabled()) {
            this.log.error(conn + " HTTP protocol exception: " + ex.getMessage(), ex);
        }
        if (handler != null) {
            handler.failed(ex);
        }
        closeConnection(conn);
    }

    public void exception(final NHttpClientConnection conn, final IOException ex) {
        HttpAsyncClientExchangeHandler<?> handler = getHandler(conn);
        if (this.log.isErrorEnabled()) {
            this.log.error(conn + " I/O error: " + ex.getMessage(), ex);
        }
        if (handler != null) {
            handler.failed(ex);
        }
        shutdownConnection(conn);
    }

    public void requestReady(final NHttpClientConnection conn) {
        HttpExchange httpexchange = getHttpExchange(conn);
        HttpAsyncClientExchangeHandler<?> handler = getHandler(conn);
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Request ready");
        }
        if (httpexchange.getRequestState() != MessageState.READY) {
            return;
        }
        if (handler == null || handler.isDone()) {
            if (this.log.isDebugEnabled()) {
                this.log.debug(conn + " No request submitted");
            }
            return;
        }
        try {
            HttpContext context = handler.getContext();
            HttpRequest request = handler.generateRequest();
            httpexchange.setRequest(request);

            HttpEntityEnclosingRequest entityReq = null;
            if (request instanceof HttpEntityEnclosingRequest) {
                entityReq = (HttpEntityEnclosingRequest) request;
            }

            conn.submitRequest(request);

            if (entityReq != null) {
                if (entityReq.expectContinue()) {
                    int timeout = conn.getSocketTimeout();
                    httpexchange.setTimeout(timeout);
                    timeout = request.getParams().getIntParameter(
                            CoreProtocolPNames.WAIT_FOR_CONTINUE, 3000);
                    conn.setSocketTimeout(timeout);
                    httpexchange.setRequestState(MessageState.ACK);
                } else {
                    httpexchange.setRequestState(MessageState.BODY_STREAM);
                }
            } else {
                httpexchange.setRequestState(MessageState.COMPLETED);
                handler.requestCompleted(context);
            }
        } catch (IOException ex) {
            if (this.log.isDebugEnabled()) {
                this.log.debug(conn + " I/O error: " + ex.getMessage(), ex);
            }
            shutdownConnection(conn);
            handler.failed(ex);
        } catch (HttpException ex) {
            if (this.log.isDebugEnabled()) {
                this.log.debug(conn + " HTTP protocol exception: " + ex.getMessage(), ex);
            }
            closeConnection(conn);
            handler.failed(ex);
        }
    }

    public void inputReady(final NHttpClientConnection conn, final ContentDecoder decoder) {
        HttpExchange httpexchange = getHttpExchange(conn);
        HttpAsyncClientExchangeHandler<?> handler = getHandler(conn);
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Input ready");
        }
        try {
            handler.consumeContent(decoder, conn);
            if (this.log.isDebugEnabled()) {
                this.log.debug(conn + " Content decoder " + decoder);
            }
            if (decoder.isCompleted()) {
                processResponse(conn, httpexchange, handler);
            }
        } catch (IOException ex) {
            if (this.log.isDebugEnabled()) {
                this.log.debug("I/O error: " + ex.getMessage(), ex);
            }
            shutdownConnection(conn);
            handler.failed(ex);
        }
    }

    public void outputReady(final NHttpClientConnection conn, final ContentEncoder encoder) {
        HttpExchange httpexchange = getHttpExchange(conn);
        HttpAsyncClientExchangeHandler<?> handler = getHandler(conn);
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Output ready");
        }
        try {
            HttpContext context = handler.getContext();
            if (httpexchange.getRequestState() == MessageState.ACK) {
                conn.suspendOutput();
                return;
            }
            handler.produceContent(encoder, conn);
            if (this.log.isDebugEnabled()) {
                this.log.debug(conn + " Content encoder " + encoder);
            }
            if (encoder.isCompleted()) {
                httpexchange.setRequestState(MessageState.COMPLETED);
                handler.requestCompleted(context);
            }
        } catch (IOException ex) {
            if (this.log.isDebugEnabled()) {
                this.log.debug(conn + " I/O error: " + ex.getMessage(), ex);
            }
            shutdownConnection(conn);
            handler.failed(ex);
        }
    }

    public void responseReceived(final NHttpClientConnection conn) {
        HttpExchange httpexchange = getHttpExchange(conn);
        HttpAsyncClientExchangeHandler<?> handler = getHandler(conn);
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Response received");
        }
        try {
            HttpResponse response = conn.getHttpResponse();
            HttpRequest request = httpexchange.getRequest();

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode < HttpStatus.SC_OK) {
                // 1xx intermediate response
                if (statusCode != HttpStatus.SC_CONTINUE) {
                    throw new ProtocolException(
                            "Unexpected response: " + response.getStatusLine());
                }
                if (httpexchange.getRequestState() == MessageState.ACK) {
                    int timeout = httpexchange.getTimeout();
                    conn.setSocketTimeout(timeout);
                    conn.requestOutput();
                    httpexchange.setRequestState(MessageState.BODY_STREAM);
                }
                return;
            } else {
                httpexchange.setResponse(response);
                if (httpexchange.getRequestState() == MessageState.ACK) {
                    int timeout = httpexchange.getTimeout();
                    conn.setSocketTimeout(timeout);
                    conn.resetOutput();
                    httpexchange.setRequestState(MessageState.COMPLETED);
                } else if (httpexchange.getRequestState() == MessageState.BODY_STREAM) {
                    // Early response
                    int timeout = httpexchange.getTimeout();
                    conn.setSocketTimeout(timeout);
                    conn.suspendOutput();
                    conn.resetOutput();
                    httpexchange.invalidate();
                }
            }
            handler.responseReceived(response);
            if (!canResponseHaveBody(request, response)) {
                conn.resetInput();
                processResponse(conn, httpexchange, handler);
            }
        } catch (IOException ex) {
            if (this.log.isDebugEnabled()) {
                this.log.debug("I/O error: " + ex.getMessage(), ex);
            }
            shutdownConnection(conn);
            handler.failed(ex);
        } catch (HttpException ex) {
            if (this.log.isDebugEnabled()) {
                this.log.debug("HTTP protocol exception: " + ex.getMessage(), ex);
            }
            closeConnection(conn);
            handler.failed(ex);
        }
    }

    public void timeout(final NHttpClientConnection conn) {
        HttpExchange httpexchange = getHttpExchange(conn);
        HttpAsyncClientExchangeHandler<?> handler = getHandler(conn);
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Timeout");
        }
        try {
            if (httpexchange.getRequestState() == MessageState.ACK) {
                int timeout = httpexchange.getTimeout();
                conn.setSocketTimeout(timeout);
                conn.requestOutput();
                httpexchange.setRequestState(MessageState.BODY_STREAM);
            } else {
                handler.failed(new SocketTimeoutException());
                if (conn.getStatus() == NHttpConnection.ACTIVE) {
                    conn.close();
                    if (conn.getStatus() == NHttpConnection.CLOSING) {
                        // Give the connection some grace time to
                        // close itself nicely
                        conn.setSocketTimeout(250);
                    }
                } else {
                    conn.shutdown();
                }
            }
        } catch (IOException ex) {
            if (this.log.isDebugEnabled()) {
                this.log.debug("I/O error: " + ex.getMessage(), ex);
            }
            shutdownConnection(conn);
            handler.failed(ex);
        }
    }

    private HttpExchange getHttpExchange(final NHttpClientConnection conn) {
        return (HttpExchange) conn.getContext().getAttribute(HTTP_EXCHNAGE);
    }

    private HttpAsyncClientExchangeHandler<?> getHandler(final NHttpClientConnection conn) {
        return (HttpAsyncClientExchangeHandler<?>) conn.getContext().getAttribute(
                DefaultAsyncRequestDirector.HTTP_EXCHANGE_HANDLER);
    }

    private void processResponse(
            final NHttpClientConnection conn,
            final HttpExchange httpexchange,
            final HttpAsyncClientExchangeHandler<?> handler) throws IOException {
        if (!httpexchange.isValid()) {
            conn.close();
        }
        HttpContext context = handler.getContext();
        HttpRequest request = httpexchange.getRequest();
        HttpResponse response = httpexchange.getResponse();

        String method = request.getRequestLine().getMethod();
        int status = response.getStatusLine().getStatusCode();
        if (method.equalsIgnoreCase("CONNECT") && status == HttpStatus.SC_OK) {
            this.log.debug("CONNECT method succeeded");
            conn.resetInput();
        } else {
            ConnectionReuseStrategy reuseStrategy = handler.getConnectionReuseStrategy();
            if (!reuseStrategy.keepAlive(response, context)) {
                this.log.debug("Connection cannot be kept alive");
                conn.close();
            }
        }
        if (this.log.isDebugEnabled()) {
            this.log.debug(conn + " Response processed");
        }
        handler.responseCompleted(context);
        httpexchange.reset();
    }

    private boolean canResponseHaveBody(final HttpRequest request, final HttpResponse response) {

        String method = request.getRequestLine().getMethod();
        int status = response.getStatusLine().getStatusCode();

        if (method.equalsIgnoreCase("HEAD")) {
            return false;
        }
        if (method.equalsIgnoreCase("CONNECT") && status == HttpStatus.SC_OK) {
            return false;
        }
        return status >= HttpStatus.SC_OK
            && status != HttpStatus.SC_NO_CONTENT
            && status != HttpStatus.SC_NOT_MODIFIED
            && status != HttpStatus.SC_RESET_CONTENT;
    }

}
