package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * Native Http (not Rest) preemptive Basic authentication compatibility.
 * Only an isolated loopback HTTP fixture receives requests; no credentials
 * or production endpoints are used. The test exercises the actual Hop engine.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class HttpBasicAuthFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void httpBasicCredentialsAreSentOnFirstRequestAfterConfigEditAndHplReopen(
      @TempDir Path tempDir) throws Exception {
    String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
        "fixture-user:fixture-password".getBytes(StandardCharsets.UTF_8));
    List<String> receivedAuth = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/secure", exchange -> {
      try (exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        receivedAuth.add(expectedAuth.equals(auth) ? "authorized" : "missing-or-invalid");
        if (!expectedAuth.equals(auth)) {
          exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"local-fixture\"");
          exchange.sendResponseHeaders(401, -1);
        } else {
          byte[] body = "authorized-黄河".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        }
      }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/secure";
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
      fieldType.getMethod("setName", String.class).invoke(field, "批次");
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      grid.getClass().getMethod("setDataGridFields", List.class)
          .invoke(grid, List.of(field));
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, List.of("黄河"));
      grid.getClass().getMethod("setDataLines", List.class)
          .invoke(grid, List.of(line));

      ITransformMeta http = plugins.loadClass(httpPlugin, ITransformMeta.class);
      http.setDefault();
      http.getClass().getMethod("setHttpLogin", String.class)
          .invoke(http, "fixture-user");
      http.getClass().getMethod("setHttpPassword", String.class)
          .invoke(http, "fixture-password");
      TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
      TransformMeta target = new TransformMeta("Http", "http-node", http);
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("http-basic-first-request");
      pipeline.addTransform(source);
      pipeline.addTransform(target);
      pipeline.addPipelineHop(new PipelineHopMeta(source, target));

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("http-basic-first-request.hpl");
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var registry = new PipelineDocumentRegistry();
      registry.put(new PipelineDocument("http-basic-doc", hpl, loaded));
      var resource = new PipelineConfigResource(registry, provider);
      var read = resource.read("http-basic-doc",
          new PipelineConfigResource.ReadRequest("http-node"));
      assertEquals(200, read.getStatus());
      var response = (PipelineConfigResource.ConfigResponse) read.getEntity();
      var passwordProperty = response.descriptor().properties().stream()
          .filter(p -> p.key().equals("httpPassword")).findFirst().orElseThrow();
      assertTrue(passwordProperty.sensitive(), "native password metadata must be sensitive");
      assertEquals(AutomaticConfigDescriptor.Shape.STRING, passwordProperty.shape());
      var contract = response.descriptor().runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "HTTP_BASIC_LOGIN_PREEMPTIVELY_SENDS_CREDENTIALS_TO_ORIGIN"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/httpLogin", "/httpPassword", "/url"),
          contract.keyListPointers());
      ObjectNode draft = ((ObjectNode) response.config()).deepCopy();
      JsonNode originalPasswordWire = draft.path("httpPassword").deepCopy();
      assertTrue(originalPasswordWire.isTextual());
      draft.put("url", url);
      draft.put("encoding", "UTF-8");
      ObjectNode result = (ObjectNode) draft.path("result");
      result.put("name", "响应正文");
      result.put("code", "HTTP状态");
      result.put("response_header", "");
      result.put("response_time", "");
      var write = resource.write("http-basic-doc",
          new PipelineConfigResource.WriteRequest("http-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      assertEquals(1, reopened.nrPipelineHops());
      JsonNode wire = ConfigJsonSerializer.toJson(
          reopened.findTransform("http-node").getTransform(), provider);
      assertEquals(url, wire.at("/url").asText());
      assertEquals("fixture-user", wire.at("/httpLogin").asText());
      assertEquals(originalPasswordWire, wire.at("/httpPassword"),
          "unmodified sensitive wire value must survive disk reopen");
      assertEquals("响应正文", wire.at("/result/name").asText());
      assertEquals("HTTP状态", wire.at("/result/code").asText());

      List<Object[]> rows = new CopyOnWriteArrayList<>();
      List<IRowMeta> metas = new CopyOnWriteArrayList<>();
      LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
      engine.setMetadataProvider(provider);
      try {
        engine.prepareExecution();
        ITransform output = engine.getRunThread("http-node", 0);
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
        assertEquals(0, engine.getErrors(), "native Http Basic auth must succeed");
      } finally {
        if (!engine.isFinishedOrStopped()) engine.stopAll();
        engine.cleanup();
      }
      assertEquals(List.of("authorized"), receivedAuth,
          "native Http must send Basic credentials on its first request");
      assertEquals(1, rows.size());
      assertEquals(List.of("批次", "响应正文", "HTTP状态"),
          metas.get(0).getValueMetaList().stream().map(IValueMeta::getName).toList());
      assertEquals(IValueMeta.TYPE_STRING, metas.get(0).getValueMeta(1).getType());
      assertEquals(IValueMeta.TYPE_INTEGER, metas.get(0).getValueMeta(2).getType());
      assertEquals("黄河", rows.get(0)[0]);
      assertEquals("authorized-黄河", rows.get(0)[1]);
      assertEquals(200L, rows.get(0)[2]);
      System.out.println("PROD_P0_HTTP_BASIC_PREEMPTIVE_FIRST_REQUEST_JSON_HPL_ROWS=PASS");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void httpRedirectToDifferentOriginDoesNotPreemptivelyForwardBasicCredentialsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
        "fixture-user:fixture-password".getBytes(StandardCharsets.UTF_8));
    List<String> originAuth = new CopyOnWriteArrayList<>();
    List<String> redirectedAuth = new CopyOnWriteArrayList<>();

    HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    destination.createContext("/final", exchange -> {
      try (exchange) {
        redirectedAuth.add(exchange.getRequestHeaders().getFirst("Authorization") == null
            ? "absent" : "present");
        byte[] body = "redirected-黄河".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
    });
    destination.start();
    HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    origin.createContext("/redirect", exchange -> {
      try (exchange) {
        originAuth.add(expectedAuth.equals(exchange.getRequestHeaders().getFirst("Authorization"))
            ? "authorized" : "missing-or-invalid");
        exchange.getResponseHeaders().set("Location",
            "http://127.0.0.1:" + destination.getAddress().getPort() + "/final");
        exchange.sendResponseHeaders(302, -1);
      }
    });
    origin.start();
    try {
      String startUrl = "http://127.0.0.1:" + origin.getAddress().getPort() + "/redirect";
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
      fieldType.getMethod("setName", String.class).invoke(field, "批次");
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, List.of(field));
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, List.of("黄河"));
      grid.getClass().getMethod("setDataLines", List.class).invoke(grid, List.of(line));

      ITransformMeta http = plugins.loadClass(httpPlugin, ITransformMeta.class);
      http.setDefault();
      http.getClass().getMethod("setHttpLogin", String.class).invoke(http, "fixture-user");
      http.getClass().getMethod("setHttpPassword", String.class)
          .invoke(http, "fixture-password");
      TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
      TransformMeta target = new TransformMeta("Http", "http-node", http);
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("http-basic-cross-origin-redirect");
      pipeline.addTransform(source);
      pipeline.addTransform(target);
      pipeline.addPipelineHop(new PipelineHopMeta(source, target));

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("http-basic-cross-origin-redirect.hpl");
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var registry = new PipelineDocumentRegistry();
      registry.put(new PipelineDocument("http-cross-origin-doc", hpl, loaded));
      var resource = new PipelineConfigResource(registry, provider);
      var read = resource.read("http-cross-origin-doc",
          new PipelineConfigResource.ReadRequest("http-node"));
      assertEquals(200, read.getStatus());
      var redirectAuthContract = ((PipelineConfigResource.ConfigResponse) read.getEntity())
          .descriptor().runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "HTTP_BASIC_AUTH_CROSS_ORIGIN_REDIRECT_DOES_NOT_PREEMPTIVELY_FORWARD"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/url", "/httpLogin", "/httpPassword",
          "/result/name", "/result/code"), redirectAuthContract.keyListPointers());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      JsonNode passwordWire = draft.path("httpPassword").deepCopy();
      draft.put("url", startUrl);
      draft.put("encoding", "UTF-8");
      ObjectNode result = (ObjectNode) draft.path("result");
      result.put("name", "响应正文");
      result.put("code", "HTTP状态");
      result.put("response_header", "");
      result.put("response_time", "");
      var write = resource.write("http-cross-origin-doc",
          new PipelineConfigResource.WriteRequest("http-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      JsonNode wire = ConfigJsonSerializer.toJson(
          reopened.findTransform("http-node").getTransform(), provider);
      assertEquals(startUrl, wire.at("/url").asText());
      assertEquals("fixture-user", wire.at("/httpLogin").asText());
      assertEquals(passwordWire, wire.at("/httpPassword"));
      List<Object[]> rows = new CopyOnWriteArrayList<>();
      List<IRowMeta> metas = new CopyOnWriteArrayList<>();
      LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
      engine.setMetadataProvider(provider);
      try {
        engine.prepareExecution();
        ITransform output = engine.getRunThread("http-node", 0);
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
      assertEquals(List.of("authorized"), originAuth,
          "the initial origin receives the configured Basic credentials");
      assertEquals(List.of("absent"), redirectedAuth,
          "a different port is a different HTTP origin; do not forward credentials");
      assertEquals(1, rows.size());
      assertEquals(List.of("批次", "响应正文", "HTTP状态"),
          metas.get(0).getValueMetaList().stream().map(IValueMeta::getName).toList());
      assertEquals(IValueMeta.TYPE_STRING, metas.get(0).getValueMeta(1).getType());
      assertEquals(IValueMeta.TYPE_INTEGER, metas.get(0).getValueMeta(2).getType());
      assertEquals("redirected-黄河", rows.get(0)[1]);
      assertEquals(200L, rows.get(0)[2]);
      System.out.println("PROD_P0_HTTP_BASIC_CROSS_ORIGIN_REDIRECT_AUTH_SCOPE_JSON_HPL_ROWS=PASS");
    } finally {
      origin.stop(0);
      destination.stop(0);
    }
  }

}
