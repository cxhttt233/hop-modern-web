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
import java.util.concurrent.CopyOnWriteArrayList;
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

/** Native REST active-body methods with no configured payload, loopback HTTP only. */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class RestEmptyEntityP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  private record Received(String method, String length, String contentType, byte[] body) {}

  @Test
  void restBodylessPostPutPatchDeleteSendContentLengthZeroAfterRealHplDiskReopen(
      @TempDir Path tempDir) throws Exception {
    List<Received> received = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/empty", exchange -> {
      try (exchange) {
        received.add(new Received(
            exchange.getRequestMethod(),
            exchange.getRequestHeaders().getFirst("Content-Length"),
            exchange.getRequestHeaders().getFirst("Content-Type"),
            exchange.getRequestBody().readAllBytes()));
        byte[] response = "OK".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
      }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/empty";
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin);
      ITransformMeta probe = plugins.loadClass(plugin, ITransformMeta.class);
      var descriptor = AutomaticConfigDescriptor.describe(probe.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals("REST_BODYLESS_ACTIVE_METHODS_SEND_ZERO_LENGTH_ENTITY"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/method", "/applicationType", "/bodyField"),
          contract.keyListPointers());

      for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
        ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
        original.setDefault();
        original.getClass().getMethod("setUrl", String.class).invoke(original, url);
        var provider = new MemoryMetadataProvider();
        var variables = new Variables();
        var store = new PipelineDocumentStore(provider, variables);
        Path hpl = tempDir.resolve("rest-empty-" + method.toLowerCase(java.util.Locale.ROOT) + ".hpl");
        PipelineMeta pipeline = new PipelineMeta();
        pipeline.setName("rest-empty-" + method);
        pipeline.addTransform(new TransformMeta("Rest", "rest-empty-node", original));
        store.save(hpl, pipeline);
        PipelineMeta loaded = store.open(hpl);
        var documents = new PipelineDocumentRegistry();
        documents.put(new PipelineDocument("rest-empty-doc", hpl, loaded));
        var resource = new PipelineConfigResource(documents, provider);
        var read = resource.read("rest-empty-doc",
            new PipelineConfigResource.ReadRequest("rest-empty-node"));
        assertEquals(200, read.getStatus());
        ObjectNode draft = ((ObjectNode)
            ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
        draft.put("url", url);
        draft.put("method", method);
        draft.put("applicationType", "JSON");
        draft.put("bodyField", "");
        var write = resource.write("rest-empty-doc",
            new PipelineConfigResource.WriteRequest("rest-empty-node", draft));
        assertEquals(200, write.getStatus());
        store.save(hpl, loaded);

        PipelineMeta reopened = store.open(hpl);
        ITransformMeta diskMeta = reopened.findTransform("rest-empty-node").getTransform();
        JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
        assertEquals(url, wire.at("/url").asText());
        assertEquals(method, wire.at("/method").asText());
        assertEquals("JSON", wire.at("/applicationType").asText());
        assertEquals("", wire.at("/bodyField").asText());

        ClassLoader loader = diskMeta.getClass().getClassLoader();
        Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
        Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
        Object data = dataType.getConstructor().newInstance();
        Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
            dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
            .newInstance(reopened.findTransform("rest-empty-node"), diskMeta, data, 0,
                reopened, Mockito.spy(new LocalPipelineEngine()));
        restType.getMethod("setMetadataProvider",
            org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
        assertTrue((Boolean) restType.getMethod("init").invoke(rest));
        dataType.getField("inputRowMeta").set(data, new RowMeta());
        dataType.getField("realUrl").set(data, url);
        dataType.getField("method").set(data, method);
        dataType.getField("useBody").setBoolean(data, false);

        var invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
            String.class, LinkedHashMap.class, LinkedHashMap.class, LinkedHashMap.class);
        invoke.setAccessible(true);
        try {
          Object exchange = invoke.invoke(rest, new Object[0], null,
              new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
              new LinkedHashMap<String, String>());
          var status = exchange.getClass().getDeclaredField("status");
          status.setAccessible(true);
          assertEquals(200, status.getInt(exchange));
        } finally {
          restType.getMethod("dispose").invoke(rest);
        }
        assertEquals(List.of("POST", "PUT", "PATCH", "DELETE").indexOf(method) + 1,
            received.size(), "one HTTP exchange per persisted method");
        Received actual = received.get(received.size() - 1);
        assertEquals(method, actual.method());
        assertEquals("0", actual.length(),
            "native REST must explicitly send an empty entity with Content-Length: 0");
        assertArrayEquals(new byte[0], actual.body());
        assertNotNull(actual.contentType());
        assertTrue(actual.contentType().startsWith("application/json"),
            "empty request entity still carries configured JSON Content-Type");
      }
      System.out.println("PROD_P0_REST_EMPTY_ACTIVE_BODY_CONTENT_LENGTH_ZERO_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }
}
