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
package org.apache.http.impl.nio.conn;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.apache.commons.logging.Log;
import org.apache.http.HttpHost;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.SchemePortResolver;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager.ConfigData;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager.InternalConnectionFactory;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.conn.ManagedNHttpClientConnection;
import org.apache.http.nio.conn.NHttpConnectionFactory;
import org.apache.http.nio.conn.ssl.SchemeLayeringStrategy;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.SessionRequest;
import org.apache.http.nio.reactor.SessionRequestCallback;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class TestPoolingHttpClientAsyncConnectionManager {

    @Mock
    private ConnectingIOReactor ioreactor;
    @Mock
    private CPool pool;
    @Mock
    private SchemeLayeringStrategy layeringStrategy;
    @Mock
    private SchemePortResolver schemePortResolver;
    @Mock
    private DnsResolver dnsResolver;
    @Mock
    private FutureCallback<NHttpClientConnection> connCallback;
    @Captor
    private ArgumentCaptor<FutureCallback<CPoolEntry>> poolEntryCallbackCaptor;
    @Mock
    private ManagedNHttpClientConnection conn;
    @Mock
    private NHttpConnectionFactory<ManagedNHttpClientConnection> connFactory;
    @Captor
    private ArgumentCaptor<SessionRequestCallback> sessionRequestCallbackCaptor;
    @Mock
    private SessionRequest sessionRequest;
    @Mock
    private IOSession iosession;

    private Registry<SchemeLayeringStrategy> layeringStrategyRegistry;
    private PoolingNHttpClientConnectionManager connman;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        layeringStrategyRegistry = RegistryBuilder.<SchemeLayeringStrategy>create()
            .register("https", layeringStrategy).build();
        connman = new PoolingNHttpClientConnectionManager(
            ioreactor, pool, layeringStrategyRegistry,
            schemePortResolver, dnsResolver, 10, TimeUnit.SECONDS);
    }

    @Test
    public void testShutdown() throws Exception {
        connman.shutdown();

        Mockito.verify(pool).shutdown(2000);
    }

    @Test
    public void testShutdownMs() throws Exception {
        connman.shutdown(500);

        Mockito.verify(pool).shutdown(500);
    }

    @Test
    public void testRequestReleaseConnection() throws Exception {
        final HttpHost target = new HttpHost("localhost");
        final HttpRoute route = new HttpRoute(target);
        final Future<NHttpClientConnection> future = connman.requestConnection(
            route, "some state", 1000L, TimeUnit.MILLISECONDS, connCallback);
        Assert.assertNotNull(future);

        Mockito.verify(pool).lease(
                Mockito.same(route),
                Mockito.eq("some state"),
                Mockito.eq(1000L),
                Mockito.eq(TimeUnit.MILLISECONDS),
                poolEntryCallbackCaptor.capture());
        final FutureCallback<CPoolEntry> callaback = poolEntryCallbackCaptor.getValue();
        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        callaback.completed(poolentry);

        Assert.assertTrue(future.isDone());
        final NHttpClientConnection managedConn = future.get();
        Assert.assertTrue(Proxy.isProxyClass(managedConn.getClass()));
        Mockito.verify(connCallback).completed(Mockito.<NHttpClientConnection>any());

        Mockito.when(conn.isOpen()).thenReturn(Boolean.TRUE);
        connman.releaseConnection(managedConn, "new state", 5, TimeUnit.SECONDS);

        Mockito.verify(pool).release(poolentry, true);
        Assert.assertEquals("new state", poolentry.getState());
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(poolentry.getUpdated());
        cal.add(Calendar.SECOND, 5);
        Assert.assertEquals(cal.getTimeInMillis(), poolentry.getExpiry());
    }

    @Test
    public void testReleaseConnectionIncompleteRoute() throws Exception {
        final HttpHost target = new HttpHost("localhost");
        final HttpRoute route = new HttpRoute(target);
        final Future<NHttpClientConnection> future = connman.requestConnection(
            route, "some state", 1000L, TimeUnit.MILLISECONDS, connCallback);
        Assert.assertNotNull(future);

        Mockito.verify(pool).lease(
                Mockito.same(route),
                Mockito.eq("some state"),
                Mockito.eq(1000L),
                Mockito.eq(TimeUnit.MILLISECONDS),
                poolEntryCallbackCaptor.capture());
        final FutureCallback<CPoolEntry> callaback = poolEntryCallbackCaptor.getValue();
        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        callaback.completed(poolentry);

        Assert.assertTrue(future.isDone());
        final NHttpClientConnection managedConn = future.get();
        Assert.assertTrue(Proxy.isProxyClass(managedConn.getClass()));
        Mockito.verify(connCallback).completed(Mockito.<NHttpClientConnection>any());

        Mockito.when(conn.isOpen()).thenReturn(Boolean.TRUE);
        connman.releaseConnection(managedConn, "new state", 5, TimeUnit.SECONDS);

        Mockito.verify(pool).release(poolentry, false);
    }

    @Test
    public void testRequestConnectionFutureCancelled() throws Exception {
        final HttpHost target = new HttpHost("localhost");
        final HttpRoute route = new HttpRoute(target);
        final Future<NHttpClientConnection> future = connman.requestConnection(
            route, "some state", 1000L, TimeUnit.MILLISECONDS, null);
        Assert.assertNotNull(future);
        future.cancel(true);

        Mockito.verify(pool).lease(
                Mockito.same(route),
                Mockito.eq("some state"),
                Mockito.eq(1000L),
                Mockito.eq(TimeUnit.MILLISECONDS),
                poolEntryCallbackCaptor.capture());
        final FutureCallback<CPoolEntry> callaback = poolEntryCallbackCaptor.getValue();
        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        callaback.completed(poolentry);

        Mockito.verify(pool).release(poolentry, true);
    }

    @Test(expected=ExecutionException.class)
    public void testRequestConnectionFailed() throws Exception {
        final HttpHost target = new HttpHost("localhost");
        final HttpRoute route = new HttpRoute(target);
        final Future<NHttpClientConnection> future = connman.requestConnection(
            route, "some state", 1000L, TimeUnit.MILLISECONDS, null);
        Assert.assertNotNull(future);

        Mockito.verify(pool).lease(
                Mockito.same(route),
                Mockito.eq("some state"),
                Mockito.eq(1000L),
                Mockito.eq(TimeUnit.MILLISECONDS),
                poolEntryCallbackCaptor.capture());
        final FutureCallback<CPoolEntry> callaback = poolEntryCallbackCaptor.getValue();
        callaback.failed(new Exception());

        Assert.assertTrue(future.isDone());
        future.get();
    }

    @Test
    public void testRequestConnectionCancelled() throws Exception {
        final HttpHost target = new HttpHost("localhost");
        final HttpRoute route = new HttpRoute(target);
        final Future<NHttpClientConnection> future = connman.requestConnection(
            route, "some state", 1000L, TimeUnit.MILLISECONDS, null);
        Assert.assertNotNull(future);

        Mockito.verify(pool).lease(
                Mockito.same(route),
                Mockito.eq("some state"),
                Mockito.eq(1000L),
                Mockito.eq(TimeUnit.MILLISECONDS),
                poolEntryCallbackCaptor.capture());
        final FutureCallback<CPoolEntry> callaback = poolEntryCallbackCaptor.getValue();
        callaback.cancelled();

        Assert.assertTrue(future.isDone());
        Assert.assertTrue(future.isCancelled());
        Assert.assertNull(future.get());
    }

    @Test
    public void testConnect() throws Exception {
        final InetAddress la1 = InetAddress.getByAddress(new byte[] {127,0,0,1});
        final InetAddress ra1 = InetAddress.getByAddress(new byte[] {10,0,0,1});
        final InetAddress ra2 = InetAddress.getByAddress(new byte[] {10,0,0,2});
        final HttpHost target = new HttpHost("somehost", -1, "https");
        final HttpRoute route = new HttpRoute(target, la1, true);

        Mockito.when(dnsResolver.resolve("somehost")).thenReturn(new InetAddress[] {ra1, ra2});
        Mockito.when(schemePortResolver.resolve(target)).thenReturn(8443);

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);
        final Future<NHttpClientConnection> future = connman.connect(
            managedConn, route, 1000, connCallback);
        Assert.assertNotNull(future);

        Mockito.verify(ioreactor).connect(
            Mockito.eq(new InetSocketAddress(ra1, 8443)),
            Mockito.eq(new InetSocketAddress(la1, 0)),
            Mockito.isNull(),
            sessionRequestCallbackCaptor.capture());

        Mockito.when(sessionRequest.getSession()).thenReturn(iosession);
        Mockito.when(layeringStrategy.layer(target, iosession)).thenReturn(iosession);

        final SessionRequestCallback callback = sessionRequestCallbackCaptor.getValue();
        callback.completed(sessionRequest);
        Mockito.verify(connCallback).completed(Mockito.<NHttpClientConnection>any());
        Mockito.verify(iosession).setAttribute(IOEventDispatch.CONNECTION_KEY, conn);
        Mockito.verify(conn).bind(iosession);
    }

    @Test
    public void testConnectAlreadyConnected() throws Exception {
        final HttpHost target = new HttpHost("somehost", -1, "https");
        final HttpRoute route = new HttpRoute(target);

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.isOpen()).thenReturn(Boolean.TRUE);

        final Future<NHttpClientConnection> future = connman.connect(
            managedConn, route, 1000, connCallback);
        Assert.assertNotNull(future);
        Assert.assertTrue(future.isDone());

        Mockito.verify(ioreactor, Mockito.never()).connect(
            Mockito.<InetSocketAddress>any(),
            Mockito.<InetSocketAddress>any(),
            Mockito.any(),
            Mockito.<SessionRequestCallback>any());
    }

    @Test
    public void testConnectUnknownHost() throws Exception {
        final HttpHost target = new HttpHost("pampa", -1, "https");
        final HttpRoute route = new HttpRoute(target);

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(dnsResolver.resolve("pampa")).thenThrow(new UnknownHostException());

        final Future<NHttpClientConnection> future = connman.connect(
            managedConn, route, 1000, connCallback);
        Assert.assertNotNull(future);
        Assert.assertTrue(future.isDone());

        Mockito.verify(ioreactor, Mockito.never()).connect(
            Mockito.<InetSocketAddress>any(),
            Mockito.<InetSocketAddress>any(),
            Mockito.any(),
            Mockito.<SessionRequestCallback>any());

        try {
            future.get();
        } catch (final ExecutionException ex) {
            Assert.assertTrue(ex.getCause() instanceof UnknownHostException);
        }
    }

    @Test
    public void testConnectFailure() throws Exception {
        final InetAddress ra1 = InetAddress.getByAddress(new byte[] {10,0,0,1});
        final HttpHost target = new HttpHost("somehost", -1, "https");
        final HttpRoute route = new HttpRoute(target);

        Mockito.when(dnsResolver.resolve("somehost")).thenReturn(new InetAddress[] {ra1});
        Mockito.when(schemePortResolver.resolve(target)).thenReturn(8443);

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);
        final Future<NHttpClientConnection> future = connman.connect(
            managedConn, route, 1000, connCallback);
        Assert.assertNotNull(future);

        Mockito.verify(ioreactor).connect(
            Mockito.eq(new InetSocketAddress(ra1, 8443)),
            (SocketAddress) Mockito.isNull(),
            Mockito.isNull(),
            sessionRequestCallbackCaptor.capture());

        Mockito.when(sessionRequest.getException()).thenReturn(new IOException());

        final SessionRequestCallback callback = sessionRequestCallbackCaptor.getValue();
        callback.failed(sessionRequest);
        Mockito.verify(connCallback).failed(Mockito.<IOException>any());
    }

    @Test
    public void testConnectTimeout() throws Exception {
        final InetAddress ra1 = InetAddress.getByAddress(new byte[] {10,0,0,1});
        final HttpHost target = new HttpHost("somehost", -1, "https");
        final HttpRoute route = new HttpRoute(target);

        Mockito.when(dnsResolver.resolve("somehost")).thenReturn(new InetAddress[] {ra1});
        Mockito.when(schemePortResolver.resolve(target)).thenReturn(8443);

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);
        final Future<NHttpClientConnection> future = connman.connect(
            managedConn, route, 1000, connCallback);
        Assert.assertNotNull(future);

        Mockito.verify(ioreactor).connect(
            Mockito.eq(new InetSocketAddress(ra1, 8443)),
            (SocketAddress) Mockito.isNull(),
            Mockito.isNull(),
            sessionRequestCallbackCaptor.capture());

        final SessionRequestCallback callback = sessionRequestCallbackCaptor.getValue();
        callback.timeout(sessionRequest);
        Mockito.verify(connCallback).failed(Mockito.<ConnectTimeoutException>any());
    }

    @Test
    public void testConnectCancelled() throws Exception {
        final InetAddress ra1 = InetAddress.getByAddress(new byte[] {10,0,0,1});
        final HttpHost target = new HttpHost("somehost", -1, "https");
        final HttpRoute route = new HttpRoute(target);

        Mockito.when(dnsResolver.resolve("somehost")).thenReturn(new InetAddress[] {ra1});
        Mockito.when(schemePortResolver.resolve(target)).thenReturn(8443);

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);
        final Future<NHttpClientConnection> future = connman.connect(
            managedConn, route, 1000, connCallback);
        Assert.assertNotNull(future);

        Mockito.verify(ioreactor).connect(
            Mockito.eq(new InetSocketAddress(ra1, 8443)),
            (SocketAddress) Mockito.isNull(),
            Mockito.isNull(),
            sessionRequestCallbackCaptor.capture());

        final SessionRequestCallback callback = sessionRequestCallbackCaptor.getValue();
        callback.cancelled(sessionRequest);
        Mockito.verify(connCallback).cancelled();
    }

    @Test
    public void testConnectionInitialize() throws Exception {
        final HttpHost target = new HttpHost("somehost", -1, "http");
        final HttpRoute route = new HttpRoute(target);
        final HttpContext context = new BasicHttpContext();

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.getIOSession()).thenReturn(iosession);
        Mockito.when(layeringStrategy.layer(target, iosession)).thenReturn(iosession);

        connman.initialize(managedConn, route, context);

        Mockito.verify(layeringStrategy, Mockito.never()).layer(target, iosession);
        Mockito.verify(conn, Mockito.never()).bind(iosession);

        Assert.assertFalse(connman.isRouteComplete(managedConn));
    }

    @Test
    public void testConnectionInitializeHttps() throws Exception {
        final HttpHost target = new HttpHost("somehost", -1, "https");
        final HttpRoute route = new HttpRoute(target, null, true);
        final HttpContext context = new BasicHttpContext();

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.getIOSession()).thenReturn(iosession);
        Mockito.when(layeringStrategy.layer(target, iosession)).thenReturn(iosession);

        connman.initialize(managedConn, route, context);

        Mockito.verify(layeringStrategy).layer(target, iosession);
        Mockito.verify(conn).bind(iosession);
    }

    @Test
    public void testConnectionUpgrade() throws Exception {
        final HttpHost target = new HttpHost("somehost", -1, "https");
        final HttpRoute route = new HttpRoute(target);
        final HttpContext context = new BasicHttpContext();

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.getIOSession()).thenReturn(iosession);
        Mockito.when(layeringStrategy.layer(target, iosession)).thenReturn(iosession);

        connman.upgrade(managedConn, route, context);

        Mockito.verify(layeringStrategy).layer(target, iosession);
        Mockito.verify(conn).bind(iosession);
    }

    @Test(expected=IllegalStateException.class)
    public void testConnectionUpgradeIllegalScheme() throws Exception {
        final HttpHost target = new HttpHost("somehost", -1, "http");
        final HttpRoute route = new HttpRoute(target);
        final HttpContext context = new BasicHttpContext();

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.getIOSession()).thenReturn(iosession);
        Mockito.when(layeringStrategy.layer(target, iosession)).thenReturn(iosession);

        connman.upgrade(managedConn, route, context);
    }

    @Test
    public void testConnectionRouteComplete() throws Exception {
        final HttpHost target = new HttpHost("somehost", -1, "http");
        final HttpRoute route = new HttpRoute(target);
        final HttpContext context = new BasicHttpContext();

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.getIOSession()).thenReturn(iosession);
        Mockito.when(layeringStrategy.layer(target, iosession)).thenReturn(iosession);

        connman.initialize(managedConn, route, context);
        connman.routeComplete(managedConn, route, context);

        Assert.assertTrue(connman.isRouteComplete(managedConn));
    }

    @Test
    public void testDelegationToCPool() throws Exception {
        connman.closeExpiredConnections();
        Mockito.verify(pool).closeExpired();

        connman.closeIdleConnections(3, TimeUnit.SECONDS);
        Mockito.verify(pool).closeIdle(3, TimeUnit.SECONDS);

        connman.getMaxTotal();
        Mockito.verify(pool).getMaxTotal();

        connman.getDefaultMaxPerRoute();
        Mockito.verify(pool).getDefaultMaxPerRoute();

        final HttpRoute route = new HttpRoute(new HttpHost("somehost"));
        connman.getMaxPerRoute(route);
        Mockito.verify(pool).getMaxPerRoute(route);

        connman.setMaxTotal(200);
        Mockito.verify(pool).setMaxTotal(200);

        connman.setDefaultMaxPerRoute(100);
        Mockito.verify(pool).setDefaultMaxPerRoute(100);

        connman.setMaxPerRoute(route, 150);
        Mockito.verify(pool).setMaxPerRoute(route, 150);

        connman.getTotalStats();
        Mockito.verify(pool).getTotalStats();

        connman.getStats(route);
        Mockito.verify(pool).getStats(route);
    }

    @Test
    public void testInternalConnFactoryCreate() throws Exception {
        final ConfigData configData = new ConfigData();
        final InternalConnectionFactory internalConnFactory = new InternalConnectionFactory(
            configData, connFactory);

        final HttpRoute route = new HttpRoute(new HttpHost("somehost"));
        internalConnFactory.create(route, iosession);

        Mockito.verify(layeringStrategy, Mockito.never()).layer(
            Mockito.eq(new HttpHost("somehost")), Mockito.<IOSession>any());
        Mockito.verify(connFactory).create(Mockito.same(iosession), Mockito.<ConnectionConfig>any());
    }

    @Test
    public void testInternalConnFactoryCreateViaProxy() throws Exception {
        final ConfigData configData = new ConfigData();
        final InternalConnectionFactory internalConnFactory = new InternalConnectionFactory(
            configData, connFactory);

        final HttpHost target = new HttpHost("somehost");
        final HttpHost proxy = new HttpHost("someproxy", 8888);
        final HttpRoute route = new HttpRoute(target, null, proxy, false);

        final ConnectionConfig config = ConnectionConfig.custom().build();
        configData.setConnectionConfig(proxy, config);

        internalConnFactory.create(route, iosession);

        Mockito.verify(connFactory).create(iosession, config);
    }

    @Test
    public void testInternalConnFactoryCreateDirect() throws Exception {
        final ConfigData configData = new ConfigData();
        final InternalConnectionFactory internalConnFactory = new InternalConnectionFactory(
            configData, connFactory);

        final HttpHost target = new HttpHost("somehost");
        final HttpRoute route = new HttpRoute(target);

        final ConnectionConfig config = ConnectionConfig.custom().build();
        configData.setConnectionConfig(target, config);

        internalConnFactory.create(route, iosession);

        Mockito.verify(connFactory).create(iosession, config);
    }

    @Test
    public void testInternalConnFactoryCreateDefaultConfig() throws Exception {
        final ConfigData configData = new ConfigData();
        final InternalConnectionFactory internalConnFactory = new InternalConnectionFactory(
            configData, connFactory);

        final HttpHost target = new HttpHost("somehost");
        final HttpRoute route = new HttpRoute(target);

        final ConnectionConfig config = ConnectionConfig.custom().build();
        configData.setDefaultConnectionConfig(config);

        internalConnFactory.create(route, iosession);

        Mockito.verify(connFactory).create(iosession, config);
    }

    @Test
    public void testInternalConnFactoryCreateGlobalDefaultConfig() throws Exception {
        final ConfigData configData = new ConfigData();
        final InternalConnectionFactory internalConnFactory = new InternalConnectionFactory(
            configData, connFactory);

        final HttpHost target = new HttpHost("somehost");
        final HttpRoute route = new HttpRoute(target);

        configData.setDefaultConnectionConfig(null);

        internalConnFactory.create(route, iosession);

        Mockito.verify(connFactory).create(iosession, ConnectionConfig.DEFAULT);
    }

}
