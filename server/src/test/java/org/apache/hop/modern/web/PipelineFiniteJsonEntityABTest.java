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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.hop.modern.web.document.PipelineOpenResource;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Same-listener A/B: Jersey record binding versus finite byte[] entity binding.
 * This test-only endpoint does not modify production resources or HTTP framing.
 * A bounded byte[] allows Jackson to check trailing tokens without reading the
 * Grizzly socket for EOF. Whether Jersey supplies that finite body is the test.
 */
class PipelineFiniteJsonEntityABTest {

  @Test
  @Timeout(value = 110, unit = TimeUnit.SECONDS)
  void probesStrictParsingOfFiniteJsonEntityOnActualServerStack() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    URI base = URI.create("http://127.0.0.1:" + port + "/");
    HttpServer server = GrizzlyHttpServerFactory.createHttpServer(
        base, ModernWebServer.createResourceConfig()
            .register(PipelineHttpJsonBoundaryABTest.JsonEchoResource.class)
            .register(FiniteJsonProbeResource.class));
    try {
      HttpClient client = HttpClient.newHttpClient();
      URI raw = base.resolve("api/diagnostic/finite-json");
      URI record = base.resolve("api/diagnostic/echo");
      String valid = "{\"path\":\"/definitely-not-present/finite-json.hpl\"}";
      String concat = valid + "{\"path\":\"/another.hpl\"}";
      String garbage = valid + " NOT_JSON";
      String duplicate = "{\"path\":\"/first.hpl\",\"path\":\"/second.hpl\"}";

      HttpResponse<String> recordValid = post(client, record, valid);
      HttpResponse<String> recordConcat = post(client, record, concat);
      HttpResponse<String> rawValid = post(client, raw, valid);
      HttpResponse<String> rawConcat = post(client, raw, concat);
      HttpResponse<String> rawGarbage = post(client, raw, garbage);
      HttpResponse<String> rawDuplicate = post(client, raw, duplicate);
      HttpResponse<String> rawMalformed = post(client, raw, "{\"path\":");
      HttpResponse<String> rawEmpty = post(client, raw, "");
      HttpResponse<String> rawAfter = post(client, raw, valid);

      System.out.printf(
          "SAME_SERVER_FINITE_JSON_AB recordValid=%d recordConcat=%d rawValid=%d"
              + " concat=%d garbage=%d duplicate=%d malformed=%d empty=%d after=%d%n",
          recordValid.statusCode(), recordConcat.statusCode(), rawValid.statusCode(),
          rawConcat.statusCode(), rawGarbage.statusCode(), rawDuplicate.statusCode(),
          rawMalformed.statusCode(), rawEmpty.statusCode(), rawAfter.statusCode());

      assertAll("Finite JSON entity must parse strictly and preserve subsequent requests",
          () -> assertEquals(200, recordValid.statusCode(), recordValid.body()),
          () -> assertEquals(200, rawValid.statusCode(), rawValid.body()),
          () -> assertEquals(400, rawConcat.statusCode(), rawConcat.body()),
          () -> assertEquals(400, rawGarbage.statusCode(), rawGarbage.body()),
          () -> assertEquals(400, rawDuplicate.statusCode(), rawDuplicate.body()),
          () -> assertEquals(400, rawMalformed.statusCode(), rawMalformed.body()),
          () -> assertEquals(400, rawEmpty.statusCode(), rawEmpty.body()),
          () -> assertEquals(200, rawAfter.statusCode(), rawAfter.body()));
    } finally {
      server.shutdownNow();
    }
  }

  private static HttpResponse<String> post(HttpClient client, URI uri, String body)
      throws Exception {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(8))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Path("/api/diagnostic")
  public static final class FiniteJsonProbeResource {
    private static final ObjectMapper STRICT = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    @POST
    @Path("/finite-json")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response parse(byte[] entity) {
      if (entity == null || entity.length == 0) {
        return Response.status(400).entity("empty_json").build();
      }
      try {
        PipelineOpenResource.OpenRequest request =
            STRICT.readValue(entity, PipelineOpenResource.OpenRequest.class);
        return request == null || request.path() == null
            ? Response.status(400).entity("invalid_json").build()
            : Response.ok("accepted").build();
      } catch (java.io.IOException invalid) {
        return Response.status(400).entity("invalid_json").build();
      }
    }
  }
}
