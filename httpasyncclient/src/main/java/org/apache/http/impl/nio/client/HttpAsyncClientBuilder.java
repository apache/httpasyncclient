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

import java.net.ProxySelector;
import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.SSLContext;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.annotation.NotThreadSafe;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.client.AuthenticationStrategy;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.UserTokenHandler;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.RequestAddCookies;
import org.apache.http.client.protocol.RequestAuthCache;
import org.apache.http.client.protocol.RequestClientConnControl;
import org.apache.http.client.protocol.RequestDefaultHeaders;
import org.apache.http.client.protocol.RequestExpectContinue;
import org.apache.http.client.protocol.ResponseProcessCookies;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Lookup;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.SchemePortResolver;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.cookie.CookieSpecProvider;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.auth.BasicSchemeFactory;
import org.apache.http.impl.auth.DigestSchemeFactory;
import org.apache.http.impl.auth.KerberosSchemeFactory;
import org.apache.http.impl.auth.NTLMSchemeFactory;
import org.apache.http.impl.auth.SPNegoSchemeFactory;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.client.DefaultUserTokenHandler;
import org.apache.http.impl.client.NoopUserTokenHandler;
import org.apache.http.impl.client.ProxyAuthenticationStrategy;
import org.apache.http.impl.client.TargetAuthenticationStrategy;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.impl.conn.DefaultRoutePlanner;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.apache.http.impl.cookie.BestMatchSpecFactory;
import org.apache.http.impl.cookie.BrowserCompatSpecFactory;
import org.apache.http.impl.cookie.IgnoreSpecFactory;
import org.apache.http.impl.cookie.NetscapeDraftSpecFactory;
import org.apache.http.impl.cookie.RFC2109SpecFactory;
import org.apache.http.impl.cookie.RFC2965SpecFactory;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.conn.NoopIOSessionStrategy;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpProcessorBuilder;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;
import org.apache.http.util.TextUtils;
import org.apache.http.util.VersionInfo;

@NotThreadSafe
public class HttpAsyncClientBuilder {

    final static String DEFAULT_USER_AGENT;
    static {
        final VersionInfo vi = VersionInfo.loadVersionInfo("org.apache.http.nio.client",
                HttpAsyncClientBuilder.class.getClassLoader());
        final String release = vi != null ? vi.getRelease() : VersionInfo.UNAVAILABLE;
        DEFAULT_USER_AGENT = "Apache-HttpAsyncClient/" + release + " (java 1.5)";
    }

    private NHttpClientConnectionManager connManager;
    private SchemePortResolver schemePortResolver;
    private SchemeIOSessionStrategy sslStrategy;
    private X509HostnameVerifier hostnameVerifier;
    private SSLContext sslcontext;
    private ConnectionReuseStrategy reuseStrategy;
    private ConnectionKeepAliveStrategy keepAliveStrategy;
    private AuthenticationStrategy targetAuthStrategy;
    private AuthenticationStrategy proxyAuthStrategy;
    private UserTokenHandler userTokenHandler;
    private HttpProcessor httpprocessor;

    private LinkedList<HttpRequestInterceptor> requestFirst;
    private LinkedList<HttpRequestInterceptor> requestLast;
    private LinkedList<HttpResponseInterceptor> responseFirst;
    private LinkedList<HttpResponseInterceptor> responseLast;

    private HttpRoutePlanner routePlanner;
    private RedirectStrategy redirectStrategy;
    private Lookup<AuthSchemeProvider> authSchemeRegistry;
    private Lookup<CookieSpecProvider> cookieSpecRegistry;
    private CookieStore cookieStore;
    private CredentialsProvider credentialsProvider;
    private String userAgent;
    private HttpHost proxy;
    private Collection<? extends Header> defaultHeaders;
    private IOReactorConfig defaultIOReactorConfig;
    private ConnectionConfig defaultConnectionConfig;
    private RequestConfig defaultRequestConfig;

