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
package org.apache.http.nio.conn.scheme;

import java.util.Locale;

import org.apache.http.util.Args;
import org.apache.http.util.LangUtils;

@Deprecated
public final class AsyncScheme {

    /** The name of this scheme, in lowercase. (e.g. http, https) */
    private final String name;

    /** The layering strategy for this scheme, if applicable */
    private final LayeringStrategy strategy;

    /** The default port for this scheme */
    private final int defaultPort;

    /** A string representation, for {@link #toString toString}. */
    private String stringRep;
    /*
     *  This is used to cache the result of the toString() method
     *  Since the method always generates the same value, there's no
     *  need to synchronize, and it does not affect immutability.
    */

    public AsyncScheme(final String name, final int port, final LayeringStrategy strategy) {
        Args.notNull(name, "Scheme name");
        if ((port <= 0) || (port > 0xffff)) {
            throw new IllegalArgumentException("Port is invalid: " + port);
        }
        this.name = name.toLowerCase(Locale.ENGLISH);
        this.strategy = strategy;
        this.defaultPort = port;
    }

    public final int getDefaultPort() {
        return defaultPort;
    }

    public final LayeringStrategy getLayeringStrategy() {
        return this.strategy;
    }

    public final String getName() {
        return name;
    }

    public final int resolvePort(final int port) {
        return port <= 0 ? defaultPort : port;
    }

    @Override
    public final String toString() {
        if (stringRep == null) {
            final StringBuilder buffer = new StringBuilder();
            buffer.append(this.name);
            buffer.append(':');
            buffer.append(Integer.toString(this.defaultPort));
            stringRep = buffer.toString();
        }
        return stringRep;
    }

    @Override
    public final boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof AsyncScheme) {
            final AsyncScheme that = (AsyncScheme) obj;
            return this.name.equals(that.name)
                && this.defaultPort == that.defaultPort
                && this.strategy.equals(that.strategy);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        int hash = LangUtils.HASH_SEED;
        hash = LangUtils.hashCode(hash, this.defaultPort);
        hash = LangUtils.hashCode(hash, this.name);
        hash = LangUtils.hashCode(hash, this.strategy);
        return hash;
    }

}
