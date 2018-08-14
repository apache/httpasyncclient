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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Calendar;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.http.HttpHost;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.SchemePortResolver;
import org.apache.http.conn.UnsupportedSchemeException;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager.ConfigData;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager.InternalAddressResolver;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager.InternalConnectionFactory;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.conn.ManagedNHttpClientConnection;
import org.apache.http.nio.conn.NHttpConnectionFactory;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.SessionRequest;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class TestPoolingHttpClientAsyncConnectionManager {

    @Mock
    private ConnectingIOReactor ioReactor;
    @Mock
    private CPool pool;
    @Mock
    private SchemeIOSessionStrategy noopStrategy;
    @Mock
    private SchemeIOSessionStrategy sslStrategy;
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
    @Mock
    private SessionRequest sessionRequest;
    @Mock
    private IOSession ioSession;

    private Registry<SchemeIOSessionStrategy> layeringStrategyRegistry;
    private PoolingNHttpClientConnectionManager connman;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        Mockito.when(sslStrategy.isLayeringRequired()).thenReturn(Boolean.TRUE);

        layeringStrategyRegistry = RegistryBuilder.<SchemeIOSessionStrategy>create()
            .register("http", noopStrategy)
            .register("https", sslStrategy)
            .build();
        connman = new PoolingNHttpClientConnectionManager(
            ioReactor, pool, layeringStrategyRegistry);
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
            route, "some state", 1000L, 2000L, TimeUnit.MILLISECONDS, connCallback);
        Assert.assertNotNull(future);

        Mockito.verify(pool).lease(
                Matchers.same(route),
                Matchers.eq("some state"),
                Matchers.eq(1000L),
                Matchers.eq(2000L),
                Matchers.eq(TimeUnit.MILLISECONDS),
                poolEntryCallbackCaptor.capture());
        final FutureCallback<CPoolEntry> callaback = poolEntryCallbackCaptor.getValue();
        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        callaback.completed(poolentry);

        Assert.assertTrue(future.isDone());
        final NHttpClientConnection managedConn = future.get();
        Mockito.verify(connCallback).completed(Matchers.<NHttpClientConnection>any());

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
            route, "some state", 1000L, 2000L, TimeUnit.MILLISECONDS, connCallback);
        Assert.assertNotNull(future);

        Mockito.verify(pool).lease(
                Matchers.same(route),
                Matchers.eq("some state"),
                Matchers.eq(1000L),
                Matchers.eq(2000L),
                Matchers.eq(TimeUnit.MILLISECONDS),
                poolEntryCallbackCaptor.capture());
        final FutureCallback<CPoolEntry> callaback = poolEntryCallbackCaptor.getValue();
        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        callaback.completed(poolentry);

        Assert.assertTrue(future.isDone());
        final NHttpClientConnection managedConn = future.get();
        Mockito.verify(connCallback).completed(Matchers.<NHttpClientConnection>any());

        Mockito.when(conn.isOpen()).thenReturn(Boolean.TRUE);
        connman.releaseConnection(managedConn, "new state", 5, TimeUnit.SECONDS);

        Mockito.verify(pool).release(poolentry, false);
    }

    @Test
    public void testRequestConnectionFutureCancelled() throws Exception {
        final HttpHost target = new HttpHost("localhost");
        final HttpRoute route = new HttpRoute(target);
        final Future<NHttpClientConnection> future = connman.requestConnection(
            route, "some state", 1000L, 2000L, TimeUnit.MILLISECONDS, null);
        Assert.assertNotNull(future);
        future.cancel(true);

        Mockito.verify(pool).lease(
                Matchers.same(route),
                Matchers.eq("some state"),
                Matchers.eq(1000L),
                Matchers.eq(2000L),
                Matchers.eq(TimeUnit.MILLISECONDS),
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
            route, "some state", 1000L, 2000L, TimeUnit.MILLISECONDS, null);
        Assert.assertNotNull(future);

        Mockito.verify(pool).lease(
                Matchers.same(route),
                Matchers.eq("some state"),
                Matchers.eq(1000L),
                Matchers.eq(2000L),
                Matchers.eq(TimeUnit.MILLISECONDS),
                poolEntryCallbackCaptor.capture());
        final FutureCallback<CPoolEntry> callaback = poolEntryCallbackCaptor.getValue();
        callaback.failed(new Exception());

        Assert.assertTrue(future.isDone());
        future.get();
    }

    @Test(expected = CancellationException.class)
    public void testRequestConnectionCancelled() throws Exception {
        final HttpHost target = new HttpHost("localhost");
        final HttpRoute route = new HttpRoute(target);
        final Future<NHttpClientConnection> future = connman.requestConnection(
            route, "some state", 1000L, 2000L, TimeUnit.MILLISECONDS, null);
        Assert.assertNotNull(future);

        Mockito.verify(pool).lease(
                Matchers.same(route),
                Matchers.eq("some state"),
                Matchers.eq(1000L),
                Matchers.eq(2000L),
                Matchers.eq(TimeUnit.MILLISECONDS),
                poolEntryCallbackCaptor.capture());
        final FutureCallback<CPoolEntry> callaback = poolEntryCallbackCaptor.getValue();
        callaback.cancelled();

        Assert.assertTrue(future.isDone());
        Assert.assertTrue(future.isCancelled());
        future.get();
    }

    @Test
    public void testConnectionInitialize() throws Exception {
        final HttpHost target = new HttpHost("somehost", -1, "http");
        final HttpRoute route = new HttpRoute(target);
        final HttpContext context = new BasicHttpContext();

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.getIOSession()).thenReturn(ioSession);
        Mockito.when(sslStrategy.upgrade(target, ioSession)).thenReturn(ioSession);

        connman.startRoute(managedConn, route, context);

        Mockito.verify(noopStrategy, Mockito.never()).upgrade(target, ioSession);
        Mockito.verify(conn, Mockito.never()).bind(ioSession);

        Assert.assertFalse(connman.isRouteComplete(managedConn));
    }

    @Test
    public void testConnectionInitializeHttps() throws Exception {
        final HttpHost target = new HttpHost("somehost", 443, "https");
        final HttpRoute route = new HttpRoute(target, null, true);
        final HttpContext context = new BasicHttpContext();

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.getIOSession()).thenReturn(ioSession);
        Mockito.when(sslStrategy.upgrade(target, ioSession)).thenReturn(ioSession);

        connman.startRoute(managedConn, route, context);

        Mockito.verify(sslStrategy).upgrade(target, ioSession);
        Mockito.verify(conn).bind(ioSession);
    }

    @Test
    public void testConnectionInitializeContextSpecific() throws Exception {
        final HttpHost target = new HttpHost("somehost", 80, "http11");
        final HttpRoute route = new HttpRoute(target);
        final HttpContext context = new BasicHttpContext();

        final Registry<SchemeIOSessionStrategy> reg = RegistryBuilder.<SchemeIOSessionStrategy>create()
                .register("http11", noopStrategy)
                .build();
        context.setAttribute(PoolingNHttpClientConnectionManager.IOSESSION_FACTORY_REGISTRY, reg);

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.getIOSession()).thenReturn(ioSession);
        Mockito.when(sslStrategy.upgrade(target, ioSession)).thenReturn(ioSession);

        connman.startRoute(managedConn, route, context);

        Mockito.verify(noopStrategy, Mockito.never()).upgrade(target, ioSession);
        Mockito.verify(conn, Mockito.never()).bind(ioSession);

        Assert.assertFalse(connman.isRouteComplete(managedConn));
    }

    @Test(expected=UnsupportedSchemeException.class)
    public void testConnectionInitializeUnknownScheme() throws Exception {
        final HttpHost target = new HttpHost("somehost", -1, "whatever");
        final HttpRoute route = new HttpRoute(target, null, true);
        final HttpContext context = new BasicHttpContext();

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.getIOSession()).thenReturn(ioSession);
        Mockito.when(sslStrategy.upgrade(target, ioSession)).thenReturn(ioSession);

        connman.startRoute(managedConn, route, context);
    }

    @Test
    public void testConnectionUpgrade() throws Exception {
        final HttpHost target = new HttpHost("somehost", 443, "https");
        final HttpRoute route = new HttpRoute(target);
        final HttpContext context = new BasicHttpContext();

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.getIOSession()).thenReturn(ioSession);
        Mockito.when(sslStrategy.upgrade(target, ioSession)).thenReturn(ioSession);

        connman.upgrade(managedConn, route, context);

        Mockito.verify(sslStrategy).upgrade(target, ioSession);
        Mockito.verify(conn).bind(ioSession);
    }

    @Test(expected=UnsupportedSchemeException.class)
    public void testConnectionUpgradeUnknownScheme() throws Exception {
        final HttpHost target = new HttpHost("somehost", -1, "whatever");
        final HttpRoute route = new HttpRoute(target);
        final HttpContext context = new BasicHttpContext();

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.getIOSession()).thenReturn(ioSession);
        Mockito.when(sslStrategy.upgrade(target, ioSession)).thenReturn(ioSession);

        connman.upgrade(managedConn, route, context);
    }

    @Test(expected=UnsupportedSchemeException.class)
    public void testConnectionUpgradeIllegalScheme() throws Exception {
        final HttpHost target = new HttpHost("somehost", 80, "http");
        final HttpRoute route = new HttpRoute(target);
        final HttpContext context = new BasicHttpContext();

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.getIOSession()).thenReturn(ioSession);
        Mockito.when(sslStrategy.upgrade(target, ioSession)).thenReturn(ioSession);

        connman.upgrade(managedConn, route, context);
    }

    @Test
    public void testConnectionRouteComplete() throws Exception {
        final HttpHost target = new HttpHost("somehost", 80, "http");
        final HttpRoute route = new HttpRoute(target);
        final HttpContext context = new BasicHttpContext();

        final Log log = Mockito.mock(Log.class);
        final CPoolEntry poolentry = new CPoolEntry(log, "some-id", route, conn, -1, TimeUnit.MILLISECONDS);
        poolentry.markRouteComplete();
        final NHttpClientConnection managedConn = CPoolProxy.newProxy(poolentry);

        Mockito.when(conn.getIOSession()).thenReturn(ioSession);
        Mockito.when(sslStrategy.upgrade(target, ioSession)).thenReturn(ioSession);

        connman.startRoute(managedConn, route, context);
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

        final HttpRoute route = new HttpRoute(new HttpHost("somehost", 80));
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

        final HttpRoute route = new HttpRoute(new HttpHost("somehost", 80));
        internalConnFactory.create(route, ioSession);

        Mockito.verify(sslStrategy, Mockito.never()).upgrade(Matchers.eq(new HttpHost("somehost", 80)),
                Matchers.<IOSession>any());
        Mockito.verify(connFactory).create(Matchers.same(ioSession), Matchers.<ConnectionConfig>any());
    }

    @Test
    public void testInternalConnFactoryCreateViaProxy() throws Exception {
        final ConfigData configData = new ConfigData();
        final InternalConnectionFactory internalConnFactory = new InternalConnectionFactory(
            configData, connFactory);

        final HttpHost target = new HttpHost("somehost", 80);
        final HttpHost proxy = new HttpHost("someproxy", 8888);
        final HttpRoute route = new HttpRoute(target, null, proxy, false);

        final ConnectionConfig config = ConnectionConfig.custom().build();
        configData.setConnectionConfig(proxy, config);

        internalConnFactory.create(route, ioSession);

        Mockito.verify(connFactory).create(ioSession, config);
    }

    @Test
    public void testInternalConnFactoryCreateDirect() throws Exception {
        final ConfigData configData = new ConfigData();
        final InternalConnectionFactory internalConnFactory = new InternalConnectionFactory(
            configData, connFactory);

        final HttpHost target = new HttpHost("somehost", 80);
        final HttpRoute route = new HttpRoute(target);

        final ConnectionConfig config = ConnectionConfig.custom().build();
        configData.setConnectionConfig(target, config);

        internalConnFactory.create(route, ioSession);

        Mockito.verify(connFactory).create(ioSession, config);
    }

    @Test
    public void testInternalConnFactoryCreateDefaultConfig() throws Exception {
        final ConfigData configData = new ConfigData();
        final InternalConnectionFactory internalConnFactory = new InternalConnectionFactory(
            configData, connFactory);

        final HttpHost target = new HttpHost("somehost", 80);
        final HttpRoute route = new HttpRoute(target);

        final ConnectionConfig config = ConnectionConfig.custom().build();
        configData.setDefaultConnectionConfig(config);

        internalConnFactory.create(route, ioSession);

        Mockito.verify(connFactory).create(ioSession, config);
    }

    @Test
    public void testInternalConnFactoryCreateGlobalDefaultConfig() throws Exception {
        final ConfigData configData = new ConfigData();
        final InternalConnectionFactory internalConnFactory = new InternalConnectionFactory(
            configData, connFactory);

        final HttpHost target = new HttpHost("somehost", 80);
        final HttpRoute route = new HttpRoute(target);

        configData.setDefaultConnectionConfig(null);

        internalConnFactory.create(route, ioSession);

        Mockito.verify(connFactory).create(ioSession, ConnectionConfig.DEFAULT);
    }

    @Test
    public void testResolveLocalAddress() throws Exception {
        final InternalAddressResolver addressResolver = new InternalAddressResolver(
                schemePortResolver, dnsResolver);

        final HttpHost target = new HttpHost("localhost");
        final byte[] ip = new byte[] {10, 0, 0, 10};
        final HttpRoute route = new HttpRoute(target, InetAddress.getByAddress(ip), false);
        final InetSocketAddress address = (InetSocketAddress) addressResolver.resolveLocalAddress(route);

        Assert.assertNotNull(address);
        Assert.assertEquals(InetAddress.getByAddress(ip), address.getAddress());
        Assert.assertEquals(0, address.getPort());
    }

    @Test
    public void testResolveLocalAddressNull() throws Exception {
        final InternalAddressResolver addressResolver = new InternalAddressResolver(
                schemePortResolver, dnsResolver);

        final HttpHost target = new HttpHost("localhost");
        final HttpRoute route = new HttpRoute(target);
        final InetSocketAddress address = (InetSocketAddress) addressResolver.resolveLocalAddress(route);

        Assert.assertNull(address);
    }

    @Test
    public void testResolveRemoteAddress() throws Exception {
        final InternalAddressResolver addressResolver = new InternalAddressResolver(
                schemePortResolver, dnsResolver);

        final HttpHost target = new HttpHost("somehost", 80);
        final HttpRoute route = new HttpRoute(target);

        Mockito.when(schemePortResolver.resolve(target)).thenReturn(123);
        final byte[] ip = new byte[] {10, 0, 0, 10};
        Mockito.when(dnsResolver.resolve("somehost")).thenReturn(new InetAddress[] {InetAddress.getByAddress(ip)});

        final InetSocketAddress address = (InetSocketAddress) addressResolver.resolveRemoteAddress(route);

        Assert.assertNotNull(address);
        Assert.assertEquals(InetAddress.getByAddress(ip), address.getAddress());
        Assert.assertEquals(123, address.getPort());
    }

    @Test
    public void testResolveRemoteAddressViaProxy() throws Exception {
        final InternalAddressResolver addressResolver = new InternalAddressResolver(
                schemePortResolver, dnsResolver);

        final HttpHost target = new HttpHost("somehost", 80);
        final HttpHost proxy = new HttpHost("someproxy");
        final HttpRoute route = new HttpRoute(target, null, proxy, false);

        Mockito.when(schemePortResolver.resolve(proxy)).thenReturn(8888);
        final byte[] ip = new byte[] {10, 0, 0, 10};
        Mockito.when(dnsResolver.resolve("someproxy")).thenReturn(new InetAddress[] {InetAddress.getByAddress(ip)});

        final InetSocketAddress address = (InetSocketAddress) addressResolver.resolveRemoteAddress(route);

        Assert.assertNotNull(address);
        Assert.assertEquals(InetAddress.getByAddress(ip), address.getAddress());
        Assert.assertEquals(8888, address.getPort());
    }

}
