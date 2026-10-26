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
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
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
 * Native REST non-streaming response contract for 204 and 4xx/5xx status codes.
 * Uses a loopback-only HTTP server and a real Hop XML disk reopen.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class RestNonStreamingStatusP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void restNonStreaming204And401404500KeepTypedStatusAndBodyAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int[] codes = {204, 401, 404, 500};
    for (int code : codes) {
      server.createContext("/status" + code, exchange -> {
        try (exchange) {
          assertEquals("GET", exchange.getRequestMethod());
          requests.incrementAndGet();
          exchange.getResponseHeaders().set("X-Result-Origin", "native-" + code);
          if (code == 204) {
            exchange.sendResponseHeaders(code, -1);
          } else {
            byte[] bytes = ("错误状态-雪<&>:" + code).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        }
      });
    }
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin, "pinned source-derived REST plugin required");
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class)
          .invoke(original, base + "/status204");

      var contract = AutomaticConfigDescriptor.describe(original.getClass())
          .runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "REST_NONSTREAMING_204_AND_HTTP_ERRORS_EMIT_TYPED_RESULT_ROWS"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/url", "/method", "/streamingEnabled", "/result/name",
          "/result/code", "/result/response_header", "/result/binary",
          "/retryMethods/retryMethod", "/retryStatusCodes/retryStatusCode",
          "/retryTimes"), contract.keyListPointers());

      var provider = new MemoryMetadataProvider();
      var variables = new Variables();
      var store = new PipelineDocumentStore(provider, variables);
      Path hpl = tempDir.resolve("rest-nonstreaming-status.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-nonstreaming-status");
      pipeline.addTransform(new TransformMeta("Rest", "rest-status-node", original));
      store.save(hpl, pipeline);

      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-status-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-status-doc",
          new PipelineConfigResource.ReadRequest("rest-status-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("url", base + "/status204");
      draft.put("method", "GET");
      draft.put("streamingEnabled", false);
      // Deliberately exclude GET/204/401/404/500 from Hop-level retry matching.
      // Otherwise a configured retry status may throw after exhaustion rather than emit a row.
      draft.put("retryTimes", 0);
      draft.putObject("retryMethods").putArray("retryMethod").add("POST");
      draft.putObject("retryStatusCodes").putArray("retryStatusCode").add("418");
      ObjectNode result = (ObjectNode) draft.path("result");
      assertTrue(result.isObject());
      result.put("name", "响应正文_雪<&>");
      result.put("code", "HTTP状态码");
      result.put("response_header", "响应头");
      result.put("response_time", "");
      result.put("binary", false);
      var write = resource.write("rest-status-doc",
          new PipelineConfigResource.WriteRequest("rest-status-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-status-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals(base + "/status204", wire.at("/url").asText());
      assertEquals("GET", wire.at("/method").asText());
      assertTrue(wire.at("/streamingEnabled").isBoolean());
      assertFalse(wire.at("/streamingEnabled").booleanValue());
      assertEquals("POST", wire.at("/retryMethods/retryMethod/0").asText());
      assertEquals("418", wire.at("/retryStatusCodes/retryStatusCode/0").asText());
      assertTrue(wire.at("/retryTimes").isNumber());
      assertEquals(0, wire.at("/retryTimes").intValue());
      assertEquals("响应正文_雪<&>", wire.at("/result/name").asText());
      assertEquals("HTTP状态码", wire.at("/result/code").asText());
      assertEquals("响应头", wire.at("/result/response_header").asText());
      assertTrue(wire.at("/result/binary").isBoolean());
      assertFalse(wire.at("/result/binary").booleanValue());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      Object data = dataType.getConstructor().newInstance();
      Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("rest-status-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      restType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
      assertTrue((Boolean) restType.getMethod("init").invoke(rest));
      dataType.getField("inputRowMeta").set(data, new RowMeta());
      dataType.getField("method").set(data, "GET");
      dataType.getField("binaryResult").setBoolean(data, false);
      dataType.getField("resultFieldName").set(data, "响应正文_雪<&>");
      dataType.getField("resultCodeFieldName").set(data, "HTTP状态码");
      dataType.getField("resultResponseFieldName").set(data, null);
      dataType.getField("resultHeaderFieldName").set(data, "响应头");
      var invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
          String.class, LinkedHashMap.class, LinkedHashMap.class, LinkedHashMap.class);
      invoke.setAccessible(true);
      ObjectMapper mapper = new ObjectMapper();
      try {
        for (int code : codes) {
          dataType.getField("realUrl").set(data, base + "/status" + code);
          Object exchange = invoke.invoke(rest, new Object[0], null,
              new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
              new LinkedHashMap<String, String>());
          var status = exchange.getClass().getDeclaredField("status");
          status.setAccessible(true);
          assertEquals(code, status.getInt(exchange));
          var assemble = restType.getDeclaredMethod("assembleResultRow",
              Object[].class, exchange.getClass());
          assemble.setAccessible(true);
          Object[] row = (Object[]) assemble.invoke(rest, new Object[0], exchange);
          String expectedBody = code == 204 ? "" : "错误状态-雪<&>:" + code;
          assertEquals(expectedBody, row[0],
              "204 must be empty text; HTTP error bodies must remain visible");
          assertInstanceOf(Long.class, row[1], "Hop INTEGER is carried as Java Long");
          assertEquals((long) code, row[1]);
          JsonNode headers = mapper.readTree((String) row[2]);
          boolean found = false;
          for (var keys = headers.fieldNames(); keys.hasNext();) {
            String key = keys.next();
            if (key.equalsIgnoreCase("X-Result-Origin")) {
              found = true;
              assertEquals("native-" + code, headers.path(key).asText());
            }
          }
          assertTrue(found, "native final-response header must be preserved");
        }
      } finally {
        restType.getMethod("dispose").invoke(rest);
      }
      assertEquals(codes.length, requests.get(),
          "the 204 and error statuses must each reach the loopback endpoint once");
      System.out.println("PROD_P0_REST_NONSTREAMING_204_4XX_5XX_ROWS_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }
}
