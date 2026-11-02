/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Formal same-listener HTTP proof that startup readiness cannot pass on a 503. */
class ModernWebReadinessHttpIntegrationTest {
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void reports503UntilPluginsAreInitializedThen200OnRepeatedRequests() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    AtomicBoolean initialized = new AtomicBoolean(false);
    URI base = URI.create("http://127.0.0.1:" + port + "/");
    HttpServer server = GrizzlyHttpServerFactory.createHttpServer(
        base, new ResourceConfig().register(new ReadinessResource(initialized::get)));
    try {
      HttpClient client = HttpClient.newHttpClient();
      URI endpoint = base.resolve("api/ready");
      HttpResponse<String> before = get(client, endpoint);
      initialized.set(true);
      HttpResponse<String> after = get(client, endpoint);
      HttpResponse<String> again = get(client, endpoint);

      assertEquals(503, before.statusCode(), before.body());
      assertEquals("not_ready", before.body());
      assertEquals(200, after.statusCode(), after.body());
      assertEquals("ready", after.body());
      assertEquals(200, again.statusCode(), again.body());
      assertEquals("ready", again.body());
      assertTrue(after.headers().firstValue("Cache-Control").orElse("")
          .contains("no-store"), "Readiness must not be cached");
    } finally {
      server.shutdownNow();
    }
  }

  private static HttpResponse<String> get(HttpClient client, URI endpoint) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(endpoint)
        .timeout(Duration.ofSeconds(8))
        .GET()
        .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