    private ThreadFactory threadFactory;

    private boolean systemProperties;
    private boolean cookieManagementDisabled;
    private boolean authCachingDisabled;
    private boolean connectionStateDisabled;

    private int maxConnTotal = 0;
    private int maxConnPerRoute = 0;

    public static HttpAsyncClientBuilder create() {
        return new HttpAsyncClientBuilder();
    }

    protected HttpAsyncClientBuilder() {
        super();
    }

    public final HttpAsyncClientBuilder setConnectionManager(
            final NHttpClientConnectionManager connManager) {
        this.connManager = connManager;
        return this;
    }

    public final HttpAsyncClientBuilder setSchemePortResolver(
            final SchemePortResolver schemePortResolver) {
        this.schemePortResolver = schemePortResolver;
        return this;
    }

    public final HttpAsyncClientBuilder setMaxConnTotal(final int maxConnTotal) {
        this.maxConnTotal = maxConnTotal;
        return this;
    }

    public final HttpAsyncClientBuilder setMaxConnPerRoute(final int maxConnPerRoute) {
        this.maxConnPerRoute = maxConnPerRoute;
        return this;
    }

    public final HttpAsyncClientBuilder setConnectionReuseStrategy(
            final ConnectionReuseStrategy reuseStrategy) {
        this.reuseStrategy = reuseStrategy;
        return this;
    }

    public final HttpAsyncClientBuilder setKeepAliveStrategy(
            final ConnectionKeepAliveStrategy keepAliveStrategy) {
        this.keepAliveStrategy = keepAliveStrategy;
        return this;
    }

    public final HttpAsyncClientBuilder setUserTokenHandler(final UserTokenHandler userTokenHandler) {
        this.userTokenHandler = userTokenHandler;
        return this;
    }

    public final HttpAsyncClientBuilder setTargetAuthenticationStrategy(
            final AuthenticationStrategy targetAuthStrategy) {
        this.targetAuthStrategy = targetAuthStrategy;
        return this;
    }

    public final HttpAsyncClientBuilder setProxyAuthenticationStrategy(
            final AuthenticationStrategy proxyAuthStrategy) {
        this.proxyAuthStrategy = proxyAuthStrategy;
        return this;
    }

    public final HttpAsyncClientBuilder setHttpProcessor(final HttpProcessor httpprocessor) {
        this.httpprocessor = httpprocessor;
        return this;
    }

    public final HttpAsyncClientBuilder addInterceptorFirst(final HttpResponseInterceptor itcp) {
        if (itcp == null) {
            return this;
        }
        if (responseFirst == null) {
            responseFirst = new LinkedList<HttpResponseInterceptor>();
        }
        responseFirst.addFirst(itcp);
        return this;
    }

    public final HttpAsyncClientBuilder addInterceptorLast(final HttpResponseInterceptor itcp) {
        if (itcp == null) {
            return this;
        }
        if (responseLast == null) {
            responseLast = new LinkedList<HttpResponseInterceptor>();
        }
        responseLast.addLast(itcp);
        return this;
    }

    public final HttpAsyncClientBuilder addInterceptorFirst(final HttpRequestInterceptor itcp) {
        if (itcp == null) {
            return this;
        }
        if (requestFirst == null) {
            requestFirst = new LinkedList<HttpRequestInterceptor>();
        }
        requestFirst.addFirst(itcp);
        return this;
    }

    public final HttpAsyncClientBuilder addInterceptorLast(final HttpRequestInterceptor itcp) {
        if (itcp == null) {
            return this;
        }
        if (requestLast == null) {
            requestLast = new LinkedList<HttpRequestInterceptor>();
        }
        requestLast.addLast(itcp);
        return this;
    }

    public final HttpAsyncClientBuilder setRoutePlanner(final HttpRoutePlanner routePlanner) {
        this.routePlanner = routePlanner;
        return this;
    }

