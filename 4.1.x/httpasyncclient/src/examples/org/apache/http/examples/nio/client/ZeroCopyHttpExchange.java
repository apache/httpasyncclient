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

import java.io.File;
import java.util.concurrent.Future;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.nio.client.methods.ZeroCopyConsumer;
import org.apache.http.nio.client.methods.ZeroCopyPost;

/**
 * This example demonstrates how HttpAsyncClient can be used to upload or download files
 * without creating an intermediate content buffer in memory (zero copy file transfer).
 */
public class ZeroCopyHttpExchange {

    public static void main(final String[] args) throws Exception {
        CloseableHttpAsyncClient httpclient = HttpAsyncClients.createDefault();
        try {
            httpclient.start();
            File upload = new File(args[0]);
            File download = new File(args[1]);
            ZeroCopyPost httpost = new ZeroCopyPost("http://localhost:8080/", upload,
                    ContentType.create("text/plain"));
            ZeroCopyConsumer<File> consumer = new ZeroCopyConsumer<File>(download) {

                @Override
                protected File process(
                        final HttpResponse response,
                        final File file,
                        final ContentType contentType) throws Exception {
                    if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                        throw new ClientProtocolException("Upload failed: " + response.getStatusLine());
                    }
                    return file;
                }

            };
            Future<File> future = httpclient.execute(httpost, consumer, null);
            File result = future.get();
            System.out.println("Response file length: " + result.length());
            System.out.println("Shutting down");
        } finally {
            httpclient.close();
        }
        System.out.println("Done");
    }

}
