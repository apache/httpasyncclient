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
package org.apache.http.impl.nio.pool;

class BasicPoolEntryCallback implements PoolEntryCallback<String, PoolEntry<String>> {

    private PoolEntry<String> entry;
    private Exception ex;
    private boolean completed;
    private boolean failed;
    private boolean cancelled;

    public void completed(final PoolEntry<String> entry) {
        this.entry = entry;
        this.completed = true;
    }

    public PoolEntry<String> getEntry() {
        return this.entry;
    }

    public Exception getException() {
        return this.ex;
    }

    public void failed(final Exception ex) {
        this.ex = ex;
        this.failed = true;
    }

    public void cancelled() {
        this.cancelled = true;
    }

    public boolean isCompleted() {
        return this.completed;
    }

    public boolean isFailed() {
        return this.failed;
    }

    public boolean isCancelled() {
        return this.cancelled;
    }

}