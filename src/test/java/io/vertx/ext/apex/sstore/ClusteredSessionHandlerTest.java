/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.apex.sstore;

import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.ext.apex.Router;
import io.vertx.ext.apex.Session;
import io.vertx.ext.apex.handler.CookieHandler;
import io.vertx.ext.apex.handler.SessionHandler;
import io.vertx.ext.apex.handler.SessionHandlerTestBase;
import io.vertx.ext.apex.handler.SomeSerializable;
import io.vertx.ext.apex.sstore.impl.SessionImpl;
import io.vertx.test.core.TestUtils;
import io.vertx.test.fakecluster.FakeClusterManager;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class ClusteredSessionHandlerTest extends SessionHandlerTestBase {

  int numNodes = 3;
  byte[] bytes = TestUtils.randomByteArray(100);
  Buffer buffer = TestUtils.randomBuffer(100);

  @Override
  public void setUp() throws Exception {
    super.setUp();
    VertxOptions options = new VertxOptions();
    options.setClustered(true);
    options.setClusterManager(getClusterManager());
    startNodes(numNodes, options);
    store = ClusteredSessionStore.create(vertices[0]);
  }

  @Override
  protected ClusterManager getClusterManager() {
    return new FakeClusterManager();
  }

  @Test
  public void testClusteredSession() throws Exception {
    Router router1 = Router.router(vertices[0]);
    router1.route().handler(CookieHandler.create());
    SessionStore store1 = ClusteredSessionStore.create(vertices[0]);
    router1.route().handler(SessionHandler.create(store1));
    HttpServer server1 = vertices[0].createHttpServer(new HttpServerOptions().setPort(8081).setHost("localhost"));
    server1.requestHandler(router1::accept);
    CountDownLatch latch1 = new CountDownLatch(1);
    server1.listen(onSuccess(s -> latch1.countDown()));
    HttpClient client1 = vertices[0].createHttpClient(new HttpClientOptions());

    Router router2 = Router.router(vertices[1]);
    router2.route().handler(CookieHandler.create());
    SessionStore store2 = ClusteredSessionStore.create(vertices[1]);
    router2.route().handler(SessionHandler.create(store2));
    HttpServer server2 = vertices[1].createHttpServer(new HttpServerOptions().setPort(8082).setHost("localhost"));
    server2.requestHandler(router2::accept);
    CountDownLatch latch2 = new CountDownLatch(1);
    server2.listen(onSuccess(s -> latch2.countDown()));
    HttpClient client2 = vertices[0].createHttpClient(new HttpClientOptions());

    Router router3 = Router.router(vertices[2]);
    router3.route().handler(CookieHandler.create());
    SessionStore store3 = ClusteredSessionStore.create(vertices[2]);
    router3.route().handler(SessionHandler.create(store3));
    HttpServer server3 = vertices[2].createHttpServer(new HttpServerOptions().setPort(8083).setHost("localhost"));
    server3.requestHandler(router3::accept);
    CountDownLatch latch3 = new CountDownLatch(1);
    server3.listen(onSuccess(s -> latch3.countDown()));
    HttpClient client3 = vertices[0].createHttpClient(new HttpClientOptions());

    router1.route().handler(rc -> {
      Session sess = rc.session();
      sess.put("foo", "bar");
      stuffSession(sess);
      rc.response().end();
    });

    router2.route().handler(rc -> {
      Session sess = rc.session();
      checkSession(sess);
      assertEquals("bar", sess.get("foo"));
      sess.put("eek", "wibble");
      rc.response().end();
    });

    router3.route().handler(rc -> {
      Session sess = rc.session();
      checkSession(sess);
      assertEquals("bar", sess.get("foo"));
      assertEquals("wibble", sess.get("eek"));
      rc.response().end();
    });

    AtomicReference<String> rSetCookie = new AtomicReference<>();
    testRequestBuffer(client1, HttpMethod.GET, 8081, "/", null, resp -> {
      String setCookie = resp.headers().get("set-cookie");
      rSetCookie.set(setCookie);
    }, 200, "OK", null);
    // FIXME - for now we do an artificial sleep because it's possible the session hasn't been stored properly before
    // the next request hits the server
    // https://github.com/vert-x3/vertx-apex/issues/93
    Thread.sleep(1000);
    testRequestBuffer(client2, HttpMethod.GET, 8082, "/", req -> {
      req.putHeader("cookie", rSetCookie.get());
    }, null, 200, "OK", null);
    Thread.sleep(1000);
    testRequestBuffer(client3, HttpMethod.GET, 8083, "/", req -> {
      req.putHeader("cookie", rSetCookie.get());
    }, null, 200, "OK", null);

  }

  @Test
  public void testSessionSerializationNullPrincipal() {
    long timeout = 123;
    SessionImpl session = (SessionImpl)store.createSession(timeout);
    assertTrue(session.getRoles().isEmpty());
    assertTrue(session.getPermissions().isEmpty());
    stuffSession(session);
    checkSession(session);
    Buffer buffer = session.writeToBuffer();
    SessionImpl session2 = (SessionImpl)store.createSession(0);
    session2.readFromBuffer(buffer);
    checkSession(session2);
    assertEquals(timeout, session2.timeout());
    assertEquals(session.id(), session2.id());
    assertNull(session2.getPrincipal());
    assertTrue(session2.getRoles().isEmpty());
    assertTrue(session2.getPermissions().isEmpty());
  }

  @Test
  public void testSessionSerializationWithPrincipal() {
    long timeout = 123;
    SessionImpl session = (SessionImpl)store.createSession(timeout);
    JsonObject principal = new JsonObject().put("foo", "bar").put("blah", 123);
    session.setPrincipal(principal);
    stuffSession(session);
    checkSession(session);
    Buffer buffer = session.writeToBuffer();
    SessionImpl session2 = (SessionImpl)store.createSession(0);
    session2.readFromBuffer(buffer);
    checkSession(session2);
    assertEquals(session.id(), session2.id());
    assertEquals(timeout, session2.timeout());
    assertEquals(principal, session2.getPrincipal());
  }

  @Test
  public void testSessionSerializationWithRolesAndPermissions() {
    long timeout = 123;
    SessionImpl session = (SessionImpl)store.createSession(timeout);
    session.getRoles().add("role1");
    session.getRoles().add("role2");
    session.getRoles().add("role3");
    session.getPermissions().add("perm1");
    session.getPermissions().add("perm2");
    session.getPermissions().add("perm3");
    stuffSession(session);
    checkSession(session);
    Buffer buffer = session.writeToBuffer();
    SessionImpl session2 = (SessionImpl)store.createSession(0);
    session2.readFromBuffer(buffer);
    checkSession(session2);
    assertEquals(session.id(), session2.id());
    assertEquals(timeout, session2.timeout());
    assertEquals(3, session2.getRoles().size());
    assertTrue(session2.getRoles().contains("role1"));
    assertTrue(session2.getRoles().contains("role2"));
    assertTrue(session2.getRoles().contains("role3"));
    assertEquals(3, session2.getPermissions().size());
    assertTrue(session2.getPermissions().contains("perm1"));
    assertTrue(session2.getPermissions().contains("perm2"));
    assertTrue(session2.getPermissions().contains("perm3"));
  }

  private void stuffSession(Session session) {
    session.put("somelong", 123456l);
    session.put("someint", 1234);
    session.put("someshort", (short)123);
    session.put("somebyte", (byte)12);
    session.put("somedouble", 123.456d);
    session.put("somefloat", 123.456f);
    session.put("somechar", 'X');
    session.put("somebooleantrue", true);
    session.put("somebooleanfalse", false);
    session.put("somestring", "wibble");
    session.put("somebytes", bytes);
    session.put("somebuffer", buffer);
    session.put("someserializable", new SomeSerializable("eek"));
    session.put("someclusterserializable", new JsonObject().put("foo", "bar"));
  }

  private void checkSession(Session session) {
    assertEquals(123456l, (long)session.get("somelong"));
    assertEquals(1234, (int)session.get("someint"));
    assertEquals((short)123, (short)session.get("someshort"));
    assertEquals((byte)12, (byte)session.get("somebyte"));
    assertEquals(123.456d, (double)session.get("somedouble"), 0);
    assertEquals(123.456f, (float)session.get("somefloat"), 0);
    assertEquals('X', (char)session.get("somechar"));
    assertTrue(session.get("somebooleantrue"));
    assertFalse(session.get("somebooleanfalse"));
    assertEquals("wibble", session.get("somestring"));
    assertTrue(TestUtils.byteArraysEqual(bytes, session.get("somebytes")));
    assertEquals(buffer, session.get("somebuffer"));
    JsonObject json = session.get("someclusterserializable");
    assertNotNull(json);
    assertEquals("bar", json.getString("foo"));
  }

}


