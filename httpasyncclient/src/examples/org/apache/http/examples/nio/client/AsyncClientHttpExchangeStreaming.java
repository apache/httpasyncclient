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
package org.apache.http.examples.nio.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.util.concurrent.Future;

import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.impl.nio.client.DefaultHttpAsyncClient;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.client.HttpAsyncRequestProducer;
import org.apache.http.nio.client.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;

public class AsyncClientHttpExchangeStreaming {

    public static void main(String[] args) throws Exception {
        HttpAsyncClient httpclient = new DefaultHttpAsyncClient();
        httpclient.start();
        try {
            Future<Boolean> future = httpclient.execute(
                    new MyRequestProducer(), new MyResponseConsumer(), null);
            Boolean result = future.get();
            if (result != null && result.booleanValue()) {
                System.out.println("Request successfully executed");
            } else {
                System.out.println("Request failed");
            }
            System.out.println("Shutting down");
        } finally {
            httpclient.shutdown();
        }
        System.out.println("Done");
    }

    static class MyRequestProducer implements HttpAsyncRequestProducer {

        public HttpRequest generateRequest() throws IOException, HttpException {
            BasicHttpRequest request = new BasicHttpRequest("GET", "/");
            return request;
        }

        public HttpHost getTarget() {
            return new HttpHost("www.apache.org");
        }

        public boolean isRepeatable() {
            return true;
        }

        public void produceContent(
                final ContentEncoder encoder, final IOControl ioctrl) throws IOException {
            // Should never be called for non entity enclosing requests
        }

        public void resetRequest() {
        }

    }

    static class MyResponseConsumer implements HttpAsyncResponseConsumer<Boolean> {

        private Charset charset;
        private CharsetDecoder decoder;
        private ByteBuffer bbuf;
        private CharBuffer cbuf;

        private volatile Boolean result;

        public synchronized void responseReceived(
                final HttpResponse response) throws IOException, HttpException {
            System.out.println("HTTP response: " + response.getStatusLine());
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                String s = EntityUtils.getContentCharSet(entity);
                if (s == null) {
                    s = HTTP.DEFAULT_CONTENT_CHARSET;
                }
                this.charset = Charset.forName(s);
                this.decoder = this.charset.newDecoder();
                this.bbuf = ByteBuffer.allocate(1024);
                this.cbuf = CharBuffer.allocate(1024);
            }
        }

        public synchronized void consumeContent(
                final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
            int bytesRead;
            do {
                bytesRead = decoder.read(this.bbuf);
                this.bbuf.flip();
                CoderResult result = this.decoder.decode(this.bbuf, this.cbuf, decoder.isCompleted());
                if (result.isError()) {
                    result.throwException();
                }
                this.bbuf.compact();
                this.cbuf.flip();
                while (this.cbuf.hasRemaining()) {
                    System.out.print(this.cbuf.get());
                }
                this.cbuf.compact();
            } while (bytesRead > 0);
        }

        public void responseCompleted() {
            this.result = Boolean.TRUE;
        }

        public void cancel() {
            this.result = Boolean.FALSE;
        }

        public void failed(final Exception ex) {
            this.result = Boolean.FALSE;
            ex.printStackTrace();
        }

        public Boolean getResult() {
            return this.result;
        }

        public boolean isDone() {
            return this.result != null;
        }

    }

}
