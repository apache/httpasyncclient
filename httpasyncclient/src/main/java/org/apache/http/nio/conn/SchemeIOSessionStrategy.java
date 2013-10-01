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
package org.apache.http.nio.conn;

import org.apache.http.HttpHost;
import org.apache.http.nio.reactor.IOSession;

import java.io.IOException;

/**
 * I/O session layering strategy for complex protocol schemes, which employ
 * a transport level security protocol to secure HTTP communication
 * (in other words those schemes 'layer' HTTP on top of a transport level
 * protocol such as TLS/SSL).
 *
 * @since 4.0
 */
public interface SchemeIOSessionStrategy {

    /**
     * Determines whether or not protocol layering is required. If this method
     * returns <code>false<code/> the {@link #upgrade(org.apache.http.HttpHost,
     * org.apache.http.nio.reactor.IOSession) upgrade} method  is expected
     * to have no effect and should not be called.
     */
    boolean isLayeringRequired();

    /**
     * Decorates the original {@link IOSession} with a transport level security
     * protocol implementation.
     * @param host the target host.
     * @param iosession the I/O session.
     * @return upgraded I/O session.
     */
    IOSession upgrade(HttpHost host, IOSession iosession) throws IOException;

}
