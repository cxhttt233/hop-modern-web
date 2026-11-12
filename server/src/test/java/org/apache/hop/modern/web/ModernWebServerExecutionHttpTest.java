/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.hop.core.database.Database;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.database.DatabasePluginType;
import org.apache.hop.core.encryption.HopTwoWayPasswordEncoder;
import org.apache.hop.core.logging.LoggingObject;
import org.apache.hop.databases.h2.H2DatabaseMeta;
import org.apache.hop.metadata.serializer.json.JsonMetadataProvider;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.tableinput.TableInputMeta;
import java.time.Duration;
import java.time.Instant;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.modern.web.document.PipelineDocumentStore;
import org.apache.hop.pipeline.PipelineMeta;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModernWebServerExecutionHttpTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  @TempDir Path tempDir;
  private HttpServer server;

  @BeforeAll
  static void initializeHop() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.shutdownNow();
    }
  }

  @Test
  void opensStartsAndQueriesRealPipelineLifecycleOverHttp() throws Exception {
    Path pipelineFile = tempDir.resolve("execution-proof.hpl");
    PipelineDocumentStore writer =
        new PipelineDocumentStore(
            mock(IHopMetadataProvider.class), Variables.getADefaultVariableSpace());
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("execution-proof");
    writer.save(pipelineFile, pipeline);

    URI base = startServer();

    HttpResponse<String> opened =
        postJson(base.resolve("api/pipelines/open"), JSON.writeValueAsString(new OpenRequest(pipelineFile.toString())));
    assertEquals(200, opened.statusCode());
    String documentId = JSON.readTree(opened.body()).path("id").asText();
    assertTrue(!documentId.isBlank());

    HttpResponse<String> started =
        postNoBody(base.resolve("api/executions/pipelines/" + documentId));
    assertEquals(
        202,
        started.statusCode(),
        () ->
            "execution start HTTP diagnostic: body="
                + started.body()
                + ", headers="
                + started.headers().map());
    JsonNode startBody = JSON.readTree(started.body());
    String executionId = startBody.path("id").asText();
    assertTrue(!executionId.isBlank());
    assertEquals(documentId, startBody.path("documentId").asText());
    assertNotNull(startBody.get("start"));
    assertDoesNotThrow(() -> Instant.parse(startBody.path("start").asText()));

    JsonNode terminal = awaitTerminal(base, executionId);
    assertEquals(documentId, terminal.path("documentId").asText());
    assertTrue(
        terminal.path("state").asText().equals("completed")
            || terminal.path("state").asText().equals("failed")
            || terminal.path("state").asText().equals("stopped"));
    assertNotNull(terminal.get("start"));
    assertNotNull(terminal.get("end"));
    assertDoesNotThrow(() -> Instant.parse(terminal.path("start").asText()));
    assertDoesNotThrow(() -> Instant.parse(terminal.path("end").asText()));
    assertTrue(terminal.path("durationMillis").asLong(-1) >= 0);
  }

  @Test
  void executesTableInputWithNativePersistedProjectMetadataOverHttp() throws Exception {
    // The metadata path is selected via ModernWebServer production wiring, not
    // through the manually injected MemoryMetadataProvider used by engine-only tests.
    DatabasePluginType.getInstance().registerClassPathPlugin(H2DatabaseMeta.class);
    var variables = new Variables();
    Path metadataDir = Files.createDirectory(tempDir.resolve("project-metadata"));
    var persisted = new JsonMetadataProvider(
        new HopTwoWayPasswordEncoder(), metadataDir.toString(), variables);
    var warehouse = new DatabaseMeta(
        "Warehouse", "H2", "Native", "", "mem:server-project-http;DB_CLOSE_DELAY=-1", "", "", "");
    persisted.getSerializer(DatabaseMeta.class).save(warehouse);

    try (Database db = new Database(new LoggingObject("project-http-proof"), variables, warehouse)) {
      db.connect();
      db.execStatement("CREATE TABLE PROJECT_HTTP_PROOF(ID INT PRIMARY KEY, NAME VARCHAR(32))");
      db.execStatement("INSERT INTO PROJECT_HTTP_PROOF VALUES(7, 'native-metadata')");
    }

    var input = new TableInputMeta();
    input.setConnection("Warehouse");
    input.setSql("SELECT ID, NAME FROM PROJECT_HTTP_PROOF");
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("project-http-proof");
    pipeline.addTransform(new TransformMeta("TableInput", "native-source", input));
    Path pipelineFile = tempDir.resolve("project-http-proof.hpl");
    new PipelineDocumentStore(persisted, variables).save(pipelineFile, pipeline);

    IHopMetadataProvider fromServerConfig =
        ModernWebServer.selectMetadataProvider(variables, metadataDir.toString());
    server = GrizzlyHttpServerFactory.createHttpServer(
        URI.create("http://127.0.0.1:0/"),
        ModernWebServer.createResourceConfig(variables, fromServerConfig), false);
    server.start();
    int port = server.getListeners().iterator().next().getPort();
    URI base = URI.create("http://127.0.0.1:" + port + "/");

    var opened = postJson(
        base.resolve("api/pipelines/open"),
        JSON.writeValueAsString(new OpenRequest(pipelineFile.toString())));
    assertEquals(200, opened.statusCode(), opened.body());
    String documentId = JSON.readTree(opened.body()).path("id").asText();
    assertTrue(!documentId.isBlank());

    var started = postNoBody(base.resolve("api/executions/pipelines/" + documentId));
    assertEquals(202, started.statusCode(), started.body());
    String executionId = JSON.readTree(started.body()).path("id").asText();
    assertTrue(!executionId.isBlank());
    JsonNode terminal = awaitTerminal(base, executionId);
    assertEquals("completed", terminal.path("state").asText(), terminal.toString());
    assertEquals(0, terminal.path("errors").asInt(-1), terminal.toString());
  }

  @Test
  void returnsReasonableErrorsForUnknownDocumentAndExecution() throws Exception {
    URI base = startServer();

    HttpResponse<String> missingDocument =
        postNoBody(base.resolve("api/executions/pipelines/missing-document"));
    assertEquals(
        404,
        missingDocument.statusCode(),
        () ->
            "missing-document start diagnostic: body="
                + missingDocument.body()
                + ", headers="
                + missingDocument.headers().map());
    assertEquals("document_not_found", JSON.readTree(missingDocument.body()).path("code").asText());

    HttpResponse<String> missingExecution =
        get(base.resolve("api/executions/missing-execution"));
    assertEquals(404, missingExecution.statusCode());
    assertEquals("execution_not_found", JSON.readTree(missingExecution.body()).path("code").asText());
  }

  @Test
  void rejectsTrailingJsonTokensOnPipelineOpenWithoutBreakingNextRequest() throws Exception {
    Path pipelineFile = tempDir.resolve("strict-json.hpl");
    PipelineDocumentStore writer =
        new PipelineDocumentStore(
            mock(IHopMetadataProvider.class), Variables.getADefaultVariableSpace());
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("strict-json");
    writer.save(pipelineFile, pipeline);

    URI endpoint = startServer().resolve("api/pipelines/open");
    String valid = JSON.writeValueAsString(new OpenRequest(pipelineFile.toString()));

    HttpResponse<String> before = postJson(endpoint, valid);
    HttpResponse<String> concatenated = postJson(endpoint, valid + valid);
    HttpResponse<String> garbage = postJson(endpoint, valid + " NOT_JSON");
    HttpResponse<String> after = postJson(endpoint, valid);

    assertEquals(200, before.statusCode(), before.body());
    assertEquals(400, concatenated.statusCode(), concatenated.body());
    assertEquals(400, garbage.statusCode(), garbage.body());
    assertEquals(200, after.statusCode(), after.body());
  }

  @Test
  void rejectsDuplicateJsonFieldsOnPipelineOpenWithoutBreakingNextRequest() throws Exception {
    Path pipelineFile = tempDir.resolve("duplicate-json.hpl");
    PipelineDocumentStore writer =
        new PipelineDocumentStore(
            mock(IHopMetadataProvider.class), Variables.getADefaultVariableSpace());
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("duplicate-json");
    writer.save(pipelineFile, pipeline);

    URI endpoint = startServer().resolve("api/pipelines/open");
    String quotedPath = JSON.writeValueAsString(pipelineFile.toString());
    String valid = "{\"path\":" + quotedPath + "}";
    String duplicate = "{\"path\":" + quotedPath + ",\"path\":" + quotedPath + "}";

    HttpResponse<String> before = postJson(endpoint, valid);
    HttpResponse<String> ambiguous = postJson(endpoint, duplicate);
    HttpResponse<String> after = postJson(endpoint, valid);

    assertEquals(200, before.statusCode(), before.body());
    assertEquals(400, ambiguous.statusCode(), ambiguous.body());
    assertEquals(200, after.statusCode(), after.body());
  }

  @Test
  void pipelineAndExecutionHttpResponsesCannotBeCached() throws Exception {
    Path pipelineFile = tempDir.resolve("no-store.hpl");
    PipelineDocumentStore writer =
        new PipelineDocumentStore(
            mock(IHopMetadataProvider.class), Variables.getADefaultVariableSpace());
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("no-store");
    writer.save(pipelineFile, pipeline);

    URI base = startServer();
    HttpResponse<String> opened =
        postJson(
            base.resolve("api/pipelines/open"),
            JSON.writeValueAsString(new OpenRequest(pipelineFile.toString())));
    assertEquals(200, opened.statusCode(), opened.body());
    assertEquals("no-store", opened.headers().firstValue("Cache-Control").orElse(null));

    String documentId = JSON.readTree(opened.body()).path("id").asText();
    HttpResponse<String> started =
        postNoBody(base.resolve("api/executions/pipelines/" + documentId));
    assertEquals(202, started.statusCode(), started.body());
    assertEquals("no-store", started.headers().firstValue("Cache-Control").orElse(null));

    String executionId = JSON.readTree(started.body()).path("id").asText();
    HttpResponse<String> status = get(base.resolve("api/executions/" + executionId));
    assertEquals(200, status.statusCode(), status.body());
    assertEquals("no-store", status.headers().firstValue("Cache-Control").orElse(null));

    HttpResponse<String> missing = get(base.resolve("api/executions/missing-execution"));
    assertEquals(404, missing.statusCode(), missing.body());
    assertEquals("no-store", missing.headers().firstValue("Cache-Control").orElse(null));
  }

  private URI startServer() throws Exception {
    server =
        GrizzlyHttpServerFactory.createHttpServer(
            URI.create("http://127.0.0.1:0/"), ModernWebServer.createResourceConfig(), false);
    server.start();
    int port = server.getListeners().iterator().next().getPort();
    return URI.create("http://127.0.0.1:" + port + "/");
  }

  private static JsonNode awaitTerminal(URI base, String executionId) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    JsonNode last = null;
    while (System.nanoTime() < deadline) {
      HttpResponse<String> response = get(base.resolve("api/executions/" + executionId));
      assertEquals(200, response.statusCode());
      last = JSON.readTree(response.body());
      String state = last.path("state").asText();
      if (state.equals("completed") || state.equals("failed") || state.equals("stopped")) {
        return last;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("execution did not reach a terminal Hop lifecycle state; last=" + last);
  }

  private static HttpResponse<String> postJson(URI uri, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> postNoBody(URI uri) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(URI uri) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private record OpenRequest(String path) {}
}