    public final HttpAsyncClientBuilder setRedirectStrategy(final RedirectStrategy redirectStrategy) {
        this.redirectStrategy = redirectStrategy;
        return this;
    }

    public final HttpAsyncClientBuilder setDefaultCookieStore(final CookieStore cookieStore) {
        this.cookieStore = cookieStore;
        return this;
    }

    public final HttpAsyncClientBuilder setDefaultCredentialsProvider(
            final CredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
        return this;
    }

    public final HttpAsyncClientBuilder setDefaultAuthSchemeRegistry(
            final Lookup<AuthSchemeProvider> authSchemeRegistry) {
        this.authSchemeRegistry = authSchemeRegistry;
        return this;
    }

    public final HttpAsyncClientBuilder setDefaultCookieSpecRegistry(
            final Lookup<CookieSpecProvider> cookieSpecRegistry) {
        this.cookieSpecRegistry = cookieSpecRegistry;
        return this;
    }

    public final HttpAsyncClientBuilder setUserAgent(final String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    public final HttpAsyncClientBuilder setProxy(final HttpHost proxy) {
        this.proxy = proxy;
        return this;
    }

    public final HttpAsyncClientBuilder setSSLStrategy(final SchemeIOSessionStrategy strategy) {
        this.sslStrategy = strategy;
        return this;
    }

    public final HttpAsyncClientBuilder setSSLContext(final SSLContext sslcontext) {
        this.sslcontext = sslcontext;
        return this;
    }

    public final HttpAsyncClientBuilder setDefaultHeaders(final Collection<? extends Header> defaultHeaders) {
        this.defaultHeaders = defaultHeaders;
        return this;
    }

    public final HttpAsyncClientBuilder setDefaultIOReactorConfig(final IOReactorConfig config) {
        this.defaultIOReactorConfig = config;
        return this;
    }

    public final HttpAsyncClientBuilder setDefaultConnectionConfig(final ConnectionConfig config) {
        this.defaultConnectionConfig = config;
        return this;
    }

    public final HttpAsyncClientBuilder setDefaultRequestConfig(final RequestConfig config) {
        this.defaultRequestConfig = config;
        return this;
    }

    public final HttpAsyncClientBuilder setThreadFactory(final ThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
        return this;
    }

    public final HttpAsyncClientBuilder disableConnectionState() {
        connectionStateDisabled = true;
        return this;
    }

    public final HttpAsyncClientBuilder disableCookieManagement() {
        cookieManagementDisabled = true;
        return this;
    }

    public final HttpAsyncClientBuilder disableAuthCaching() {
        authCachingDisabled = true;
        return this;
    }

    public final HttpAsyncClientBuilder useSystemProperties() {
        systemProperties = true;
        return this;
    }

    private static String[] split(final String s) {
        if (TextUtils.isBlank(s)) {
            return null;
        }
        return s.split(" *, *");
    }

    public CloseableHttpAsyncClient build() {
        NHttpClientConnectionManager connManager = this.connManager;
        if (connManager == null) {
            SchemeIOSessionStrategy sslStrategy = this.sslStrategy;
            if (sslStrategy == null) {
                SSLContext sslcontext = this.sslcontext;
                if (sslcontext == null) {
                    if (systemProperties) {
                        sslcontext = SSLContexts.createDefault();
                    } else {
                        sslcontext = SSLContexts.createSystemDefault();
                    }
                }
                final String[] supportedProtocols = systemProperties ? split(
                        System.getProperty("https.protocols")) : null;
                final String[] supportedCipherSuites = systemProperties ? split(
                        System.getProperty("https.cipherSuites")) : null;
                sslStrategy = new SSLIOSessionStrategy(
                        sslcontext, supportedProtocols, supportedCipherSuites, hostnameVerifier);
            }
            final ConnectingIOReactor ioreactor = IOReactorUtils.create(
                defaultIOReactorConfig != null ? defaultIOReactorConfig : IOReactorConfig.DEFAULT);
            final PoolingNHttpClientConnectionManager poolingmgr = new PoolingNHttpClientConnectionManager(
                    ioreactor,
                    RegistryBuilder.<SchemeIOSessionStrategy>create()
                        .register("http", NoopIOSessionStrategy.INSTANCE)
                        .register("https", sslStrategy)
                        .build());
            if (defaultConnectionConfig != null) {
                poolingmgr.setDefaultConnectionConfig(defaultConnectionConfig);
            }
            if (systemProperties) {
                String s = System.getProperty("http.keepAlive", "true");
                if ("true".equalsIgnoreCase(s)) {
                    s = System.getProperty("http.maxConnections", "5");
                    final int max = Integer.parseInt(s);
                    poolingmgr.setDefaultMaxPerRoute(max);
                    poolingmgr.setMaxTotal(2 * max);
                }
            } else {
                if (maxConnTotal > 0) {
                    poolingmgr.setMaxTotal(maxConnTotal);
                }
                if (maxConnPerRoute > 0) {
                    poolingmgr.setDefaultMaxPerRoute(maxConnPerRoute);
                }
            }
            connManager = poolingmgr;
        }
        ConnectionReuseStrategy reuseStrategy = this.reuseStrategy;
        if (reuseStrategy == null) {
            if (systemProperties) {
                final String s = System.getProperty("http.keepAlive", "true");
                if ("true".equalsIgnoreCase(s)) {
                    reuseStrategy = DefaultConnectionReuseStrategy.INSTANCE;
                } else {
                    reuseStrategy = NoConnectionReuseStrategy.INSTANCE;
                }
            } else {
                reuseStrategy = DefaultConnectionReuseStrategy.INSTANCE;
            }
        }
        ConnectionKeepAliveStrategy keepAliveStrategy = this.keepAliveStrategy;
        if (keepAliveStrategy == null) {
            keepAliveStrategy = DefaultConnectionKeepAliveStrategy.INSTANCE;
        }
        AuthenticationStrategy targetAuthStrategy = this.targetAuthStrategy;
        if (targetAuthStrategy == null) {
            targetAuthStrategy = TargetAuthenticationStrategy.INSTANCE;
        }
        AuthenticationStrategy proxyAuthStrategy = this.proxyAuthStrategy;
        if (proxyAuthStrategy == null) {
            proxyAuthStrategy = ProxyAuthenticationStrategy.INSTANCE;
        }
        UserTokenHandler userTokenHandler = this.userTokenHandler;
        if (userTokenHandler == null) {
            if (!connectionStateDisabled) {
                userTokenHandler = DefaultUserTokenHandler.INSTANCE;
            } else {
                userTokenHandler = NoopUserTokenHandler.INSTANCE;
            }
        }
        SchemePortResolver schemePortResolver = this.schemePortResolver;
        if (schemePortResolver == null) {
            schemePortResolver = DefaultSchemePortResolver.INSTANCE;
        }

        HttpProcessor httpprocessor = this.httpprocessor;
        if (httpprocessor == null) {

            String userAgent = this.userAgent;
            if (userAgent == null) {
                if (systemProperties) {
                    userAgent = System.getProperty("http.agent");
                }
                if (userAgent == null) {
                    userAgent = DEFAULT_USER_AGENT;
                }
            }

            final HttpProcessorBuilder b = HttpProcessorBuilder.create();
            if (requestFirst != null) {
                for (final HttpRequestInterceptor i: requestFirst) {
                    b.addFirst(i);
                }
            }
            if (responseFirst != null) {
                for (final HttpResponseInterceptor i: responseFirst) {
                    b.addFirst(i);
                }
            }
            b.addAll(
                    new RequestDefaultHeaders(defaultHeaders),
                    new RequestContent(),
                    new RequestTargetHost(),
                    new RequestClientConnControl(),
                    new RequestUserAgent(userAgent),
                    new RequestExpectContinue());
            if (!cookieManagementDisabled) {
                b.add(new RequestAddCookies());
            }
            if (!authCachingDisabled) {
                b.add(new RequestAuthCache());
            }
            if (!cookieManagementDisabled) {
                b.add(new ResponseProcessCookies());
            }
            if (requestLast != null) {
                for (final HttpRequestInterceptor i: requestLast) {
                    b.addLast(i);
                }
            }
            if (responseLast != null) {
                for (final HttpResponseInterceptor i: responseLast) {
                    b.addLast(i);
                }
            }
            httpprocessor = b.build();
        }
        // Add redirect executor, if not disabled
        HttpRoutePlanner routePlanner = this.routePlanner;
        if (routePlanner == null) {
            if (proxy != null) {
                routePlanner = new DefaultProxyRoutePlanner(proxy, schemePortResolver);
            } else if (systemProperties) {
                routePlanner = new SystemDefaultRoutePlanner(
                        schemePortResolver, ProxySelector.getDefault());
            } else {
                routePlanner = new DefaultRoutePlanner(schemePortResolver);
            }
        }
        Lookup<AuthSchemeProvider> authSchemeRegistry = this.authSchemeRegistry;
        if (authSchemeRegistry == null) {
            authSchemeRegistry = RegistryBuilder.<AuthSchemeProvider>create()
                .register(AuthSchemes.BASIC, new BasicSchemeFactory())
                .register(AuthSchemes.DIGEST, new DigestSchemeFactory())
                .register(AuthSchemes.NTLM, new NTLMSchemeFactory())
                .register(AuthSchemes.SPNEGO, new SPNegoSchemeFactory())
                .register(AuthSchemes.KERBEROS, new KerberosSchemeFactory())
                .build();
        }
        Lookup<CookieSpecProvider> cookieSpecRegistry = this.cookieSpecRegistry;
        if (cookieSpecRegistry == null) {
            cookieSpecRegistry = RegistryBuilder.<CookieSpecProvider>create()
                .register(CookieSpecs.BEST_MATCH, new BestMatchSpecFactory())
                .register(CookieSpecs.STANDARD, new RFC2965SpecFactory())
                .register(CookieSpecs.BROWSER_COMPATIBILITY, new BrowserCompatSpecFactory())
                .register(CookieSpecs.NETSCAPE, new NetscapeDraftSpecFactory())
                .register(CookieSpecs.IGNORE_COOKIES, new IgnoreSpecFactory())
                .register("rfc2109", new RFC2109SpecFactory())
                .register("rfc2965", new RFC2965SpecFactory())
                .build();
        }

        CookieStore defaultCookieStore = this.cookieStore;
        if (defaultCookieStore == null) {
            defaultCookieStore = new BasicCookieStore();
        }

        CredentialsProvider defaultCredentialsProvider = this.credentialsProvider;
        if (defaultCredentialsProvider == null) {
            defaultCredentialsProvider = new BasicCredentialsProvider();
        }

        RedirectStrategy redirectStrategy = this.redirectStrategy;
        if (redirectStrategy == null) {
            redirectStrategy = DefaultRedirectStrategy.INSTANCE;
        }

        RequestConfig defaultRequestConfig = this.defaultRequestConfig;
        if (defaultRequestConfig == null) {
            defaultRequestConfig = RequestConfig.DEFAULT;
        }

        ThreadFactory threadFactory = this.threadFactory;
        if (threadFactory == null) {
            threadFactory = Executors.defaultThreadFactory();
        }

        final MainClientExec exec = new MainClientExec(
            connManager,
            httpprocessor,
            routePlanner,
            reuseStrategy,
            keepAliveStrategy,
            redirectStrategy,
            targetAuthStrategy,
            proxyAuthStrategy,
            userTokenHandler);

        return new InternalHttpAsyncClient(
            connManager,
            exec,
            cookieSpecRegistry,
            authSchemeRegistry,
            defaultCookieStore,
            defaultCredentialsProvider,
            defaultRequestConfig,
            threadFactory);
    }

}
