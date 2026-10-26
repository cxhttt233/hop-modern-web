package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
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
 * Native REST response bytes -> result row, with authoritative config and real .hpl disk reopen.
 * The HTTP exchange is confined to 127.0.0.1; this is not a full Pipeline execution test.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class RestBinaryResponseP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void restBinaryResponsePreservesNonTextBytesInNativeOutputRowAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    byte[] payload = new byte[] {0, (byte) 0xff, (byte) 0x80, (byte) 0xc3,
        0x28, 0x0a, (byte) 0xe9, 0x00};
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/raw-response", exchange -> {
      try (exchange) {
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
      }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/raw-response";
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin, "pinned production Rest plugin must be installed");
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class).invoke(original, url);

      var descriptor = AutomaticConfigDescriptor.describe(original.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals("REST_BINARY_RESULT_FIELD_CHANGES_OUTPUT_ROW_TYPE"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/result/name", "/result/binary", "/result/code",
          "/result/response_time", "/result/response_header"), contract.keyListPointers());
      var result = descriptor.properties().stream()
          .filter(p -> p.key().equals("result")).findFirst().orElseThrow();
      assertEquals(AutomaticConfigDescriptor.Shape.OBJECT, result.shape());
      assertEquals(AutomaticConfigDescriptor.Shape.BOOLEAN,
          result.children().stream().filter(p -> p.key().equals("binary"))
              .findFirst().orElseThrow().shape());

      var provider = new MemoryMetadataProvider();
      var variables = new Variables();
      var store = new PipelineDocumentStore(provider, variables);
      Path hpl = tempDir.resolve("rest-raw-response.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-raw-response");
      pipeline.addTransform(new TransformMeta("Rest", "rest-raw-node", original));
      store.save(hpl, pipeline);

      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-raw-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-raw-doc",
          new PipelineConfigResource.ReadRequest("rest-raw-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("url", url);
      draft.put("method", "GET");
      ObjectNode resultDraft = (ObjectNode) draft.path("result");
      assertTrue(resultDraft.isObject());
      resultDraft.put("name", "原始字节_雪");
      resultDraft.put("binary", true);
      resultDraft.put("code", "状态码_甲");
      resultDraft.put("response_time", "");
      resultDraft.put("response_header", "");
      var write = resource.write("rest-raw-doc",
          new PipelineConfigResource.WriteRequest("rest-raw-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-raw-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals(url, wire.at("/url").asText());
      assertEquals("GET", wire.at("/method").asText());
      assertEquals("原始字节_雪", wire.at("/result/name").asText());
      assertTrue(wire.at("/result/binary").isBoolean());
      assertTrue(wire.at("/result/binary").booleanValue());
      assertEquals("状态码_甲", wire.at("/result/code").asText());

      var output = new RowMeta();
      diskMeta.getFields(output, "rest-raw-node", new IRowMeta[0],
          null, variables, provider);
      assertEquals(2, output.size());
      assertEquals("原始字节_雪", output.getValueMeta(0).getName());
      assertEquals(IValueMeta.TYPE_BINARY, output.getValueMeta(0).getType());
      assertEquals("状态码_甲", output.getValueMeta(1).getName());
      assertEquals(IValueMeta.TYPE_INTEGER, output.getValueMeta(1).getType());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      Object data = dataType.getConstructor().newInstance();
      Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("rest-raw-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      restType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
      assertTrue((Boolean) restType.getMethod("init").invoke(rest));
      dataType.getField("inputRowMeta").set(data, new RowMeta());
      dataType.getField("realUrl").set(data, url);
      dataType.getField("method").set(data, "GET");
      dataType.getField("binaryResult").setBoolean(data, true);
      dataType.getField("resultFieldName").set(data, "原始字节_雪");
      dataType.getField("resultCodeFieldName").set(data, "状态码_甲");
      dataType.getField("resultResponseFieldName").set(data, null);
      dataType.getField("resultHeaderFieldName").set(data, null);

      var invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
          String.class, LinkedHashMap.class, LinkedHashMap.class, LinkedHashMap.class);
      invoke.setAccessible(true);
      try {
        Object exchange = invoke.invoke(rest, new Object[0], null,
            new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
            new LinkedHashMap<String, String>());
        var bytesField = exchange.getClass().getDeclaredField("bodyBytes");
        bytesField.setAccessible(true);
        assertArrayEquals(payload, (byte[]) bytesField.get(exchange),
            "native REST must retain non-UTF8 bytes from the HTTP response");
        var textField = exchange.getClass().getDeclaredField("body");
        textField.setAccessible(true);
        assertNull(textField.get(exchange), "binary response must not be decoded to String");
        var statusField = exchange.getClass().getDeclaredField("status");
        statusField.setAccessible(true);
        assertEquals(200, statusField.getInt(exchange));

        var assemble = restType.getDeclaredMethod("assembleResultRow",
            Object[].class, exchange.getClass());
        assemble.setAccessible(true);
        Object[] row = (Object[]) assemble.invoke(rest, new Object[0], exchange);
        assertArrayEquals(payload, (byte[]) row[0],
            "the BINARY output column must preserve the exact network bytes");
        assertEquals(200L, row[1], "status column follows binary body in native row order");
      } finally {
        restType.getMethod("dispose").invoke(rest);
      }
      System.out.println("PROD_P0_REST_BINARY_RESPONSE_RAW_BYTES_LOOPBACK_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }
}
