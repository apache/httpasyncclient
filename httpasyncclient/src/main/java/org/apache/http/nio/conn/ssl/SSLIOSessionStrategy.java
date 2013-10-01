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

package org.apache.http.nio.conn.ssl;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.BrowserCompatHostnameVerifier;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.ssl.SSLIOSession;
import org.apache.http.nio.reactor.ssl.SSLMode;
import org.apache.http.nio.reactor.ssl.SSLSetupHandler;
import org.apache.http.util.Asserts;

/**
 * TLS/SSL transport level security strategy.
 *
 * @since 4.0
 */
public class SSLIOSessionStrategy implements SchemeIOSessionStrategy {

    public static SSLIOSessionStrategy getDefaultStrategy() {
        return new SSLIOSessionStrategy(SSLContexts.createDefault());
    }

    public static SSLIOSessionStrategy getSystemDefaultStrategy() {
        return new SSLIOSessionStrategy(SSLContexts.createSystemDefault());
    }

    private final SSLContext sslContext;
    private final X509HostnameVerifier hostnameVerifier;

    public SSLIOSessionStrategy(final SSLContext sslContext, final X509HostnameVerifier hostnameVerifier) {
        super();
        this.sslContext = sslContext;
        this.hostnameVerifier = hostnameVerifier;
    }

    public SSLIOSessionStrategy(final SSLContext sslcontext) {
        this(sslcontext, new BrowserCompatHostnameVerifier());
    }

    public SSLIOSession upgrade(final HttpHost host, final IOSession iosession) {
        Asserts.check(!(iosession instanceof SSLIOSession), "I/O session is already upgraded to TLS/SSL");
        final SSLIOSession ssliosession = new SSLIOSession(
            iosession,
            SSLMode.CLIENT,
            this.sslContext,
            new SSLSetupHandler() {

                public void initalize(
                        final SSLEngine sslengine) throws SSLException {
                    initializeEngine(sslengine);
                }

                public void verify(
                        final IOSession iosession,
                        final SSLSession sslsession) throws SSLException {
                    verifySession(host, iosession, sslsession);
                }

        });
        iosession.setAttribute(SSLIOSession.SESSION_KEY, ssliosession);
        return ssliosession;
    }

    protected void initializeEngine(final SSLEngine engine) {
    }

    protected void verifySession(
            final HttpHost host,
            final IOSession iosession,
            final SSLSession sslsession) throws SSLException {
        final Certificate[] certs = sslsession.getPeerCertificates();
        final X509Certificate x509 = (X509Certificate) certs[0];
        this.hostnameVerifier.verify(host.getHostName(), x509);
    }

    public boolean isLayeringRequired() {
        return true;
    }

}
