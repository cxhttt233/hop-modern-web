/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Jersey/Grizzly transport contract for the authoritative in-memory config editor. */
class PipelineConfigHttpIntegrationTest {
  @TempDir Path tempDir;

  @Test
  void validatesWriteAtHttpBoundaryWithoutLosingCommittedOrDiskState() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
    Path fixture = tempDir.resolve("transport-config.hpl");
    Files.copy(Path.of("../e2e/fixtures/v4-table-input-proof.hpl"), fixture);
    String originalDisk = Files.readString(fixture);

    int port;
    try (ServerSocket socket =
        new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    URI base = URI.create("http://127.0.0.1:" + port + "/");
    HttpServer server =
        GrizzlyHttpServerFactory.createHttpServer(base, ModernWebServer.createResourceConfig());
    try {
      HttpClient client = HttpClient.newHttpClient();
      ObjectMapper json = new ObjectMapper();
      ObjectNode open = json.createObjectNode().put("path", fixture.toString());
      HttpResponse<String> opened = post(client, base.resolve("api/pipelines/open"), json, open);
      assertEquals(200, opened.statusCode(), opened.body());
      String id = json.readTree(opened.body()).path("id").asText();
      assertFalse(id.isBlank(), opened.body());

      URI readUrl = base.resolve("api/pipelines/" + id + "/config/read");
      URI writeUrl = base.resolve("api/pipelines/" + id + "/config/write");
      ObjectNode readRequest = json.createObjectNode().put("nodeId", "table-input");
      HttpResponse<String> initial = post(client, readUrl, json, readRequest);
      assertEquals(200, initial.statusCode(), initial.body());
      ObjectNode config = ((ObjectNode) json.readTree(initial.body()).path("config")).deepCopy();
      assertEquals("SELECT id, name FROM customers", config.path("sql").asText());

      config.put("sql", "SELECT id FROM rivers");
      ObjectNode valid = json.createObjectNode().put("nodeId", "table-input");
      valid.set("config", config);
      HttpResponse<String> written = post(client, writeUrl, json, valid);
      assertEquals(200, written.statusCode(), written.body());
      assertEquals("SELECT id FROM rivers",
          json.readTree(written.body()).path("config").path("sql").asText());

      ObjectNode malformed = json.createObjectNode().put("nodeId", "table-input");
      malformed.putArray("config");
      HttpResponse<String> rejected = post(client, writeUrl, json, malformed);
      assertEquals(400, rejected.statusCode(), rejected.body());
      assertEquals("invalid_request", json.readTree(rejected.body()).path("code").asText());

      ObjectNode missingNode = json.createObjectNode().put("nodeId", "missing");
      missingNode.set("config", config);
      HttpResponse<String> unknown = post(client, writeUrl, json, missingNode);
      assertEquals(422, unknown.statusCode(), unknown.body());
      assertEquals("unknown_transform", json.readTree(unknown.body()).path("code").asText());

      HttpResponse<String> after = post(client, readUrl, json, readRequest);
      assertEquals(200, after.statusCode(), after.body());
      JsonNode afterConfig = json.readTree(after.body()).path("config");
      assertEquals("SELECT id FROM rivers", afterConfig.path("sql").asText());
      assertEquals(originalDisk, Files.readString(fixture),
          "HTTP config editing must not silently save the .hpl file");
    } finally {
      server.shutdownNow();
    }
  }

  private static HttpResponse<String> post(
      HttpClient client, URI uri, ObjectMapper json, JsonNode body) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
