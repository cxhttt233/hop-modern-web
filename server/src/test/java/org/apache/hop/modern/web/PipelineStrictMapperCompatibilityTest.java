/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ext.ContextResolver;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test-only compatibility probe: can an explicitly strict Jackson mapper reject
 * trailing and duplicate JSON while the production READ_FULL_STREAM=false
 * provider setting avoids the observed Grizzly end-of-stream hang?
 *
 * This does NOT install the mapper in production. A failed or timed-out request
 * is recorded as -1, not accepted as a successful rejection.
 */
class PipelineStrictMapperCompatibilityTest {

  @Test
  @Timeout(value = 110, unit = TimeUnit.SECONDS)
  void probesStrictMapperOnTheActualGrizzlyJerseyStack() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    URI base = URI.create("http://127.0.0.1:" + port + "/");
    HttpServer server = GrizzlyHttpServerFactory.createHttpServer(
        base,
        ModernWebServer.createResourceConfig()
            .register(new StrictMapperProvider())
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
      Result after = post(client, echo, valid);

      System.out.printf(
          "SAME_STACK_STRICT_MAPPER before=%d concat=%d garbage=%d duplicate=%d"
              + " malformed=%d empty=%d openConcat=%d after=%d%n",
          before.status(), concatEcho.status(), garbageEcho.status(),
          duplicateEcho.status(), malformed.status(), empty.status(),
          concatOpen.status(), after.status());

      assertAll("Strict mapper must reject invalid JSON promptly without blocking valid requests",
          () -> assertEquals(200, before.status(), before.body()),
          () -> assertEquals(400, concatEcho.status(), concatEcho.body()),
          () -> assertEquals(400, garbageEcho.status(), garbageEcho.body()),
          () -> assertEquals(400, duplicateEcho.status(), duplicateEcho.body()),
          () -> assertEquals(400, malformed.status(), malformed.body()),
          () -> assertEquals(400, empty.status(), empty.body()),
          () -> assertEquals(400, concatOpen.status(), concatOpen.body()),
          () -> assertEquals(200, after.status(), after.body()));
    } finally {
      server.shutdownNow();
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
    } catch (HttpTimeoutException timeout) {
      return new Result(-1, "HTTP request timed out after seven seconds: " + timeout);
    }
  }

  private record Result(int status, String body) {}

  public static final class StrictMapperProvider implements ContextResolver<ObjectMapper> {
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    @Override
    public ObjectMapper getContext(Class<?> type) {
      return mapper;
    }
  }
}
