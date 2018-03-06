/*******************************************************************************
 * Copyright (c) 2018 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.session.cache.fat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import componenttest.annotation.Server;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.topology.impl.LibertyServer;
import componenttest.topology.utils.FATServletClient;

@RunWith(FATRunner.class)
public class SessionCacheOneServerTest extends FATServletClient {

    @Server("sessionCacheServer")
    public static LibertyServer server;

    public static SessionCacheApp app = null;

    public static final ExecutorService executor = Executors.newFixedThreadPool(10);

    @BeforeClass
    public static void setUp() throws Exception {
        app = new SessionCacheApp(server, "session.cache.web", "session.cache.web.listener1", "session.cache.web.listener2");
        server.startServer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        executor.shutdownNow();
        server.stopServer();
    }

    /**
     * Submit concurrent requests to put new attributes into a single session.
     * Verify that all of the attributes (and no others) are added to the session, with their respective values.
     */
    @Test
    public void testConcurrentPutNewAttributes() throws Exception {
        final int NUM_THREADS = 9;

        List<String> session = new ArrayList<>();

        StringBuilder attributeNames = new StringBuilder("testConcurrentPutNewAttributes-key0");

        List<Callable<Void>> puts = new ArrayList<Callable<Void>>();
        List<Callable<Void>> gets = new ArrayList<Callable<Void>>();
        for (int i = 1; i <= NUM_THREADS; i++) {
            final int offset = i;
            attributeNames.append(",testConcurrentPutNewAttributes-key").append(i);
            puts.add(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    app.sessionPut("testConcurrentPutNewAttributes-key" + offset, 'A' + offset, session, true);
                    return null;
                }
            });
            gets.add(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    app.sessionGet("testConcurrentPutNewAttributes-key" + offset, 'A' + offset, session);
                    return null;
                }
            });
        }

        app.sessionPut("testConcurrentPutNewAttributes-key0", 'A', session, true);
        try {
            List<Future<Void>> futures = executor.invokeAll(puts);
            for (Future<Void> future : futures)
                future.get(); // report any exceptions that might have occurred

            app.invokeServlet("testAttributeNames&allowOtherAttributes=false&sessionAttributes=" + attributeNames, session);

            futures = executor.invokeAll(gets);
            for (Future<Void> future : futures)
                future.get(); // report any exceptions that might have occurred
        } finally {
            app.invalidateSession(session);
        }
    }

    /**
     * Submit concurrent requests to replace the value of the same attributes within a single session.
     */
    @Test
    public void testConcurrentReplaceAttributes() throws Exception {
        final int NUM_ATTRS = 2;
        final int NUM_THREADS = 8;

        List<String> session = new ArrayList<>();

        Map<String, String> expectedValues = new TreeMap<String, String>();
        List<Callable<Void>> puts = new ArrayList<Callable<Void>>();
        for (int i = 1; i <= NUM_ATTRS; i++) {
            final String key = "testConcurrentReplaceAttributes-key" + i;
            StringBuilder sb = new StringBuilder();

            for (int j = 1; j <= NUM_THREADS / NUM_ATTRS; j++) {
                final int value = i * 100 + j;
                if (j > 1)
                    sb.append(',');
                sb.append(value);

                puts.add(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        app.sessionPut(key, value, session, false);
                        return null;
                    }
                });
            }

            expectedValues.put(key, sb.toString());
        }

        app.sessionPut("testConcurrentReplaceAttributes-key1", 100, session, true);
        try {
            app.sessionPut("testConcurrentReplaceAttributes-key2", 200, session, false);

            List<Future<Void>> futures = executor.invokeAll(puts);
            for (Future<Void> future : futures)
                future.get(); // report any exceptions that might have occurred

            app.invokeServlet("testAttributeNames&allowOtherAttributes=false&sessionAttributes=testConcurrentReplaceAttributes-key1,testConcurrentReplaceAttributes-key2", session);

            for (Map.Entry<String, String> expected : expectedValues.entrySet())
                app.invokeServlet("testAttributeIsAnyOf&type=java.lang.Integer&key=" + expected.getKey() + "&values=" + expected.getValue(), session);
        } finally {
            app.invalidateSession(session);
        }
    }

    /**
     * Verify that the time reported as the creation time of the session is reasonably close to when we created it.
     */
    @Test
    public void testCreationTime() throws Exception {
        List<String> session = new ArrayList<>();
        app.invokeServlet("testCreationTime", session);
        app.invalidateSession(session);
    }

    /**
     * Verify that two HttpSessionListeners both receive events when sessions are created and destroyed.
     */
    @Test
    public void testHttpSessionListeners() throws Exception {
        List<String> session1 = new ArrayList<>();
        String sessionId1 = app.sessionPut("testHttpSessionListeners-key1", (byte) 10, session1, true);
        try {
            // Registered HttpSessionListeners listener1 and listener2 must be notified of
            // the creation of session1, and at this point must contain no record of it being destroyed,

            app.invokeServlet("testHttpSessionListener&listener=listener1" +
                              "&sessionCreated=" + sessionId1 +
                              "&sessionNotDestroyed=" + sessionId1,
                              null);

            app.sessionGet("testHttpSessionListeners-key1", (byte) 10, session1);

            app.invokeServlet("testHttpSessionListener&listener=listener2" +
                              "&sessionCreated=" + sessionId1 +
                              "&sessionNotDestroyed=" + sessionId1,
                              null);
        } finally {
            // Invalidating the session should cause sessionDestroyed to be sent to the listeners
            app.invalidateSession(session1);
        }

        List<String> session2 = new ArrayList<>();
        String sessionId2 = app.sessionPut("testHttpSessionListeners-key2", true, session2, true);
        try {
            // Registered HttpSessionListeners listener1 and listener2 must be notified of
            // the creation of session2, and at this point must contain no record of it being destroyed.
            // They should however, indicate that session1 was destroyed,

            app.invokeServlet("testHttpSessionListener&listener=listener1" +
                              "&sessionCreated=" + sessionId2 +
                              "&sessionDestroyed=" + sessionId1 +
                              "&sessionNotDestroyed=" + sessionId2,
                              null);

            app.invokeServlet("testHttpSessionListener&listener=listener2" +
                              "&sessionCreated=" + sessionId2 +
                              "&sessionDestroyed=" + sessionId1 +
                              "&sessionNotDestroyed=" + sessionId2,
                              null);

            app.sessionGet("testHttpSessionListeners-key2", true, session2);
        } finally {
            // Invalidating the session should cause sessionDestroyed to be sent to the listeners
            app.invalidateSession(session2);
        }

        // Registered HttpSessionListeners listener1 and listener2 must be notified of
        // the destruction of session2,

        app.invokeServlet("testHttpSessionListener&listener=listener1" +
                          "&sessionDestroyed=" + sessionId2,
                          null);
        app.invokeServlet("testHttpSessionListener&listener=listener2" +
                          "&sessionDestroyed=" + sessionId2,
                          null);
    }

    /**
     * Test that the last accessed time changes when accessed at different times.
     */
    @Test
    public void testLastAccessedTime() throws Exception {
        List<String> session = new ArrayList<>();
        app.invokeServlet("testLastAccessedTime", session);
        app.invalidateSession(session);
    }

    /**
     * Ensure that various types of objects can be stored in a session,
     * serialized when the session is evicted from memory, and deserialized
     * when the session is accessed again.
     */
    @Test
    public void testSerialization() throws Exception {
        List<String> session = new ArrayList<>();
        app.invokeServlet("testSerialization", session);
        try {
            app.invokeServlet("evictSession", null);
            app.invokeServlet("testSerialization_complete", session);
        } finally {
            app.invalidateSession(session);
        }
    }

    /**
     * Ensure that various types of objects can be stored in a session,
     * serialized when the session is evicted from memory, and deserialized
     * when the session is accessed again.
     */
    @Test
    public void testSerializeDataSource() throws Exception {
        List<String> session = new ArrayList<>();
        app.invokeServlet("testSerializeDataSource", session);
        try {
            app.invokeServlet("evictSession", null);
            app.invokeServlet("testSerializeDataSource_complete", session);
        } finally {
            app.invalidateSession(session);
        }
    }

}
