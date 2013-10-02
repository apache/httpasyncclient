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

import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.BrowserCompatHostnameVerifier;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.StrictHostnameVerifier;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.ssl.SSLIOSession;
import org.apache.http.nio.reactor.ssl.SSLMode;
import org.apache.http.nio.reactor.ssl.SSLSetupHandler;
import org.apache.http.util.Args;
import org.apache.http.util.Asserts;
import org.apache.http.util.TextUtils;

/**
 * TLS/SSL transport level security strategy.
 *
 * @since 4.0
 */
public class SSLIOSessionStrategy implements SchemeIOSessionStrategy {

    public static final X509HostnameVerifier ALLOW_ALL_HOSTNAME_VERIFIER =
            new AllowAllHostnameVerifier();

    public static final X509HostnameVerifier BROWSER_COMPATIBLE_HOSTNAME_VERIFIER =
            new BrowserCompatHostnameVerifier();

    public static final X509HostnameVerifier STRICT_HOSTNAME_VERIFIER =
            new StrictHostnameVerifier();

    private static String[] split(final String s) {
        if (TextUtils.isBlank(s)) {
            return null;
        }
        return s.split(" *, *");
    }

    public static SSLIOSessionStrategy getDefaultStrategy() {
        return new SSLIOSessionStrategy(
                SSLContexts.createDefault(),
                BROWSER_COMPATIBLE_HOSTNAME_VERIFIER);
    }

    public static SSLIOSessionStrategy getSystemDefaultStrategy() {
        return new SSLIOSessionStrategy(
                SSLContexts.createSystemDefault(),
                split(System.getProperty("https.protocols")),
                split(System.getProperty("https.cipherSuites")),
                BROWSER_COMPATIBLE_HOSTNAME_VERIFIER);
    }

    private final SSLContext sslContext;
    private final String[] supportedProtocols;
    private final String[] supportedCipherSuites;
    private final X509HostnameVerifier hostnameVerifier;

    public SSLIOSessionStrategy(
            final SSLContext sslContext,
            final String[] supportedProtocols,
            final String[] supportedCipherSuites,
            final X509HostnameVerifier hostnameVerifier) {
        super();
        this.sslContext = Args.notNull(sslContext, "SSL context");
        this.supportedProtocols = supportedProtocols;
        this.supportedCipherSuites = supportedCipherSuites;
        this.hostnameVerifier = hostnameVerifier != null ? hostnameVerifier : BROWSER_COMPATIBLE_HOSTNAME_VERIFIER;
    }

    public SSLIOSessionStrategy(
            final SSLContext sslcontext,
            final X509HostnameVerifier hostnameVerifier) {
        this(sslcontext, null, null, hostnameVerifier);
    }

    public SSLIOSessionStrategy(final SSLContext sslcontext) {
        this(sslcontext, null, null, BROWSER_COMPATIBLE_HOSTNAME_VERIFIER);
    }

    public SSLIOSession upgrade(final HttpHost host, final IOSession iosession) throws IOException {
        Asserts.check(!(iosession instanceof SSLIOSession), "I/O session is already upgraded to TLS/SSL");
        final SSLIOSession ssliosession = new SSLIOSession(
            iosession,
            SSLMode.CLIENT,
            this.sslContext,
            new SSLSetupHandler() {

                public void initalize(
                        final SSLEngine sslengine) throws SSLException {
                    if (supportedProtocols != null) {
                        sslengine.setEnabledProtocols(supportedProtocols);
                    }
                    if (supportedCipherSuites != null) {
                        sslengine.setEnabledCipherSuites(supportedCipherSuites);
                    }
                    initializeEngine(sslengine);
                }

                public void verify(
                        final IOSession iosession,
                        final SSLSession sslsession) throws SSLException {
                    verifySession(host, iosession, sslsession);
                }

        });
        iosession.setAttribute(SSLIOSession.SESSION_KEY, ssliosession);
        ssliosession.initialize();
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
