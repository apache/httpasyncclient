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

import java.net.InetSocketAddress;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.BrowserCompatHostnameVerifier;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.nio.conn.scheme.LayeringStrategy;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.ssl.SSLIOSession;
import org.apache.http.nio.reactor.ssl.SSLMode;
import org.apache.http.nio.reactor.ssl.SSLSetupHandler;

@SuppressWarnings("deprecation")
public class SSLLayeringStrategy implements LayeringStrategy, SchemeLayeringStrategy {

    public static final String TLS   = "TLS";
    public static final String SSL   = "SSL";
    public static final String SSLV2 = "SSLv2";

    public static SSLLayeringStrategy getDefaultStrategy() {
        return new SSLLayeringStrategy(SSLContexts.createDefault());
    }

    public static SSLLayeringStrategy getSystemDefaultStrategy() {
        return new SSLLayeringStrategy(SSLContexts.createSystemDefault());
    }

    private final SSLContext sslContext;
    private final X509HostnameVerifier hostnameVerifier;

    private static SSLContext createSSLContext(
            final String algorithm,
            final KeyStore keystore,
            final String keystorePassword,
            final KeyStore truststore,
            final SecureRandom random,
            final TrustStrategy trustStrategy)
                throws NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException, KeyManagementException {
        final String algo = algorithm != null ? algorithm : TLS;
        final KeyManagerFactory kmfactory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmfactory.init(keystore, keystorePassword != null ? keystorePassword.toCharArray(): null);
        final KeyManager[] keymanagers =  kmfactory.getKeyManagers();
        final TrustManagerFactory tmfactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmfactory.init(truststore);
        final TrustManager[] trustmanagers = tmfactory.getTrustManagers();
        if (trustmanagers != null && trustStrategy != null) {
            for (int i = 0; i < trustmanagers.length; i++) {
                final TrustManager tm = trustmanagers[i];
                if (tm instanceof X509TrustManager) {
                    trustmanagers[i] = new TrustManagerDecorator(
                            (X509TrustManager) tm, trustStrategy);
                }
            }
        }
        final SSLContext sslcontext = SSLContext.getInstance(algo);
        sslcontext.init(keymanagers, trustmanagers, random);
        return sslcontext;
    }

    public SSLLayeringStrategy(
            final String algorithm,
            final KeyStore keystore,
            final String keystorePassword,
            final KeyStore truststore,
            final SecureRandom random,
            final X509HostnameVerifier hostnameVerifier)
                throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        this(createSSLContext(
                algorithm, keystore, keystorePassword, truststore, random, null),
                hostnameVerifier);
    }

    public SSLLayeringStrategy(
            final String algorithm,
            final KeyStore keystore,
            final String keystorePassword,
            final KeyStore truststore,
            final SecureRandom random,
            final TrustStrategy trustStrategy,
            final X509HostnameVerifier hostnameVerifier)
                throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        this(createSSLContext(
                algorithm, keystore, keystorePassword, truststore, random, trustStrategy),
                hostnameVerifier);
    }

    public SSLLayeringStrategy(
            final KeyStore keystore,
            final String keystorePassword,
            final KeyStore truststore)
                throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        this(TLS, keystore, keystorePassword, truststore, null, null, new BrowserCompatHostnameVerifier());
    }

    public SSLLayeringStrategy(
            final KeyStore keystore,
            final String keystorePassword)
                throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException{
        this(TLS, keystore, keystorePassword, null, null, null, new BrowserCompatHostnameVerifier());
    }

    public SSLLayeringStrategy(
            final KeyStore truststore)
                throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        this(TLS, null, null, truststore, null, null, new BrowserCompatHostnameVerifier());
    }

    public SSLLayeringStrategy(
            final TrustStrategy trustStrategy,
            final X509HostnameVerifier hostnameVerifier)
                throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        this(TLS, null, null, null, null, trustStrategy, hostnameVerifier);
    }

    public SSLLayeringStrategy(
            final TrustStrategy trustStrategy)
                throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        this(TLS, null, null, null, null, trustStrategy, new BrowserCompatHostnameVerifier());
    }

    public SSLLayeringStrategy(
            final SSLContext sslContext, final X509HostnameVerifier hostnameVerifier) {
        super();
        this.sslContext = sslContext;
        this.hostnameVerifier = hostnameVerifier;
    }

    public SSLLayeringStrategy(final SSLContext sslContext) {
        this(sslContext, new BrowserCompatHostnameVerifier());
    }

    public boolean isSecure() {
        return true;
    }

    @Deprecated
    public SSLIOSession layer(final IOSession iosession) {
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
                    verifySession(iosession, sslsession);
                }

        });
        iosession.setAttribute(SSLIOSession.SESSION_KEY, ssliosession);
        return ssliosession;
    }

    public SSLIOSession layer(final HttpHost host, final IOSession iosession) {
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

    @Deprecated
    protected void verifySession(
            final IOSession iosession,
            final SSLSession sslsession) throws SSLException {
        final InetSocketAddress address = (InetSocketAddress) iosession.getRemoteAddress();

        final Certificate[] certs = sslsession.getPeerCertificates();
        final X509Certificate x509 = (X509Certificate) certs[0];
        hostnameVerifier.verify(address.getHostName(), x509);
    }

    protected void verifySession(
            final HttpHost host,
            final IOSession iosession,
            final SSLSession sslsession) throws SSLException {
        final Certificate[] certs = sslsession.getPeerCertificates();
        final X509Certificate x509 = (X509Certificate) certs[0];
        hostnameVerifier.verify(host.getHostName(), x509);
    }

}
