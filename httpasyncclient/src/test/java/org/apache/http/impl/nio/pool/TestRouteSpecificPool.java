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
package org.apache.http.impl.nio.pool;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.SessionRequest;
import org.junit.Test;
import org.mockito.Mockito;

public class TestRouteSpecificPool {

    static class LocalRoutePool extends RouteSpecificPool<String, PoolEntry<String>> {

        public LocalRoutePool() {
            super("whatever");
        }

        @Override
        protected PoolEntry<String> createEntry(String route, IOSession session) {
            return new PoolEntry<String>(route, session, 0L, TimeUnit.MILLISECONDS);
        }

    };

    @Test
    public void testEmptyPool() throws Exception {
        LocalRoutePool pool = new LocalRoutePool();
        Assert.assertEquals(0, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(0, pool.getPendingCount());
        Assert.assertEquals("[route: whatever][leased: 0][available: 0][pending: 0]", pool.toString());
    }

    @Test
    public void testSuccessfulConnect() throws Exception {
        LocalRoutePool pool = new LocalRoutePool();
        IOSession session = Mockito.mock(IOSession.class);
        SessionRequest sessionRequest = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest.getSession()).thenReturn(session);
        BasicPoolEntryCallback callback = new BasicPoolEntryCallback();
        pool.addPending(sessionRequest, callback);
        Assert.assertEquals(1, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(1, pool.getPendingCount());
        PoolEntry<String> entry = pool.completed(sessionRequest);
        Assert.assertNotNull(entry);
        Assert.assertSame(session, entry.getIOSession());
        Assert.assertTrue(callback.isCompleted());
        Assert.assertFalse(callback.isFailed());
        Assert.assertFalse(callback.isCancelled());

        Assert.assertEquals(1, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(1, pool.getLeasedCount());
        Assert.assertEquals(0, pool.getPendingCount());
    }

    @Test
    public void testFailedConnect() throws Exception {
        LocalRoutePool pool = new LocalRoutePool();
        IOException ex = new IOException();
        SessionRequest sessionRequest = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest.getException()).thenReturn(ex);
        BasicPoolEntryCallback callback = new BasicPoolEntryCallback();
        pool.addPending(sessionRequest, callback);
        Assert.assertEquals(1, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(1, pool.getPendingCount());
        pool.failed(sessionRequest);
        Assert.assertFalse(callback.isCompleted());
        Assert.assertTrue(callback.isFailed());
        Assert.assertFalse(callback.isCancelled());

        Assert.assertEquals(0, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(0, pool.getPendingCount());
    }

    @Test
    public void testCancelledConnect() throws Exception {
        LocalRoutePool pool = new LocalRoutePool();
        SessionRequest sessionRequest = Mockito.mock(SessionRequest.class);
        BasicPoolEntryCallback callback = new BasicPoolEntryCallback();
        pool.addPending(sessionRequest, callback);
        Assert.assertEquals(1, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(1, pool.getPendingCount());
        pool.cancelled(sessionRequest);
        Assert.assertFalse(callback.isCompleted());
        Assert.assertFalse(callback.isFailed());
        Assert.assertTrue(callback.isCancelled());

        Assert.assertEquals(0, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(0, pool.getPendingCount());
    }

    @Test
    public void testConnectTimeout() throws Exception {
        LocalRoutePool pool = new LocalRoutePool();
        SessionRequest sessionRequest = Mockito.mock(SessionRequest.class);
        BasicPoolEntryCallback callback = new BasicPoolEntryCallback();
        pool.addPending(sessionRequest, callback);
        Assert.assertEquals(1, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(1, pool.getPendingCount());
        pool.timeout(sessionRequest);
        Assert.assertFalse(callback.isCompleted());
        Assert.assertTrue(callback.isFailed());
        Assert.assertFalse(callback.isCancelled());
        Assert.assertTrue(callback.getException() instanceof SocketTimeoutException);

        Assert.assertEquals(0, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(0, pool.getPendingCount());
    }

    @Test
    public void testLeaseRelease() throws Exception {
        LocalRoutePool pool = new LocalRoutePool();
        IOSession session1 = Mockito.mock(IOSession.class);
        SessionRequest sessionRequest1 = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest1.getSession()).thenReturn(session1);
        pool.addPending(sessionRequest1, new BasicPoolEntryCallback());
        IOSession session2 = Mockito.mock(IOSession.class);
        SessionRequest sessionRequest2 = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest2.getSession()).thenReturn(session2);
        pool.addPending(sessionRequest2, new BasicPoolEntryCallback());
        IOSession session3 = Mockito.mock(IOSession.class);
        SessionRequest sessionRequest3 = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest3.getSession()).thenReturn(session3);
        pool.addPending(sessionRequest3, new BasicPoolEntryCallback());

        Assert.assertEquals(3, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(3, pool.getPendingCount());

        PoolEntry<String> entry1 = pool.completed(sessionRequest1);
        Assert.assertNotNull(entry1);
        PoolEntry<String> entry2 = pool.completed(sessionRequest2);
        Assert.assertNotNull(entry2);
        PoolEntry<String> entry3 = pool.completed(sessionRequest3);
        Assert.assertNotNull(entry3);

        Assert.assertEquals(3, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(3, pool.getLeasedCount());
        Assert.assertEquals(0, pool.getPendingCount());

        pool.freeEntry(entry1, true);
        pool.freeEntry(entry2, false);
        pool.freeEntry(entry3, true);

        Assert.assertEquals(2, pool.getAllocatedCount());
        Assert.assertEquals(2, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(0, pool.getPendingCount());

        Assert.assertNotNull(pool.getFreeEntry(null));
        Assert.assertNotNull(pool.getFreeEntry(null));
        Assert.assertNull(pool.getFreeEntry(null));

        Assert.assertEquals(2, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(2, pool.getLeasedCount());
        Assert.assertEquals(0, pool.getPendingCount());
    }

    @Test
    public void testLeaseReleaseStateful() throws Exception {
        LocalRoutePool pool = new LocalRoutePool();
        IOSession session1 = Mockito.mock(IOSession.class);
        SessionRequest sessionRequest1 = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest1.getSession()).thenReturn(session1);
        pool.addPending(sessionRequest1, new BasicPoolEntryCallback());
        IOSession session2 = Mockito.mock(IOSession.class);
        SessionRequest sessionRequest2 = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest2.getSession()).thenReturn(session2);
        pool.addPending(sessionRequest2, new BasicPoolEntryCallback());
        IOSession session3 = Mockito.mock(IOSession.class);
        SessionRequest sessionRequest3 = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest3.getSession()).thenReturn(session3);
        pool.addPending(sessionRequest3, new BasicPoolEntryCallback());

        PoolEntry<String> entry1 = pool.completed(sessionRequest1);
        Assert.assertNotNull(entry1);
        PoolEntry<String> entry2 = pool.completed(sessionRequest2);
        Assert.assertNotNull(entry2);
        PoolEntry<String> entry3 = pool.completed(sessionRequest3);
        Assert.assertNotNull(entry3);

        entry2.setState(Boolean.FALSE);
        pool.freeEntry(entry1, true);
        pool.freeEntry(entry2, true);
        pool.freeEntry(entry3, true);

        Assert.assertEquals(entry2, pool.getFreeEntry(Boolean.FALSE));
        Assert.assertEquals(entry1, pool.getFreeEntry(Boolean.FALSE));
        Assert.assertEquals(entry3, pool.getFreeEntry(null));
        Assert.assertEquals(null, pool.getFreeEntry(null));

        entry1.setState(Boolean.TRUE);
        entry2.setState(Boolean.FALSE);
        entry3.setState(Boolean.TRUE);
        pool.freeEntry(entry1, true);
        pool.freeEntry(entry2, true);
        pool.freeEntry(entry3, true);

        Assert.assertEquals(null, pool.getFreeEntry(null));
        Assert.assertEquals(entry2, pool.getFreeEntry(Boolean.FALSE));
        Assert.assertEquals(null, pool.getFreeEntry(Boolean.FALSE));
        Assert.assertEquals(entry1, pool.getFreeEntry(Boolean.TRUE));
        Assert.assertEquals(entry3, pool.getFreeEntry(Boolean.TRUE));
        Assert.assertEquals(null, pool.getFreeEntry(Boolean.TRUE));
    }

    @Test(expected=IllegalStateException.class)
    public void testReleaseInvalidEntry() throws Exception {
        LocalRoutePool pool = new LocalRoutePool();
        IOSession session = Mockito.mock(IOSession.class);
        PoolEntry<String> entry = new PoolEntry<String>("whatever", session, 0L, TimeUnit.MILLISECONDS);
        pool.freeEntry(entry, true);
    }

    @Test
    public void testRemove() throws Exception {
        LocalRoutePool pool = new LocalRoutePool();
        IOSession session1 = Mockito.mock(IOSession.class);
        SessionRequest sessionRequest1 = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest1.getSession()).thenReturn(session1);
        pool.addPending(sessionRequest1, new BasicPoolEntryCallback());
        IOSession session2 = Mockito.mock(IOSession.class);
        SessionRequest sessionRequest2 = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest2.getSession()).thenReturn(session2);
        pool.addPending(sessionRequest2, new BasicPoolEntryCallback());
        IOSession session3 = Mockito.mock(IOSession.class);
        SessionRequest sessionRequest3 = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest3.getSession()).thenReturn(session3);
        pool.addPending(sessionRequest3, new BasicPoolEntryCallback());

        Assert.assertEquals(3, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(3, pool.getPendingCount());

        PoolEntry<String> entry1 = pool.completed(sessionRequest1);
        Assert.assertNotNull(entry1);
        PoolEntry<String> entry2 = pool.completed(sessionRequest2);
        Assert.assertNotNull(entry2);
        PoolEntry<String> entry3 = pool.completed(sessionRequest3);
        Assert.assertNotNull(entry3);

        Assert.assertEquals(3, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(3, pool.getLeasedCount());
        Assert.assertEquals(0, pool.getPendingCount());

        Assert.assertTrue(pool.remove(entry2));
        Assert.assertFalse(pool.remove(entry2));

        Assert.assertEquals(2, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(2, pool.getLeasedCount());
        Assert.assertEquals(0, pool.getPendingCount());

        pool.freeEntry(entry1, true);
        pool.freeEntry(entry3, true);

        Assert.assertEquals(2, pool.getAllocatedCount());
        Assert.assertEquals(2, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(0, pool.getPendingCount());

        Assert.assertTrue(pool.remove(entry1));
        Assert.assertTrue(pool.remove(entry3));

        Assert.assertEquals(0, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(0, pool.getPendingCount());
    }

    @Test(expected=IllegalArgumentException.class)
    public void testReleaseInvalid() throws Exception {
        LocalRoutePool pool = new LocalRoutePool();
        pool.freeEntry(null, true);
    }

    @Test(expected=IllegalArgumentException.class)
    public void testRemoveInvalid() throws Exception {
        LocalRoutePool pool = new LocalRoutePool();
        pool.remove(null);
    }

    @Test
    public void testShutdown() throws Exception {
        LocalRoutePool pool = new LocalRoutePool();
        IOSession session1 = Mockito.mock(IOSession.class);
        SessionRequest sessionRequest1 = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest1.getSession()).thenReturn(session1);
        pool.addPending(sessionRequest1, new BasicPoolEntryCallback());
        IOSession session2 = Mockito.mock(IOSession.class);
        SessionRequest sessionRequest2 = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest2.getSession()).thenReturn(session2);
        pool.addPending(sessionRequest2, new BasicPoolEntryCallback());
        IOSession session3 = Mockito.mock(IOSession.class);
        SessionRequest sessionRequest3 = Mockito.mock(SessionRequest.class);
        Mockito.when(sessionRequest3.getSession()).thenReturn(session3);
        pool.addPending(sessionRequest3, new BasicPoolEntryCallback());

        PoolEntry<String> entry1 = pool.completed(sessionRequest1);
        Assert.assertNotNull(entry1);
        PoolEntry<String> entry2 = pool.completed(sessionRequest2);
        Assert.assertNotNull(entry2);

        pool.freeEntry(entry1, true);

        Assert.assertEquals(3, pool.getAllocatedCount());
        Assert.assertEquals(1, pool.getAvailableCount());
        Assert.assertEquals(1, pool.getLeasedCount());
        Assert.assertEquals(1, pool.getPendingCount());

        pool.shutdown();

        Assert.assertEquals(0, pool.getAllocatedCount());
        Assert.assertEquals(0, pool.getAvailableCount());
        Assert.assertEquals(0, pool.getLeasedCount());
        Assert.assertEquals(0, pool.getPendingCount());

        Mockito.verify(sessionRequest3).cancel();
        Mockito.verify(session2).close();
        Mockito.verify(session1).close();
    }

}
