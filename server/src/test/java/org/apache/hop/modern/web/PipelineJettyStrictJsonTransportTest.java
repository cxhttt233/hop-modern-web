/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.server.Server;
import org.glassfish.jersey.jetty.JettyHttpContainerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Architecture-aligned, isolated Jetty/Jersey transport feasibility proof.
 *
 * Unlike the Grizzly strict-mapper probe, this starts Jersey on Jetty while
 * keeping the Task5 ResourceConfig, Jackson feature, OpenRequest record,
 * reusable Java 21 HttpClient, fixture, and security assertions unchanged.
 * It does not switch the production host or relax the failing Grizzly tests.
 * A timeout is a failure, never a successful HTTP 400 rejection.
 */
class PipelineJettyStrictJsonTransportTest {

  @Test
  @Timeout(value = 110, unit = TimeUnit.SECONDS)
  void strictJsonMustRejectInvalidBodiesWithoutBreakingPersistentRequests() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    URI base = URI.create("http://127.0.0.1:" + port + "/");
    Server server = JettyHttpContainerFactory.createServer(
        base, ModernWebServer.createResourceConfig()
            .register(new PipelineStrictMapperCompatibilityTest.StrictMapperProvider())
            .register(PipelineHttpJsonBoundaryABTest.JsonEchoResource.class));
    try {
      HttpClient client = HttpClient.newHttpClient();
      URI echo = base.resolve("api/diagnostic/echo");
      URI open = base.resolve("api/pipelines/open");
      String valid = "{\"path\":\"/definitely-not-present/strict-mapper.hpl\"}";
      String concat = valid + "{\"path\":\"/another.hpl\"}";
      String garbage = valid + " NOT_JSON";
      String duplicate = "{\"path\":\"/first.hpl\",\"path\":\"/second.hpl\"}";

      Result before = post(client, echo, valid);
      Result concatEcho = post(client, echo, concat);
      Result garbageEcho = post(client, echo, garbage);
      Result duplicateEcho = post(client, echo, duplicate);
      Result malformed = post(client, echo, "{\"path\":");
      Result empty = post(client, echo, "");
      Result concatOpen = post(client, open, concat);
      Result garbageOpen = post(client, open, garbage);
      Result after = post(client, echo, valid);

      System.out.printf(
          "JETTY_SAME_LISTENER_JSON_STRICT before=%d concat=%d garbage=%d duplicate=%d"
              + " malformed=%d empty=%d openConcat=%d openGarbage=%d after=%d%n",
          before.status(), concatEcho.status(), garbageEcho.status(),
          duplicateEcho.status(), malformed.status(), empty.status(),
          concatOpen.status(), garbageOpen.status(), after.status());

      assertAll("Jetty must preserve strict JSON semantics and connection reuse",
          () -> assertEquals(200, before.status(), before.body()),
          () -> assertEquals(400, concatEcho.status(), concatEcho.body()),
          () -> assertEquals(400, garbageEcho.status(), garbageEcho.body()),
          () -> assertEquals(400, duplicateEcho.status(), duplicateEcho.body()),
          () -> assertEquals(400, malformed.status(), malformed.body()),
          () -> assertEquals(400, empty.status(), empty.body()),
          () -> assertEquals(400, concatOpen.status(), concatOpen.body()),
          () -> assertEquals(400, garbageOpen.status(), garbageOpen.body()),
          () -> assertEquals(200, after.status(), after.body()));
    } finally {
      server.stop();
    }
  }

  private static Result post(HttpClient client, URI uri, String body) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(7))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return new Result(response.statusCode(), response.body());
    } catch (java.io.IOException failure) {
      return new Result(-1, failure.toString());
    }
  }

  private record Result(int status, String body) {}
}
