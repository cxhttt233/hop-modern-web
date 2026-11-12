/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Explicit integration guard: the untrusted standalone staging editor has no
 * authenticated WebUserSession and MUST NOT expose pipeline or workflow
 * execution operations by accidentally importing the legacy preview resources.
 *
 * When an authenticated execution API is integrated, replace these 404
 * expectations with real 401/403/CSRF and cross-principal HTTP negatives.
 * Never remove the gate merely to make an unsafe endpoint available.
 */
class StandaloneExecutionSecurityAdmissionTest {
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void anonymousAndCrossOriginFormsCannotStartOrStopExecutionOnProductionListener()
      throws Exception {
    // Bind directly to the provisional production Grizzly stack on an ephemeral
    // loopback port. This test intentionally uses the same ResourceConfig as main().
    HttpServer server = GrizzlyHttpServerFactory.createHttpServer(
        URI.create("http://127.0.0.1:0/"), ModernWebServer.createResourceConfig(), false);
    server.start();
    int port = server.getListeners().iterator().next().getPort();
    URI base = URI.create("http://127.0.0.1:" + port + "/");
    try {
      HttpClient client = HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();

      // A bodyless cross-site HTML form is sufficient to attack a predictable
      // document ID if the old unauthenticated execution resource is registered.
      for (String route : new String[] {
          "api/executions/pipelines/known-document",
          "api/executions/known-execution/cancel",
          "api/v2/documents/known-workflow/executions",
          "api/v2/executions/known-execution/stop"
      }) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(route))
            .timeout(Duration.ofSeconds(8))
            .header("Origin", "https://attacker.invalid")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode(),
            "Unauthenticated standalone server must not expose " + route);
      }

      for (String route : new String[] {
          "api/executions/known-execution",
          "api/v2/executions/known-execution"
      }) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(route))
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build();
        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode(),
            "Standalone server must not expose anonymous execution history " + route);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
