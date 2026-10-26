package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
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

/** T3 native REST response charset contract: real loopback HTTP, not a full Pipeline run. */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class RestResponseCharsetP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void restResponseCharsetOverridesRequestApplicationTypeAndUtf8FallbackAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    String utf16Text = "黄河<&>雪";
    String utf8Text = "中文 + plus";
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/utf16", exchange -> {
      try (exchange) {
        byte[] bytes = utf16Text.getBytes(StandardCharsets.UTF_16LE);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-16LE");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.createContext("/fallback", exchange -> {
      try (exchange) {
        byte[] bytes = utf8Text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin, "pinned source-derived REST plugin is required");
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class)
          .invoke(original, base + "/utf16");

      var descriptor = AutomaticConfigDescriptor.describe(original.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "REST_RESPONSE_CHARSET_HEADER_OVERRIDES_REQUEST_TYPE_UTF8_FALLBACK"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/applicationType", "/result/name", "/result/binary"),
          contract.keyListPointers());
      assertEquals(AutomaticConfigDescriptor.Shape.STRING,
          descriptor.properties().stream().filter(p -> p.key().equals("applicationType"))
              .findFirst().orElseThrow().shape());
      var resultProperty = descriptor.properties().stream()
          .filter(p -> p.key().equals("result")).findFirst().orElseThrow();
      assertEquals(AutomaticConfigDescriptor.Shape.BOOLEAN,
          resultProperty.children().stream().filter(p -> p.key().equals("binary"))
              .findFirst().orElseThrow().shape());

      var provider = new MemoryMetadataProvider();
      var variables = new Variables();
      var store = new PipelineDocumentStore(provider, variables);
      Path hpl = tempDir.resolve("rest-response-charset.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-response-charset");
      pipeline.addTransform(new TransformMeta("Rest", "rest-charset-node", original));
      store.save(hpl, pipeline);

      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-charset-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-charset-doc",
          new PipelineConfigResource.ReadRequest("rest-charset-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("url", base + "/utf16");
      draft.put("method", "GET");
      draft.put("applicationType", "JSON");
      ObjectNode resultDraft = (ObjectNode) draft.path("result");
      assertTrue(resultDraft.isObject());
      resultDraft.put("name", "响应_雪<&>");
      resultDraft.put("binary", false);
      resultDraft.put("code", "状态码");
      resultDraft.put("response_time", "");
      resultDraft.put("response_header", "");
      var write = resource.write("rest-charset-doc",
          new PipelineConfigResource.WriteRequest("rest-charset-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-charset-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals("JSON", wire.at("/applicationType").asText());
      assertEquals("响应_雪<&>", wire.at("/result/name").asText());
      assertTrue(wire.at("/result/binary").isBoolean());
      assertFalse(wire.at("/result/binary").booleanValue());
      assertEquals("状态码", wire.at("/result/code").asText());

      var output = new RowMeta();
      diskMeta.getFields(output, "rest-charset-node", new IRowMeta[0],
          null, variables, provider);
      assertEquals(2, output.size());
      assertEquals("响应_雪<&>", output.getValueMeta(0).getName());
      assertEquals(IValueMeta.TYPE_STRING, output.getValueMeta(0).getType());
      assertEquals("状态码", output.getValueMeta(1).getName());
      assertEquals(IValueMeta.TYPE_INTEGER, output.getValueMeta(1).getType());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      Object data = dataType.getConstructor().newInstance();
      Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("rest-charset-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      restType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
      assertTrue((Boolean) restType.getMethod("init").invoke(rest));
      dataType.getField("inputRowMeta").set(data, new RowMeta());
      dataType.getField("method").set(data, "GET");
      dataType.getField("binaryResult").setBoolean(data, false);
      dataType.getField("resultFieldName").set(data, "响应_雪<&>");
      dataType.getField("resultCodeFieldName").set(data, "状态码");
      dataType.getField("resultResponseFieldName").set(data, null);
      dataType.getField("resultHeaderFieldName").set(data, null);
      var invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
          String.class, LinkedHashMap.class, LinkedHashMap.class, LinkedHashMap.class);
      invoke.setAccessible(true);
      try {
        for (String suffix : List.of("/utf16", "/fallback")) {
          String expected = suffix.equals("/utf16") ? utf16Text : utf8Text;
          dataType.getField("realUrl").set(data, base + suffix);
          Object exchange = invoke.invoke(rest, new Object[0], null,
              new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
              new LinkedHashMap<String, String>());
          var body = exchange.getClass().getDeclaredField("body");
          body.setAccessible(true);
          assertEquals(expected, body.get(exchange),
              "response Content-Type charset controls text decoding, not request JSON type");
          var bytes = exchange.getClass().getDeclaredField("bodyBytes");
          bytes.setAccessible(true);
          assertNull(bytes.get(exchange), "text result must not switch to BINARY");
          var status = exchange.getClass().getDeclaredField("status");
          status.setAccessible(true);
          assertEquals(200, status.getInt(exchange));
          var assemble = restType.getDeclaredMethod("assembleResultRow",
              Object[].class, exchange.getClass());
          assemble.setAccessible(true);
          Object[] row = (Object[]) assemble.invoke(rest, new Object[0], exchange);
          assertEquals(expected, row[0]);
          assertEquals(200L, row[1]);
        }
      } finally {
        restType.getMethod("dispose").invoke(rest);
      }
      System.out.println("PROD_P0_REST_RESPONSE_CHARSET_HEADER_UTF8_FALLBACK_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }
}
