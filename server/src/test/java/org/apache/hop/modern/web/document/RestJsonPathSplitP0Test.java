package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.json.ConfigJsonSerializer;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engines.local.LocalPipelineEngine;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * T3 production compatibility: a JSONPath result split is response-driven even if the
 * request's applicationType is FORM URLENCODED. The test exercises native REST code,
 * an isolated HTTP endpoint and an actual Hop .hpl disk reopen, not a full Pipeline run.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class RestJsonPathSplitP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void restJsonPathArraySplitSurvivesFormRequestTypeAndRealHplReopen(
      @TempDir Path tempDir) throws Exception {
    String payload = "[{\"id\":1,\"label\":\"黄河<&>\"},{\"id\":2,\"label\":\"雪\"}]";
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/items", exchange -> {
      try (exchange) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/items";
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin, "pinned REST plugin must be installed from source");
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class).invoke(original, url);

      var descriptor = AutomaticConfigDescriptor.describe(original.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "REST_SPLIT_PATH_JSONPATH_OVERRIDES_REQUEST_TYPE_XPATH_REQUIRES_XML"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/applicationType", "/resultSplitPath", "/result/name"),
          contract.keyListPointers());
      assertEquals(AutomaticConfigDescriptor.Shape.STRING,
          descriptor.properties().stream()
              .filter(p -> p.key().equals("resultSplitPath")).findFirst().orElseThrow().shape());

      var provider = new MemoryMetadataProvider();
      var variables = new Variables();
      var store = new PipelineDocumentStore(provider, variables);
      Path hpl = tempDir.resolve("rest-jsonpath-split.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-jsonpath-split");
      pipeline.addTransform(new TransformMeta("Rest", "rest-split-node", original));
      store.save(hpl, pipeline);

      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-split-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-split-doc",
          new PipelineConfigResource.ReadRequest("rest-split-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("url", url);
      draft.put("method", "GET");
      draft.put("applicationType", "FORM URLENCODED");
      draft.put("resultSplitPath", "$[*]");
      ObjectNode result = (ObjectNode) draft.path("result");
      assertTrue(result.isObject());
      result.put("name", "拆分_响应");
      result.put("binary", false);
      result.put("code", "状态");
      result.put("response_time", "");
      result.put("response_header", "");
      var write = resource.write("rest-split-doc",
          new PipelineConfigResource.WriteRequest("rest-split-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-split-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals("FORM URLENCODED", wire.at("/applicationType").asText());
      assertEquals("$[*]", wire.at("/resultSplitPath").asText());
      assertEquals("拆分_响应", wire.at("/result/name").asText());
      assertTrue(wire.at("/result/binary").isBoolean());
      assertFalse(wire.at("/result/binary").booleanValue());

      var output = new RowMeta();
      diskMeta.getFields(output, "rest-split-node", new IRowMeta[0],
          null, variables, provider);
      assertEquals(2, output.size());
      assertEquals(IValueMeta.TYPE_STRING, output.getValueMeta(0).getType());
      assertEquals(IValueMeta.TYPE_INTEGER, output.getValueMeta(1).getType());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      Object data = dataType.getConstructor().newInstance();
      Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("rest-split-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      restType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
      assertTrue((Boolean) restType.getMethod("init").invoke(rest));
      try {
        dataType.getField("inputRowMeta").set(data, new RowMeta());
        dataType.getField("method").set(data, "GET");
        dataType.getField("realUrl").set(data, url);
        dataType.getField("binaryResult").setBoolean(data, false);
        var invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
            String.class, LinkedHashMap.class, LinkedHashMap.class, LinkedHashMap.class);
        invoke.setAccessible(true);
        Object exchange = invoke.invoke(rest, new Object[0], null,
            new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
            new LinkedHashMap<String, String>());
        var body = exchange.getClass().getDeclaredField("body");
        body.setAccessible(true);
        assertEquals(payload, body.get(exchange));

        var resolve = restType.getDeclaredMethod(
            "resolveSplitPathOrPagingExpression", String.class);
        resolve.setAccessible(true);
        String resolved = (String) resolve.invoke(rest, wire.at("/resultSplitPath").asText());
        assertEquals("$[*]", resolved, "JSONPath brackets must not be consumed as Hop hex escapes");
        var split = restType.getDeclaredMethod("splitResultItems", String.class, String.class);
        split.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> pieces = (List<String>) split.invoke(rest, body.get(exchange), resolved);
        assertEquals(2, pieces.size(), "native REST must split JSON array into two rows");
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.readTree("{\"id\":1,\"label\":\"黄河<&>\"}"),
            mapper.readTree(pieces.get(0)));
        assertEquals(mapper.readTree("{\"id\":2,\"label\":\"雪\"}"),
            mapper.readTree(pieces.get(1)));
      } finally {
        restType.getMethod("dispose").invoke(rest);
      }
      System.out.println("PROD_P0_REST_JSONPATH_SPLIT_FORM_REQUEST_LOOPBACK_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }
}
