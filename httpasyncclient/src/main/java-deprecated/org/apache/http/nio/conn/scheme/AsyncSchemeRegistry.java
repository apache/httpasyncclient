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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.http.HttpHost;

/**
 * A set of supported protocol {@link AsyncScheme}s.
 * Schemes are identified by lowercase names.
 *
 */
@Deprecated
public final class AsyncSchemeRegistry {

    /** The available schemes in this registry. */
    private final Map<String, AsyncScheme> registeredSchemes;

    /**
     * Creates a new, empty scheme registry.
     */
    public AsyncSchemeRegistry() {
        super();
        registeredSchemes = new ConcurrentHashMap<String, AsyncScheme>();
    }

    /**
     * Obtains a scheme by name.
     *
     * @param name      the name of the scheme to look up (in lowercase)
     *
     * @return  the scheme, never <code>null</code>
     *
     * @throws IllegalStateException
     *          if the scheme with the given name is not registered
     */
    public final AsyncScheme getScheme(final String name) {
        final AsyncScheme found = get(name);
        if (found == null) {
            throw new IllegalStateException
                ("Scheme '"+name+"' not registered.");
        }
        return found;
    }

    /**
     * Obtains the scheme for a host.
     * Convenience method for <code>getScheme(host.getSchemeName())</pre>
     *
     * @param host      the host for which to obtain the scheme
     *
     * @return  the scheme for the given host, never <code>null</code>
     *
     * @throws IllegalStateException
     *          if a scheme with the respective name is not registered
     */
    public final AsyncScheme getScheme(final HttpHost host) {
        if (host == null) {
            throw new IllegalArgumentException("Host must not be null.");
        }
        return getScheme(host.getSchemeName());
    }

    /**
     * Obtains a scheme by name, if registered.
     *
     * @param name      the name of the scheme to look up (in lowercase)
     *
     * @return  the scheme, or
     *          <code>null</code> if there is none by this name
     */
    public final AsyncScheme get(final String name) {
        if (name == null) {
            throw new IllegalArgumentException("Name must not be null.");
        }

        // leave it to the caller to use the correct name - all lowercase
        //name = name.toLowerCase(Locale.ENGLISH);
        final AsyncScheme found = registeredSchemes.get(name);
        return found;
    }

    /**
     * Registers a scheme.
     * The scheme can later be retrieved by its name
     * using {@link #getScheme(String) getScheme} or {@link #get get}.
     *
     * @param sch       the scheme to register
     *
     * @return  the scheme previously registered with that name, or
     *          <code>null</code> if none was registered
     */
    public final AsyncScheme register(final AsyncScheme sch) {
        if (sch == null) {
            throw new IllegalArgumentException("Scheme must not be null.");
        }

        final AsyncScheme old = registeredSchemes.put(sch.getName(), sch);
        return old;
    }

    /**
     * Unregisters a scheme.
     *
     * @param name      the name of the scheme to unregister (in lowercase)
     *
     * @return  the unregistered scheme, or
     *          <code>null</code> if there was none
     */
    public final AsyncScheme unregister(final String name) {
        if (name == null) {
            throw new IllegalArgumentException("Name must not be null.");
        }

        // leave it to the caller to use the correct name - all lowercase
        //name = name.toLowerCase(Locale.ENGLISH);
        final AsyncScheme gone = registeredSchemes.remove(name);
        return gone;
    }

    /**
     * Obtains the names of the registered schemes.
     *
     * @return  List containing registered scheme names.
     */
    public final List<String> getSchemeNames() {
        return new ArrayList<String>(registeredSchemes.keySet());
    }

    /**
     * Populates the internal collection of registered {@link AsyncScheme protocol schemes}
     * with the content of the map passed as a parameter.
     *
     * @param map protocol schemes
     */
    public void setItems(final Map<String, AsyncScheme> map) {
        if (map == null) {
            return;
        }
        registeredSchemes.clear();
        registeredSchemes.putAll(map);
    }

}

