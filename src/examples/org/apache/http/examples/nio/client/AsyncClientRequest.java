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

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.impl.nio.client.BasicHttpAsyncClient;
import org.apache.http.impl.nio.conn.BasicIOSessionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.params.SyncBasicHttpParams;

public class AsyncClientRequest {

    public static void main(String[] args) throws Exception {
        HttpParams params = new SyncBasicHttpParams();
        params
            .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 5000)
            .setIntParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 10000)
            .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
            .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true)
            .setParameter(CoreProtocolPNames.USER_AGENT, "HttpComponents/1.1");
        DefaultConnectingIOReactor ioReactor = new DefaultConnectingIOReactor(1, params);
        BasicIOSessionManager sessmrg = new BasicIOSessionManager(ioReactor, params);
        sessmrg.setTotalMax(5);
        sessmrg.setDefaultMaxPerHost(3);

        HttpAsyncClient httpclient = new BasicHttpAsyncClient(
                ioReactor,
                sessmrg,
                params);

        httpclient.start();
        try {
            HttpHost target = new HttpHost("www.apache.org", 80);
            Queue<Future<HttpResponse>> queue = new LinkedList<Future<HttpResponse>>();
            for (int i = 0; i < 10; i++) {
                BasicHttpRequest request = new BasicHttpRequest("GET", "/");
                queue.add(httpclient.execute(target, request, null));
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
