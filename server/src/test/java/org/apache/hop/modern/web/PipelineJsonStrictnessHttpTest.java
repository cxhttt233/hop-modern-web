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
import java.util.concurrent.TimeUnit;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * HTTP security regression for Jersey's READ_FULL_STREAM=false workaround.
 * The diagnostic endpoint is test-only, but uses the production ResourceConfig,
 * Jackson provider, Grizzly listener, and a single reusable Java HTTP client.
 * A 200 on either trailing-input case is a security regression, not a pass.
 */
class PipelineJsonStrictnessHttpTest {

  @Test
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  void rejectsTrailingAndMalformedJsonWithoutBreakingSubsequentRequests() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    URI base = URI.create("http://127.0.0.1:" + port + "/");
    HttpServer server = GrizzlyHttpServerFactory.createHttpServer(
        base,
        ModernWebServer.createResourceConfig()
            .register(PipelineHttpJsonBoundaryABTest.JsonEchoResource.class));
    try {
      HttpClient client = HttpClient.newHttpClient();
      URI echo = base.resolve("api/diagnostic/echo");
      URI open = base.resolve("api/pipelines/open");
      String valid = "{\"path\":\"/definitely-not-present/strictness.hpl\"}";
      String concatenated = valid + "{\"path\":\"/another.hpl\"}";
      String trailingGarbage = valid + " NOT_JSON";

      HttpResponse<String> validBefore = post(client, echo, valid);
      HttpResponse<String> concatenatedEcho = post(client, echo, concatenated);
      HttpResponse<String> trailingEcho = post(client, echo, trailingGarbage);
      HttpResponse<String> malformed = post(client, echo, "{\"path\":");
      HttpResponse<String> empty = post(client, echo, "");
      HttpResponse<String> concatenatedOpen = post(client, open, concatenated);
      HttpResponse<String> trailingOpen = post(client, open, trailingGarbage);
      HttpResponse<String> validAfter = post(client, echo, valid);

      System.out.printf(
          "SAME_SERVER_JSON_STRICT before=%d concatEcho=%d garbageEcho=%d malformed=%d"
              + " empty=%d concatOpen=%d garbageOpen=%d after=%d%n",
          validBefore.statusCode(), concatenatedEcho.statusCode(), trailingEcho.statusCode(),
          malformed.statusCode(), empty.statusCode(), concatenatedOpen.statusCode(),
          trailingOpen.statusCode(), validAfter.statusCode());

      assertAll("No trailing JSON, garbage or malformed body may be silently accepted",
          () -> assertEquals(200, validBefore.statusCode(), validBefore.body()),
          () -> assertEquals(400, concatenatedEcho.statusCode(), concatenatedEcho.body()),
          () -> assertEquals(400, trailingEcho.statusCode(), trailingEcho.body()),
          () -> assertEquals(400, malformed.statusCode(), malformed.body()),
          () -> assertEquals(400, empty.statusCode(), empty.body()),
          () -> assertEquals(400, concatenatedOpen.statusCode(), concatenatedOpen.body()),
          () -> assertEquals(400, trailingOpen.statusCode(), trailingOpen.body()),
          () -> assertEquals(200, validAfter.statusCode(), validAfter.body()));
    } finally {
      server.shutdownNow();
    }
  }

  private static HttpResponse<String> post(HttpClient client, URI uri, String body)
      throws Exception {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
