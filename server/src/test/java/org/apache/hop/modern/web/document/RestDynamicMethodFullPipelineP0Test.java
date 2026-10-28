package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.json.ConfigJsonSerializer;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engines.local.LocalPipelineEngine;
import org.apache.hop.pipeline.transform.ITransform;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.pipeline.transform.RowAdapter;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production REST per-row dynamic method and URL acceptance after config JSON and HPL disk reopen.
 * Proves that the configured static POST enables bodyField binding, while a dynamic GET sends no
 * entity and a dynamic POST sends the input row body. HTTP stays on isolated 127.0.0.1.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class RestDynamicMethodFullPipelineP0Test {
  record RequestSeen(String path, String method, String body) {}

  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void restDynamicGetPostUsesPerRowUrlAndPostBodyAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    List<RequestSeen> received = new CopyOnWriteArrayList<>();
    AtomicInteger literalRequests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/station/", exchange -> {
      try (exchange) {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new RequestSeen(path, method, body));
        String response = path.endsWith("/read") ? "native-rest-黄河" : "native-rest-长江";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.createContext("/literal-unused", exchange -> {
      try (exchange) {
        literalRequests.incrementAndGet();
        byte[] bytes = "wrong-url".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      String postBody = "{\"station\":\"长江<&>\",\"flow\":42}";
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
      IPlugin restPlugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(gridPlugin, "pinned DataGrid plugin required");
      assertNotNull(restPlugin, "pinned REST plugin required");

      ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
      grid.setDefault();
      ClassLoader gridLoader = grid.getClass().getClassLoader();
      Class<?> fieldType = gridLoader.loadClass(
          "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
      Class<?> lineType = gridLoader.loadClass(
          "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
      List<Object> fields = new ArrayList<>();
      for (String name : List.of("请求地址<&>", "请求方法_雪", "请求体", "批次")) {
        Object field = fieldType.getConstructor().newInstance();
        fieldType.getMethod("setName", String.class).invoke(field, name);
        fieldType.getMethod("setType", String.class).invoke(field, "String");
        fields.add(field);
      }
      grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, fields);
      List<Object> lines = new ArrayList<>();
      for (List<String> values : List.of(
          List.of(base + "/station/read", "GET", "GET-SHOULD-NOT-BE-SENT", "批次一"),
          List.of(base + "/station/write", "POST", postBody, "批次二"))) {
        Object line = lineType.getConstructor().newInstance();
        lineType.getMethod("setDatalines", List.class).invoke(line, values);
        lines.add(line);
      }
      grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

      ITransformMeta rest = plugins.loadClass(restPlugin, ITransformMeta.class);
      rest.setDefault();
      TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
      TransformMeta target = new TransformMeta("Rest", "rest-node", rest);
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-native-dynamic-get-post");
      pipeline.addTransform(source);
      pipeline.addTransform(target);
      pipeline.addPipelineHop(new PipelineHopMeta(source, target));

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("rest-dynamic-method-full-pipeline.hpl");
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      assertEquals(1, loaded.nrPipelineHops());

      var registry = new PipelineDocumentRegistry();
      registry.put(new PipelineDocument("rest-dynamic-doc", hpl, loaded));
      var resource = new PipelineConfigResource(registry, provider);
      var read = resource.read("rest-dynamic-doc",
          new PipelineConfigResource.ReadRequest("rest-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("method", "POST");
      draft.put("dynamicMethod", true);
      draft.put("methodFieldName", "请求方法_雪");
      draft.put("urlInField", true);
      draft.put("urlField", "请求地址<&>");
      draft.put("url", base + "/literal-unused");
      draft.put("bodyField", "请求体");
      draft.put("applicationType", "JSON");
      draft.put("streamingEnabled", false);
      ObjectNode result = (ObjectNode) draft.path("result");
      assertTrue(result.isObject());
      result.put("name", "响应正文");
      result.put("code", "HTTP状态");
      result.put("response_header", "");
      result.put("response_time", "");
      result.put("binary", false);
      var write = resource.write("rest-dynamic-doc",
          new PipelineConfigResource.WriteRequest("rest-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      assertEquals(2, reopened.nrTransforms());
      assertEquals(1, reopened.nrPipelineHops());
      JsonNode wire = ConfigJsonSerializer.toJson(
          reopened.findTransform("rest-node").getTransform(), provider);
      assertEquals("POST", wire.at("/method").asText());
      assertTrue(wire.at("/dynamicMethod").isBoolean());
      assertTrue(wire.at("/dynamicMethod").booleanValue());
      assertEquals("请求方法_雪", wire.at("/methodFieldName").asText());
      assertTrue(wire.at("/urlInField").booleanValue());
      assertEquals("请求地址<&>", wire.at("/urlField").asText());
      assertEquals(base + "/literal-unused", wire.at("/url").asText());
      assertEquals("请求体", wire.at("/bodyField").asText());
      assertEquals("JSON", wire.at("/applicationType").asText());
      assertEquals("响应正文", wire.at("/result/name").asText());
      assertEquals("HTTP状态", wire.at("/result/code").asText());

      var contract = AutomaticConfigDescriptor.describe(
          reopened.findTransform("rest-node").getTransform().getClass())
          .runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "REST_DYNAMIC_GET_POST_BODY_REQUIRES_STATIC_BODY_CAPABLE_METHOD"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/dynamicMethod", "/methodFieldName", "/method", "/bodyField",
          "/urlInField", "/urlField"), contract.keyListPointers());

      List<Object[]> outputRows = new CopyOnWriteArrayList<>();
      List<IRowMeta> outputMetas = new CopyOnWriteArrayList<>();
      LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
      engine.setMetadataProvider(provider);
      try {
        engine.prepareExecution();
        ITransform output = engine.getRunThread("rest-node", 0);
        assertNotNull(output, "native REST engine must prepare");
        output.addRowListener(new RowAdapter() {
          @Override
          public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
            outputMetas.add(rowMeta.clone());
            outputRows.add(row.clone());
          }
        });
        engine.startThreads();
        engine.waitUntilFinished();
        assertEquals(0, engine.getErrors(), "native REST pipeline must finish");
      } finally {
        if (!engine.isFinishedOrStopped()) engine.stopAll();
        engine.cleanup();
      }

      assertEquals(2, outputRows.size());
      assertEquals(2, outputMetas.size());
      assertEquals(List.of("请求地址<&>", "请求方法_雪", "请求体", "批次",
              "响应正文", "HTTP状态"),
          outputMetas.get(0).getValueMetaList().stream().map(IValueMeta::getName).toList());
      assertEquals(IValueMeta.TYPE_STRING, outputMetas.get(0).getValueMeta(4).getType());
      assertEquals(IValueMeta.TYPE_INTEGER, outputMetas.get(0).getValueMeta(5).getType());
      for (Object[] row : outputRows) {
        assertEquals(200L, row[5]);
        if ("GET".equals(row[1])) {
          assertEquals("批次一", row[3]);
          assertEquals("native-rest-黄河", row[4]);
        } else {
          assertEquals("POST", row[1]);
          assertEquals("批次二", row[3]);
          assertEquals("native-rest-长江", row[4]);
        }
      }
      assertEquals(2, received.size());
      assertTrue(received.contains(new RequestSeen("/station/read", "GET", "")),
          "dynamic GET must not transmit configured body field");
      assertTrue(received.contains(new RequestSeen("/station/write", "POST", postBody)),
          "dynamic POST must transmit its own UTF-8 row body");
      assertEquals(0, literalRequests.get(),
          "urlInField=true must bypass configured literal URL");
      System.out.println("PROD_P0_REST_DYNAMIC_GET_POST_BODY_FULL_PIPELINE_JSON_HPL_ROWS=PASS");
    } finally {
      server.stop(0);
    }
  }

  /**
   * Unlike the seven well-known verbs, native REST accepts RFC 9110 custom method tokens
   * without normalizing their case. Prove the wire request method and UTF-8 request entity
   * after T3 JSON editing and a real disk HPL reopen, not merely the method normalizer.
   * All network traffic is restricted to the local test fixture.
   */
  @Test
  void restCustomMethodsKeepExactCaseAndUtf8BodyAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    List<RequestSeen> seen = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/custom-methods", exchange -> {
      try (exchange) {
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8);
        seen.add(new RequestSeen(exchange.getRequestURI().getPath(), method, body));
        byte[] reply = ("accepted:" + method).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, reply.length);
        exchange.getResponseBody().write(reply);
      }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort()
          + "/custom-methods";
      String firstBody = "{\"station\":\"黄河<&>\"}";
      String secondBody = "{\"station\":\"长江_雪\"}";
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
      IPlugin restPlugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(gridPlugin);
      assertNotNull(restPlugin);

      ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
      grid.setDefault();
      ClassLoader loader = grid.getClass().getClassLoader();
      Class<?> fieldType = loader.loadClass(
          "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
      Class<?> lineType = loader.loadClass(
          "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
      List<Object> fields = new ArrayList<>();
      for (String name : List.of("自定义方法", "请求体")) {
        Object field = fieldType.getConstructor().newInstance();
        fieldType.getMethod("setName", String.class).invoke(field, name);
        fieldType.getMethod("setType", String.class).invoke(field, "String");
        fields.add(field);
      }
      grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, fields);
      List<Object> lines = new ArrayList<>();
      for (List<String> values : List.of(
          List.of("pUrGe", firstBody),
          List.of("M-SEARCH", secondBody))) {
        Object line = lineType.getConstructor().newInstance();
        lineType.getMethod("setDatalines", List.class).invoke(line, values);
        lines.add(line);
      }
      grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

      ITransformMeta rest = plugins.loadClass(restPlugin, ITransformMeta.class);
      rest.setDefault();
      TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
      TransformMeta target = new TransformMeta("Rest", "rest-node", rest);
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-custom-methods-exact-case");
      pipeline.addTransform(source);
      pipeline.addTransform(target);
      pipeline.addPipelineHop(new PipelineHopMeta(source, target));

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("rest-custom-methods.hpl");
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-custom-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-custom-doc",
          new PipelineConfigResource.ReadRequest("rest-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("url", url);
      draft.put("method", "POST");
      draft.put("dynamicMethod", true);
      draft.put("methodFieldName", "自定义方法");
      draft.put("bodyField", "请求体");
      draft.put("applicationType", "JSON");
      draft.put("streamingEnabled", false);
      ObjectNode result = (ObjectNode) draft.path("result");
      result.put("name", "响应正文");
      result.put("code", "HTTP状态");
      result.put("response_header", "");
      result.put("response_time", "");
      result.put("binary", false);
      var write = resource.write("rest-custom-doc",
          new PipelineConfigResource.WriteRequest("rest-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      assertEquals(2, reopened.nrTransforms());
      assertEquals(1, reopened.nrPipelineHops());
      JsonNode wire = ConfigJsonSerializer.toJson(
          reopened.findTransform("rest-node").getTransform(), provider);
      assertEquals("POST", wire.at("/method").asText());
      assertTrue(wire.at("/dynamicMethod").booleanValue());
      assertEquals("自定义方法", wire.at("/methodFieldName").asText());
      assertEquals("请求体", wire.at("/bodyField").asText());
      assertEquals("JSON", wire.at("/applicationType").asText());
      assertEquals("响应正文", wire.at("/result/name").asText());
      var contract = AutomaticConfigDescriptor.describe(
          reopened.findTransform("rest-node").getTransform().getClass())
          .runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "REST_CUSTOM_METHOD_TOKENS_PRESERVE_CASE_AND_BODY_RULES"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/method", "/dynamicMethod", "/methodFieldName"),
          contract.keyListPointers());

      List<Object[]> rows = new CopyOnWriteArrayList<>();
      List<IRowMeta> metas = new CopyOnWriteArrayList<>();
      LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
      engine.setMetadataProvider(provider);
      try {
        engine.prepareExecution();
        ITransform output = engine.getRunThread("rest-node", 0);
        assertNotNull(output);
        output.addRowListener(new RowAdapter() {
          @Override
          public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
            metas.add(rowMeta.clone());
            rows.add(row.clone());
          }
        });
        engine.startThreads();
        engine.waitUntilFinished();
        assertEquals(0, engine.getErrors());
      } finally {
        if (!engine.isFinishedOrStopped()) engine.stopAll();
        engine.cleanup();
      }
      assertEquals(2, rows.size());
      assertEquals(2, metas.size());
      assertEquals(List.of("自定义方法", "请求体", "响应正文", "HTTP状态"),
          metas.get(0).getValueMetaList().stream().map(IValueMeta::getName).toList());
      assertEquals(IValueMeta.TYPE_STRING, metas.get(0).getValueMeta(2).getType());
      assertEquals(IValueMeta.TYPE_INTEGER, metas.get(0).getValueMeta(3).getType());
      for (Object[] row : rows) {
        String method = (String) row[0];
        assertTrue(method.equals("pUrGe") || method.equals("M-SEARCH"));
        assertEquals("accepted:" + method, row[2]);
        assertEquals(200L, row[3]);
      }
      assertEquals(2, seen.size());
      assertTrue(seen.contains(new RequestSeen("/custom-methods", "pUrGe", firstBody)));
      assertTrue(seen.contains(new RequestSeen("/custom-methods", "M-SEARCH", secondBody)));
      System.out.println("PROD_P0_REST_CUSTOM_VERB_EXACT_CASE_UTF8_BODY_JSON_HPL_ROWS=PASS");
    } finally {
      server.stop(0);
    }
  }


  /**
   * The persisted flag is inverted: false sends Basic on the first request;
   * true waits for a 401 challenge. Verify loopback HTTP requests after a
   * config API edit and a real HPL disk reopen, not only the metadata getter.
   */
  @Test
  void restBasicAuthPreemptiveAndChallengeModesSendDifferentFirstRequestsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    String expectedAuthorization = "Basic " + java.util.Base64.getEncoder().encodeToString(
        "fixture-user:fixture-password".getBytes(StandardCharsets.UTF_8));
    List<String> observed = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/basic/", exchange -> {
      try (exchange) {
        String mode = exchange.getRequestURI().getPath().endsWith("/challenge")
            ? "challenge" : "preemptive";
        boolean authorized = expectedAuthorization.equals(
            exchange.getRequestHeaders().getFirst("Authorization"));
        observed.add(mode + (authorized ? ":authorized" : ":anonymous"));
        if (!authorized) {
          exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"isolated\"");
          exchange.sendResponseHeaders(401, -1);
        } else {
          byte[] body = ("authenticated-" + mode).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        }
      }
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
      IPlugin restPlugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(gridPlugin);
      assertNotNull(restPlugin);

      for (boolean nonPreemptive : List.of(false, true)) {
        String mode = nonPreemptive ? "challenge" : "preemptive";
        ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
        grid.setDefault();
        ClassLoader loader = grid.getClass().getClassLoader();
        Class<?> fieldType = loader.loadClass(
            "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
        Class<?> lineType = loader.loadClass(
            "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
        Object field = fieldType.getConstructor().newInstance();
        fieldType.getMethod("setName", String.class).invoke(field, "批次<&>");
        fieldType.getMethod("setType", String.class).invoke(field, "String");
        grid.getClass().getMethod("setDataGridFields", List.class)
            .invoke(grid, List.of(field));
        Object line = lineType.getConstructor().newInstance();
        lineType.getMethod("setDatalines", List.class).invoke(line, List.of(mode));
        grid.getClass().getMethod("setDataLines", List.class)
            .invoke(grid, List.of(line));

        ITransformMeta rest = plugins.loadClass(restPlugin, ITransformMeta.class);
        rest.setDefault();
        rest.getClass().getMethod("setHttpLogin", String.class)
            .invoke(rest, "fixture-user");
        rest.getClass().getMethod("setHttpPassword", String.class)
            .invoke(rest, "fixture-password");
        TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
        TransformMeta target = new TransformMeta("Rest", "rest-node", rest);
        PipelineMeta pipeline = new PipelineMeta();
        pipeline.setName("rest-basic-" + mode);
        pipeline.addTransform(source);
        pipeline.addTransform(target);
        pipeline.addPipelineHop(new PipelineHopMeta(source, target));

        var provider = new MemoryMetadataProvider();
        var store = new PipelineDocumentStore(provider, new Variables());
        Path hpl = tempDir.resolve("rest-basic-" + mode + ".hpl");
        store.save(hpl, pipeline);
        PipelineMeta loaded = store.open(hpl);
        var registry = new PipelineDocumentRegistry();
        String documentId = "rest-basic-" + mode + "-doc";
        registry.put(new PipelineDocument(documentId, hpl, loaded));
        var resource = new PipelineConfigResource(registry, provider);
        var read = resource.read(documentId,
            new PipelineConfigResource.ReadRequest("rest-node"));
        assertEquals(200, read.getStatus());
        ObjectNode draft = ((ObjectNode)
            ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
        draft.put("url", base + "/basic/" + mode);
        draft.put("method", "GET");
        draft.put("non_preemptive_basic_auth", nonPreemptive);
        ObjectNode result = (ObjectNode) draft.path("result");
        result.put("name", "响应正文");
        result.put("code", "HTTP状态");
        result.put("response_header", "");
        result.put("response_time", "");
        result.put("binary", false);
        var write = resource.write(documentId,
            new PipelineConfigResource.WriteRequest("rest-node", draft));
        assertEquals(200, write.getStatus());
        store.save(hpl, loaded);

        PipelineMeta reopened = store.open(hpl);
        assertEquals(1, reopened.nrPipelineHops());
        ITransformMeta diskMeta = reopened.findTransform("rest-node").getTransform();
        JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
        assertTrue(wire.at("/non_preemptive_basic_auth").isBoolean());
        assertEquals(nonPreemptive, wire.at("/non_preemptive_basic_auth").booleanValue());
        assertEquals(!nonPreemptive, (Boolean)
            diskMeta.getClass().getMethod("isPreemptive").invoke(diskMeta));
        assertEquals(base + "/basic/" + mode, wire.at("/url").asText());
        var contract = AutomaticConfigDescriptor.describe(diskMeta.getClass())
            .runtimePreconditions().stream()
            .filter(p -> p.code().equals("REST_BASIC_AUTH_PREEMPTIVE_FLAG_IS_INVERTED"))
            .findFirst().orElseThrow();
        assertEquals(List.of("/non_preemptive_basic_auth"), contract.keyListPointers());

        List<Object[]> rows = new CopyOnWriteArrayList<>();
        List<IRowMeta> metas = new CopyOnWriteArrayList<>();
        LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
        engine.setMetadataProvider(provider);
        try {
          engine.prepareExecution();
          ITransform output = engine.getRunThread("rest-node", 0);
          assertNotNull(output);
          output.addRowListener(new RowAdapter() {
            @Override
            public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
              metas.add(rowMeta.clone());
              rows.add(row.clone());
            }
          });
          engine.startThreads();
          engine.waitUntilFinished();
          assertEquals(0, engine.getErrors());
        } finally {
          if (!engine.isFinishedOrStopped()) engine.stopAll();
          engine.cleanup();
        }
        assertEquals(1, rows.size(), "one source row must produce one authenticated result");
        assertEquals(List.of("批次<&>", "响应正文", "HTTP状态"),
            metas.get(0).getValueMetaList().stream().map(IValueMeta::getName).toList());
        assertEquals(IValueMeta.TYPE_STRING, metas.get(0).getValueMeta(1).getType());
        assertEquals(IValueMeta.TYPE_INTEGER, metas.get(0).getValueMeta(2).getType());
        assertEquals(mode, rows.get(0)[0]);
        assertEquals("authenticated-" + mode, rows.get(0)[1]);
        assertEquals(200L, rows.get(0)[2]);
      }
      assertEquals(List.of(
          "preemptive:authorized",
          "challenge:anonymous",
          "challenge:authorized"), observed,
          "the non-preemptive mode must wait for 401 before transmitting credentials");
      System.out.println("PROD_P0_REST_BASIC_AUTH_FIRST_REQUEST_CHALLENGE_JSON_HPL_ROWS=PASS");
    } finally {
      server.stop(0);
    }
  }
}
