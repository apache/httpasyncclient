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

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Future;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.nio.client.DefaultHttpAsyncClient;
import org.apache.http.nio.client.HttpAsyncClient;

public class AsyncClientRequest {

    public static void main(String[] args) throws Exception {
        HttpAsyncClient httpclient = new DefaultHttpAsyncClient();
        httpclient.start();
        try {
            Queue<Future<HttpResponse>> queue = new LinkedList<Future<HttpResponse>>();
            for (int i = 0; i < 10; i++) {
                HttpGet request = new HttpGet("http://www.apache.org/");
                queue.add(httpclient.execute(request, null));
            }
            while (!queue.isEmpty()) {
                Future<HttpResponse> future = queue.remove();
                HttpResponse response = future.get();
                System.out.println("Response: " + response.getStatusLine());
            }

            System.out.println("Shutting down");
        } finally {
            httpclient.shutdown();
        }
        System.out.println("Done");
    }

}
