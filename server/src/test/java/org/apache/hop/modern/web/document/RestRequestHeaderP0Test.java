package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaString;
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

/** Native REST header collision and explicit Accept behavior, isolated loopback only. */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class RestRequestHeaderP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void restExactDuplicateRequestHeadersUseLastValueAndExplicitAcceptAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    AtomicReference<List<String>> traceHeaders = new AtomicReference<>();
    AtomicReference<String> acceptHeader = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/headers", exchange -> {
      try (exchange) {
        traceHeaders.set(exchange.getRequestHeaders().get("X-Trace"));
        acceptHeader.set(exchange.getRequestHeaders().getFirst("Accept"));
        byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/headers";
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin);
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class).invoke(original, url);

      var descriptor = AutomaticConfigDescriptor.describe(original.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "REST_REQUEST_HEADER_EXACT_DUPLICATES_LAST_WINS_ACCEPT_OVERRIDES_DEFAULT"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/headers/header", "/applicationType"), contract.keyListPointers());
      var headerProperty = descriptor.properties().stream()
          .filter(p -> p.key().equals("header")).findFirst().orElseThrow();
      assertEquals(AutomaticConfigDescriptor.Shape.LIST, headerProperty.shape());
      assertEquals("headers", headerProperty.groupKey());
      assertEquals(List.of("field", "name"),
          headerProperty.elementProperties().stream().map(p -> p.key()).toList());

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("rest-headers-collision.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-headers-collision");
      pipeline.addTransform(new TransformMeta("Rest", "rest-header-node", original));
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-header-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-header-doc",
          new PipelineConfigResource.ReadRequest("rest-header-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("method", "GET");
      draft.put("url", url);
      draft.put("applicationType", "JSON");
      ArrayNode headers = draft.putObject("headers").putArray("header");
      headers.addObject().put("field", "首个_雪").put("name", "X-Trace");
      headers.addObject().put("field", "第二个_雪").put("name", "X-Trace");
      headers.addObject().put("field", "接受类型").put("name", "aCcEpT");
      var write = resource.write("rest-header-doc",
          new PipelineConfigResource.WriteRequest("rest-header-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-header-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals("GET", wire.at("/method").asText());
      assertEquals("JSON", wire.at("/applicationType").asText());
      assertTrue(wire.at("/headers/header").isArray());
      assertEquals(3, wire.at("/headers/header").size());
      assertEquals("首个_雪", wire.at("/headers/header/0/field").asText());
      assertEquals("X-Trace", wire.at("/headers/header/0/name").asText());
      assertEquals("第二个_雪", wire.at("/headers/header/1/field").asText());
      assertEquals("X-Trace", wire.at("/headers/header/1/name").asText());
      assertEquals("接受类型", wire.at("/headers/header/2/field").asText());
      assertEquals("aCcEpT", wire.at("/headers/header/2/name").asText());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      Object data = dataType.getConstructor().newInstance();
      Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("rest-header-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      restType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
      assertTrue((Boolean) restType.getMethod("init").invoke(rest));
      var rowMeta = new RowMeta();
      rowMeta.addValueMeta(new ValueMetaString("首个_雪"));
      rowMeta.addValueMeta(new ValueMetaString("第二个_雪"));
      rowMeta.addValueMeta(new ValueMetaString("接受类型"));
      dataType.getField("inputRowMeta").set(data, rowMeta);
      dataType.getField("realUrl").set(data, url);
      dataType.getField("method").set(data, "GET");
      dataType.getField("useHeaders").setBoolean(data, true);
      dataType.getField("nrheader").setInt(data, 3);
      dataType.getField("headerNames").set(data,
          new String[] {"X-Trace", "X-Trace", "aCcEpT"});
      dataType.getField("indexOfHeaderFields").set(data, new int[] {0, 1, 2});
      Method invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
          String.class, LinkedHashMap.class, LinkedHashMap.class, LinkedHashMap.class);
      invoke.setAccessible(true);
      try {
        Object result = invoke.invoke(rest,
            new Object[] {"first-value", "last-value", "application/vnd.example+json"},
            null, new LinkedHashMap<String, String>(),
            new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>());
        var status = result.getClass().getDeclaredField("status");
        status.setAccessible(true);
        assertEquals(200, status.getInt(result));
      } finally {
        restType.getMethod("dispose").invoke(rest);
      }
      assertEquals(List.of("last-value"), traceHeaders.get(),
          "the native request map replaces the first exact-name header value");
      assertEquals("application/vnd.example+json", acceptHeader.get(),
          "case-insensitive explicit Accept must suppress the automatic JSON Accept");
      System.out.println("PROD_P0_REST_REQUEST_HEADER_COLLISION_ACCEPT_LOOPBACK_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }
}
