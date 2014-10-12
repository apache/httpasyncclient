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
import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.BrowserCompatHostnameVerifier;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.StrictHostnameVerifier;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.conn.util.PublicSuffixMatcher;
import org.apache.http.conn.util.PublicSuffixMatcherLoader;
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

    @Deprecated
    public static final X509HostnameVerifier ALLOW_ALL_HOSTNAME_VERIFIER =
            new AllowAllHostnameVerifier();

    @Deprecated
    public static final X509HostnameVerifier BROWSER_COMPATIBLE_HOSTNAME_VERIFIER =
            new BrowserCompatHostnameVerifier();

    @Deprecated
    public static final X509HostnameVerifier STRICT_HOSTNAME_VERIFIER =
            new StrictHostnameVerifier();

    private static String[] split(final String s) {
        if (TextUtils.isBlank(s)) {
            return null;
        }
        return s.split(" *, *");
    }

    //TODO: remove after upgrade to HttpCore 4.4-beta2 or newer
    private static volatile PublicSuffixMatcher DEFAULT_INSTANCE;

    private static PublicSuffixMatcher getDefaultPublicSuffixMatcher() {
        if (DEFAULT_INSTANCE == null) {
            synchronized (PublicSuffixMatcherLoader.class) {
                if (DEFAULT_INSTANCE == null){
                    final URL url = PublicSuffixMatcherLoader.class.getResource(
                            "/mozilla/public-suffix-list.txt");
                    if (url != null) {
                        try {
                            DEFAULT_INSTANCE = PublicSuffixMatcherLoader.load(url);
                        } catch (IOException ex) {
                            // Should never happen
                            final Log log = LogFactory.getLog(PublicSuffixMatcherLoader.class);
                            if (log.isWarnEnabled()) {
                                log.warn("Failure loading public suffix list from default resource", ex);
                            }
                        }
                    } else {
                        DEFAULT_INSTANCE = new PublicSuffixMatcher(Arrays.asList("com"), null);
                    }
                }
            }
        }
        return DEFAULT_INSTANCE;
    }

    /**
     * @since 4.1
     */
    public static HostnameVerifier getDefaultHostnameVerifier() {
        return new DefaultHostnameVerifier(getDefaultPublicSuffixMatcher());
    }

    public static SSLIOSessionStrategy getDefaultStrategy() {
        return new SSLIOSessionStrategy(
                SSLContexts.createDefault(),
                getDefaultHostnameVerifier());
    }

    public static SSLIOSessionStrategy getSystemDefaultStrategy() {
        return new SSLIOSessionStrategy(
                SSLContexts.createSystemDefault(),
                split(System.getProperty("https.protocols")),
                split(System.getProperty("https.cipherSuites")),
                getDefaultHostnameVerifier());
    }

    private final SSLContext sslContext;
    private final String[] supportedProtocols;
    private final String[] supportedCipherSuites;
    private final HostnameVerifier hostnameVerifier;

    /**
     * @deprecated (4.1) use {@link SSLIOSessionStrategy#SSLIOSessionStrategy(
     *   javax.net.ssl.SSLContext, String[], String[], javax.net.ssl.HostnameVerifier)}
     */
    @Deprecated
    public SSLIOSessionStrategy(
            final SSLContext sslContext,
            final String[] supportedProtocols,
            final String[] supportedCipherSuites,
            final X509HostnameVerifier hostnameVerifier) {
        this(sslContext, supportedProtocols, supportedCipherSuites, (HostnameVerifier) hostnameVerifier);
    }

    /**
     * @deprecated (4.1)
     */
    @Deprecated
    public SSLIOSessionStrategy(
            final SSLContext sslcontext,
            final X509HostnameVerifier hostnameVerifier) {
        this(sslcontext, null, null, (HostnameVerifier) hostnameVerifier);
    }

    /**
     * @since 4.1
     */
    public SSLIOSessionStrategy(
            final SSLContext sslContext,
            final String[] supportedProtocols,
            final String[] supportedCipherSuites,
            final HostnameVerifier hostnameVerifier) {
        super();
        this.sslContext = Args.notNull(sslContext, "SSL context");
        this.supportedProtocols = supportedProtocols;
        this.supportedCipherSuites = supportedCipherSuites;
        this.hostnameVerifier = hostnameVerifier != null ? hostnameVerifier : getDefaultHostnameVerifier();
    }

    /**
     * @since 4.1
     */
    public SSLIOSessionStrategy(
            final SSLContext sslcontext,
            final HostnameVerifier hostnameVerifier) {
        this(sslcontext, null, null, hostnameVerifier);
    }

    public SSLIOSessionStrategy(final SSLContext sslcontext) {
        this(sslcontext, null, null, getDefaultHostnameVerifier());
    }

    @Override
    public SSLIOSession upgrade(final HttpHost host, final IOSession iosession) throws IOException {
        Asserts.check(!(iosession instanceof SSLIOSession), "I/O session is already upgraded to TLS/SSL");
        final SSLIOSession ssliosession = new SSLIOSession(
            iosession,
            SSLMode.CLIENT,
            this.sslContext,
            new SSLSetupHandler() {

                @Override
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

                @Override
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
        if (!this.hostnameVerifier.verify(host.getHostName(), sslsession)) {
            final Certificate[] certs = sslsession.getPeerCertificates();
            final X509Certificate x509 = (X509Certificate) certs[0];
            final X500Principal x500Principal = x509.getSubjectX500Principal();
            throw new SSLPeerUnverifiedException("Host name '" + host.getHostName() + "' does not match " +
                    "the certificate subject provided by the peer (" + x500Principal.toString() + ")");
        }
    }

    @Override
    public boolean isLayeringRequired() {
        return true;
    }

}
