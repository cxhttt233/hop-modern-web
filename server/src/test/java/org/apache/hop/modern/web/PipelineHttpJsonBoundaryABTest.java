/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.modern.web.document.PipelineOpenResource;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Same-listener, same-client, same-JSON A/B of Jersey record binding and actual pipeline open.
 * The diagnostic echo exists only in this test and is never registered in production.
 * Both endpoints must accept the same valid request; this is not an HTTP transport workaround.
 */
class PipelineHttpJsonBoundaryABTest {
  @TempDir java.nio.file.Path tempDir;

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void distinguishesJsonEntityBindingFromPipelineOpenBusinessLogic() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
    java.nio.file.Path fixture = tempDir.resolve("boundary-ab.hpl");
    Files.copy(java.nio.file.Path.of("../e2e/fixtures/v4-table-input-proof.hpl"), fixture);

    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    URI base = URI.create("http://127.0.0.1:" + port + "/");
    HttpServer server = GrizzlyHttpServerFactory.createHttpServer(
        base, ModernWebServer.createResourceConfig().register(JsonEchoResource.class));
    try {
      HttpClient client = HttpClient.newHttpClient();
      String body = new ObjectMapper().writeValueAsString(Map.of("path", fixture.toString()));
      HttpResponse<String> echo = post(client, base.resolve("api/diagnostic/echo"), body);
      HttpResponse<String> opened = post(client, base.resolve("api/pipelines/open"), body);
      System.out.printf(
          "SAME_SERVER_JSON_AB echo=%d open=%d payloadBytes=%d echoType=%s openType=%s%n",
          echo.statusCode(), opened.statusCode(), body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
          echo.headers().firstValue("Content-Type").orElse("missing"),
          opened.headers().firstValue("Content-Type").orElse("missing"));
      assertAll("Both handlers must accept identical JSON on one Grizzly/Jersey listener",
          () -> assertEquals(200, echo.statusCode(), "record echo: " + echo.body()),
          () -> assertEquals(200, opened.statusCode(), "pipeline open: " + opened.body()));
    } finally {
      server.shutdownNow();
    }
  }

  private static HttpResponse<String> post(HttpClient client, URI uri, String body)
      throws Exception {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Path("/api/diagnostic")
  public static class JsonEchoResource {
    @POST
    @Path("/echo")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response echo(PipelineOpenResource.OpenRequest request) {
      return request == null || request.path() == null
          ? Response.status(400).build()
          : Response.ok(request).build();
    }
  }
}
