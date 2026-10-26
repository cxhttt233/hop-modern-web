package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
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
 * T3 production REST gzip response contract. Exercises real Hop REST and loopback HTTP,
 * authoritative JSON edits, and actual .hpl disk reopen; not a full Pipeline run.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class RestGzipResponseP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void restGzipDecompressesBeforeBothTextAndBinaryOutputAfterRealHplDiskReopen(
      @TempDir Path tempDir) throws Exception {
    String text = "黄河<&>雪 + gzip";
    byte[] decoded = text.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
      gzip.write(decoded);
    }
    byte[] compressed = buffer.toByteArray();
    assertFalse(java.util.Arrays.equals(decoded, compressed));
    AtomicInteger requests = new AtomicInteger();

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/gzip", exchange -> {
      try (exchange) {
        requests.incrementAndGet();
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        exchange.sendResponseHeaders(200, compressed.length);
        exchange.getResponseBody().write(compressed);
      }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/gzip";
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin, "source-derived pinned REST plugin must be installed");
      ITransformMeta prototype = plugins.loadClass(plugin, ITransformMeta.class);
      var descriptor = AutomaticConfigDescriptor.describe(prototype.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "REST_GZIP_CONTENT_ENCODING_DECOMPRESSES_BEFORE_TEXT_AND_BINARY_RESULT"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/url", "/result/name", "/result/binary", "/result/code"),
          contract.keyListPointers());
      var resultDescriptor = descriptor.properties().stream()
          .filter(p -> p.key().equals("result")).findFirst().orElseThrow();
      assertEquals(AutomaticConfigDescriptor.Shape.OBJECT, resultDescriptor.shape());
      assertEquals(AutomaticConfigDescriptor.Shape.BOOLEAN,
          resultDescriptor.children().stream()
              .filter(p -> p.key().equals("binary")).findFirst().orElseThrow().shape());

      for (boolean binary : new boolean[] {false, true}) {
        ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
        original.setDefault();
        original.getClass().getMethod("setUrl", String.class).invoke(original, url);

        var provider = new MemoryMetadataProvider();
        var variables = new Variables();
        var store = new PipelineDocumentStore(provider, variables);
        String suffix = binary ? "binary" : "text";
        Path hpl = tempDir.resolve("rest-gzip-" + suffix + ".hpl");
        PipelineMeta pipeline = new PipelineMeta();
        pipeline.setName("rest-gzip-" + suffix);
        pipeline.addTransform(new TransformMeta("Rest", "rest-gzip-node", original));
        store.save(hpl, pipeline);

        PipelineMeta loaded = store.open(hpl);
        var documents = new PipelineDocumentRegistry();
        documents.put(new PipelineDocument("rest-gzip-doc", hpl, loaded));
        var resource = new PipelineConfigResource(documents, provider);
        var read = resource.read("rest-gzip-doc",
            new PipelineConfigResource.ReadRequest("rest-gzip-node"));
        assertEquals(200, read.getStatus());
        ObjectNode draft = ((ObjectNode)
            ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
        draft.put("url", url);
        draft.put("method", "GET");
        ObjectNode resultDraft = (ObjectNode) draft.path("result");
        resultDraft.put("name", "压缩响应_雪");
        resultDraft.put("binary", binary);
        resultDraft.put("code", "状态码");
        resultDraft.put("response_time", "");
        resultDraft.put("response_header", "");
        var write = resource.write("rest-gzip-doc",
            new PipelineConfigResource.WriteRequest("rest-gzip-node", draft));
        assertEquals(200, write.getStatus());
        store.save(hpl, loaded);

        PipelineMeta reopened = store.open(hpl);
        ITransformMeta diskMeta = reopened.findTransform("rest-gzip-node").getTransform();
        JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
        assertEquals(url, wire.at("/url").asText());
        assertEquals("GET", wire.at("/method").asText());
        assertEquals("压缩响应_雪", wire.at("/result/name").asText());
        assertTrue(wire.at("/result/binary").isBoolean());
        assertEquals(binary, wire.at("/result/binary").booleanValue());
        assertEquals("状态码", wire.at("/result/code").asText());

        var output = new RowMeta();
        diskMeta.getFields(output, "rest-gzip-node", new IRowMeta[0],
            null, variables, provider);
        assertEquals(2, output.size());
        assertEquals("压缩响应_雪", output.getValueMeta(0).getName());
        assertEquals(binary ? IValueMeta.TYPE_BINARY : IValueMeta.TYPE_STRING,
            output.getValueMeta(0).getType());
        assertEquals("状态码", output.getValueMeta(1).getName());
        assertEquals(IValueMeta.TYPE_INTEGER, output.getValueMeta(1).getType());

        ClassLoader loader = diskMeta.getClass().getClassLoader();
        Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
        Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
        Object data = dataType.getConstructor().newInstance();
        Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
            dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
            .newInstance(reopened.findTransform("rest-gzip-node"), diskMeta, data, 0,
                reopened, Mockito.spy(new LocalPipelineEngine()));
        restType.getMethod("setMetadataProvider",
            org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
        assertTrue((Boolean) restType.getMethod("init").invoke(rest));
        dataType.getField("inputRowMeta").set(data, new RowMeta());
        dataType.getField("realUrl").set(data, url);
        dataType.getField("method").set(data, "GET");
        dataType.getField("binaryResult").setBoolean(data, binary);
        dataType.getField("resultFieldName").set(data, "压缩响应_雪");
        dataType.getField("resultCodeFieldName").set(data, "状态码");
        dataType.getField("resultResponseFieldName").set(data, null);
        dataType.getField("resultHeaderFieldName").set(data, null);

        var invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
            String.class, LinkedHashMap.class, LinkedHashMap.class, LinkedHashMap.class);
        invoke.setAccessible(true);
        try {
          Object exchange = invoke.invoke(rest, new Object[0], null,
              new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
              new LinkedHashMap<String, String>());
          var bodyField = exchange.getClass().getDeclaredField("body");
          bodyField.setAccessible(true);
          var bytesField = exchange.getClass().getDeclaredField("bodyBytes");
          bytesField.setAccessible(true);
          var statusField = exchange.getClass().getDeclaredField("status");
          statusField.setAccessible(true);
          assertEquals(200, statusField.getInt(exchange));

          var assemble = restType.getDeclaredMethod("assembleResultRow",
              Object[].class, exchange.getClass());
          assemble.setAccessible(true);
          Object[] row = (Object[]) assemble.invoke(rest, new Object[0], exchange);
          // RowDataUtil may allocate spare capacity; assert the two populated fields below.
          if (binary) {
            assertNull(bodyField.get(exchange));
            assertArrayEquals(decoded, (byte[]) bytesField.get(exchange),
                "REST binary result contains the decompressed entity, not gzip wire bytes");
            assertArrayEquals(decoded, (byte[]) row[0]);
          } else {
            assertEquals(text, bodyField.get(exchange));
            assertNull(bytesField.get(exchange));
            assertEquals(text, row[0]);
          }
          assertEquals(200L, row[1]);
        } finally {
          restType.getMethod("dispose").invoke(rest);
        }
      }
      assertEquals(2, requests.get(), "one real loopback request for each persisted mode");
      System.out.println("PROD_P0_REST_GZIP_TEXT_BINARY_DECOMPRESSED_LOOPBACK_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }
}
