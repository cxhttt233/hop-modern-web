package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * Pinned native HTTP runtime acceptance: a configured URL field must be resolved
 * for each input row after JSON editing and a real HPL disk reopen. This tests
 * complete Pipeline execution rather than invoking Http.callHttpService directly.
 * All HTTP traffic stays inside an isolated 127.0.0.1 fixture.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class HttpDynamicUrlFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void httpDynamicUrlFromEachRowOverridesLiteralInFullPipelineAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    AtomicInteger actualRequests = new AtomicInteger();
    AtomicInteger unusedLiteralRequests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/station/", exchange -> {
      try (exchange) {
        actualRequests.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        String station = path.endsWith("/huanghe") ? "黄河"
            : path.endsWith("/changjiang") ? "长江" : "unexpected";
        byte[] bytes = ("native-http-" + station).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.getResponseHeaders().add("X-Station", path.endsWith("/huanghe") ? "huanghe" : "changjiang");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.createContext("/should-not-be-used", exchange -> {
      try (exchange) {
        unusedLiteralRequests.incrementAndGet();
        byte[] bytes = "wrong-literal-url".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.start();
    try {
      String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
      IPlugin httpPlugin = plugins.findPluginWithId(TransformPluginType.class, "Http");
      assertNotNull(gridPlugin, "pinned production DataGrid plugin required");
      assertNotNull(httpPlugin, "pinned production Http plugin required");

      ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
      grid.setDefault();
      ClassLoader loader = grid.getClass().getClassLoader();
      Class<?> fieldType = loader.loadClass(
          "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
      Class<?> lineType = loader.loadClass(
          "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
      List<Object> fields = new ArrayList<>();
      for (String name : List.of("请求地址<&>", "批次")) {
        Object field = fieldType.getConstructor().newInstance();
        fieldType.getMethod("setName", String.class).invoke(field, name);
        fieldType.getMethod("setType", String.class).invoke(field, "String");
        fields.add(field);
      }
      grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, fields);
      List<Object> lines = new ArrayList<>();
      for (List<String> values : List.of(
          List.of(baseUrl + "/station/huanghe", "批次一"),
          List.of(baseUrl + "/station/changjiang", "批次二"))) {
        Object line = lineType.getConstructor().newInstance();
        lineType.getMethod("setDatalines", List.class).invoke(line, values);
        lines.add(line);
      }
      grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

      ITransformMeta http = plugins.loadClass(httpPlugin, ITransformMeta.class);
      http.setDefault();
      TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
      TransformMeta target = new TransformMeta("Http", "http-node", http);
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("http-native-dynamic-url-per-row");
      pipeline.addTransform(source);
      pipeline.addTransform(target);
      pipeline.addPipelineHop(new PipelineHopMeta(source, target));

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("http-dynamic-url-full-pipeline.hpl");
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      assertEquals(1, loaded.nrPipelineHops());

      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("http-dynamic-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("http-dynamic-doc",
          new PipelineConfigResource.ReadRequest("http-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("urlInField", true);
      draft.put("urlField", "请求地址<&>");
      draft.put("url", baseUrl + "/should-not-be-used");
      draft.put("encoding", "UTF-8");
      ObjectNode result = (ObjectNode) draft.path("result");
      result.put("name", "响应正文");
      result.put("code", "响应状态");
      result.put("response_header", "响应头");
      var write = resource.write("http-dynamic-doc",
          new PipelineConfigResource.WriteRequest("http-node", draft));
      assertEquals(200, write.getStatus(), "native dynamic URL config must be writable");
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      assertEquals(2, reopened.nrTransforms());
      assertEquals(1, reopened.nrPipelineHops());
      JsonNode wire = ConfigJsonSerializer.toJson(
          reopened.findTransform("http-node").getTransform(), provider);
      assertTrue(wire.at("/urlInField").isBoolean());
      assertTrue(wire.at("/urlInField").booleanValue());
      assertEquals("请求地址<&>", wire.at("/urlField").asText());
      assertEquals(baseUrl + "/should-not-be-used", wire.at("/url").asText());
      assertEquals("UTF-8", wire.at("/encoding").asText());
      assertEquals("响应正文", wire.at("/result/name").asText());
      assertEquals("响应状态", wire.at("/result/code").asText());
      assertEquals("响应头", wire.at("/result/response_header").asText());

      var contract = AutomaticConfigDescriptor.describe(
          reopened.findTransform("http-node").getTransform().getClass())
          .runtimePreconditions().stream()
          .filter(p -> p.code().equals("HTTP_DYNAMIC_URL_NATIVE_CHECK_FALSE_POSITIVE"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/urlInField", "/urlField", "/url"),
          contract.keyListPointers());

      List<Object[]> emitted = new CopyOnWriteArrayList<>();
      AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
      LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
      engine.setMetadataProvider(provider);
      try {
        engine.prepareExecution();
        ITransform output = engine.getRunThread("http-node", 0);
        assertNotNull(output, "native Http engine must prepare");
        output.addRowListener(new RowAdapter() {
          @Override
          public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
            outputMeta.set(rowMeta.clone());
            emitted.add(row.clone());
          }
        });
        engine.startThreads();
        engine.waitUntilFinished();
        assertEquals(0, engine.getErrors(), "native Http pipeline must finish");
      } finally {
        if (!engine.isFinishedOrStopped()) engine.stopAll();
        engine.cleanup();
      }

      assertNotNull(outputMeta.get(), "native Http must emit real result rows");
      assertEquals(List.of("请求地址<&>", "批次", "响应正文", "响应状态", "响应头"),
          outputMeta.get().getValueMetaList().stream().map(IValueMeta::getName).toList());
      assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(2).getType());
      assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(3).getType());
      assertEquals(2, emitted.size());
      ObjectMapper mapper = new ObjectMapper();
      for (int i = 0; i < 2; i++) {
        Object[] row = emitted.get(i);
        String station = i == 0 ? "黄河" : "长江";
        assertEquals(i == 0 ? "批次一" : "批次二", row[1]);
        assertEquals("native-http-" + station, row[2]);
        assertEquals(200L, row[3]);
        JsonNode headers = mapper.readTree((String) row[4]);
        boolean found = false;
        for (var names = headers.fieldNames(); names.hasNext();) {
          String key = names.next();
          if (key.equalsIgnoreCase("X-Station")) {
            assertEquals(i == 0 ? "huanghe" : "changjiang", headers.path(key).asText());
            found = true;
          }
        }
        assertTrue(found, "per-row HTTP response headers must be preserved");
      }
      assertEquals(2, actualRequests.get(), "both input rows must make distinct HTTP requests");
      assertEquals(0, unusedLiteralRequests.get(),
          "urlInField=true must override the configured literal URL");
      System.out.println("PROD_P0_HTTP_DYNAMIC_URL_FULL_PIPELINE_PER_ROW_JSON_HPL_ROWS=PASS");
    } finally {
      server.stop(0);
    }
  }

  /**
   * A single HTTP response header is a JSON STRING, but repeated header names
   * are an ordered JSON ARRAY. Prove both shapes on actual rows after a real
   * config API edit and HPL disk reopen, not only by calling the native helper.
   */
  @Test
  void httpRepeatedResponseHeadersKeepArrayAndSingleValueAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/headers/", exchange -> {
      try (exchange) {
        requests.incrementAndGet();
        boolean duplicate = exchange.getRequestURI().getPath().endsWith("/duplicate");
        exchange.getResponseHeaders().add("X-Test-Values", "first");
        if (duplicate) exchange.getResponseHeaders().add("X-Test-Values", "second");
        byte[] bytes = (duplicate ? "黄河双值" : "长江单值")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
      IPlugin httpPlugin = plugins.findPluginWithId(TransformPluginType.class, "Http");
      assertNotNull(gridPlugin);
      assertNotNull(httpPlugin);

      ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
      grid.setDefault();
      ClassLoader loader = grid.getClass().getClassLoader();
      Class<?> fieldType = loader.loadClass(
          "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
      Class<?> lineType = loader.loadClass(
          "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, "请求地址<&>");
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      grid.getClass().getMethod("setDataGridFields", List.class)
          .invoke(grid, List.of(field));
      List<Object> lines = new ArrayList<>();
      for (String suffix : List.of("single", "duplicate")) {
        Object line = lineType.getConstructor().newInstance();
        lineType.getMethod("setDatalines", List.class)
            .invoke(line, List.of(base + "/headers/" + suffix));
        lines.add(line);
      }
      grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

      ITransformMeta http = plugins.loadClass(httpPlugin, ITransformMeta.class);
      http.setDefault();
      TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
      TransformMeta target = new TransformMeta("Http", "http-node", http);
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("http-repeated-header-wire-shapes");
      pipeline.addTransform(source);
      pipeline.addTransform(target);
      pipeline.addPipelineHop(new PipelineHopMeta(source, target));

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("http-repeated-header-shapes.hpl");
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("http-headers-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("http-headers-doc",
          new PipelineConfigResource.ReadRequest("http-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("urlInField", true);
      draft.put("urlField", "请求地址<&>");
      draft.put("url", base + "/unused");
      draft.put("encoding", "UTF-8");
      ObjectNode result = (ObjectNode) draft.path("result");
      result.put("name", "响应正文");
      result.put("code", "响应状态");
      result.put("response_header", "响应头");
      result.put("response_time", "");
      var write = resource.write("http-headers-doc",
          new PipelineConfigResource.WriteRequest("http-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      assertEquals(2, reopened.nrTransforms());
      assertEquals(1, reopened.nrPipelineHops());
      JsonNode wire = ConfigJsonSerializer.toJson(
          reopened.findTransform("http-node").getTransform(), provider);
      assertTrue(wire.at("/urlInField").isBoolean());
      assertTrue(wire.at("/urlInField").booleanValue());
      assertEquals("请求地址<&>", wire.at("/urlField").asText());
      assertEquals("响应头", wire.at("/result/response_header").asText());
      var contract = AutomaticConfigDescriptor.describe(
          reopened.findTransform("http-node").getTransform().getClass())
          .runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "HTTP_REPEATED_RESPONSE_HEADER_NAMES_SERIALIZE_AS_JSON_ARRAY"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/result/response_header"), contract.keyListPointers());

      List<Object[]> rows = new CopyOnWriteArrayList<>();
      AtomicReference<IRowMeta> rowMeta = new AtomicReference<>();
      LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
      engine.setMetadataProvider(provider);
      try {
        engine.prepareExecution();
        ITransform output = engine.getRunThread("http-node", 0);
        assertNotNull(output);
        output.addRowListener(new RowAdapter() {
          @Override
          public void rowWrittenEvent(IRowMeta metadata, Object[] row) {
            rowMeta.set(metadata.clone());
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

      assertNotNull(rowMeta.get());
      assertEquals(List.of("请求地址<&>", "响应正文", "响应状态", "响应头"),
          rowMeta.get().getValueMetaList().stream().map(IValueMeta::getName).toList());
      assertEquals(IValueMeta.TYPE_STRING, rowMeta.get().getValueMeta(3).getType());
      assertEquals(2, rows.size());
      ObjectMapper mapper = new ObjectMapper();
      for (Object[] row : rows) {
        boolean duplicate = ((String) row[0]).endsWith("/duplicate");
        assertEquals(duplicate ? "黄河双值" : "长江单值", row[1]);
        assertEquals(200L, row[2]);
        JsonNode headers = mapper.readTree((String) row[3]);
        JsonNode values = null;
        for (var names = headers.fieldNames(); names.hasNext();) {
          String name = names.next();
          if (name.equalsIgnoreCase("X-Test-Values")) {
            values = headers.get(name);
            break;
          }
        }
        assertNotNull(values, "custom header must survive response JSON");
        if (duplicate) {
          assertTrue(values.isArray(), "repeated header must not be flattened");
          assertEquals(List.of("first", "second"),
              java.util.stream.StreamSupport.stream(values.spliterator(), false)
                  .map(JsonNode::asText).toList());
        } else {
          assertTrue(values.isTextual(), "single header must remain a JSON string");
          assertEquals("first", values.asText());
        }
      }
      assertEquals(2, requests.get());
      System.out.println("PROD_P0_HTTP_REPEATED_HEADERS_ARRAY_SINGLE_FULL_PIPELINE=PASS");
    } finally {
      server.stop(0);
    }
  }
}
