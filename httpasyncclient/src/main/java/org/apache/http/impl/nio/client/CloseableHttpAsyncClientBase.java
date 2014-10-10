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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.nio.NHttpClientEventHandler;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.util.Asserts;

abstract class CloseableHttpAsyncClientBase extends CloseableHttpPipeliningClient {

    private final Log log = LogFactory.getLog(getClass());

    static enum Status {INACTIVE, ACTIVE, STOPPED}

    private final NHttpClientConnectionManager connmgr;
    private final Thread reactorThread;

    private final AtomicReference<Status> status;

    public CloseableHttpAsyncClientBase(
            final NHttpClientConnectionManager connmgr,
            final ThreadFactory threadFactory,
            final NHttpClientEventHandler handler) {
        super();
        this.connmgr = connmgr;
        if (threadFactory != null && handler != null) {
            this.reactorThread = threadFactory.newThread(new Runnable() {

                @Override
                public void run() {
                    try {
                        final IOEventDispatch ioEventDispatch = new InternalIODispatch(handler);
                        connmgr.execute(ioEventDispatch);
                    } catch (final Exception ex) {
                        log.error("I/O reactor terminated abnormally", ex);
                    } finally {
                        status.set(Status.STOPPED);
                    }
                }

            });
        } else {
            this.reactorThread = null;
        }
        this.status = new AtomicReference<Status>(Status.INACTIVE);
    }

    @Override
    public void start() {
        if (this.status.compareAndSet(Status.INACTIVE, Status.ACTIVE)) {
            if (this.reactorThread != null) {
                this.reactorThread.start();
            }
        }
    }

    protected void ensureRunning() {
        final Status currentStatus = this.status.get();
        Asserts.check(currentStatus == Status.ACTIVE, "Request cannot be executed; " +
                "I/O reactor status: %s", currentStatus);
    }

    @Override
    public void close() {
        if (this.status.compareAndSet(Status.ACTIVE, Status.STOPPED)) {
            if (this.reactorThread != null) {
                try {
                    this.connmgr.shutdown();
                } catch (IOException ex) {
                    this.log.error("I/O error shutting down connection manager", ex);
                }
                try {
                    this.reactorThread.join();
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public boolean isRunning() {
        return this.status.get() == Status.ACTIVE;
    }

}
