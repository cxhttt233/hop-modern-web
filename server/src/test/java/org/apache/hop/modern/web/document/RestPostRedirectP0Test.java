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

/**
 * Native REST POST redirect semantics are not the same as the GET redirect contract.
 * All HTTP traffic is loopback-only, with a real HPL disk reopen.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class RestPostRedirectP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  private record Seen(String path, String method, byte[] body) {}

  @Test
  void restPost302ChangesToGetBut307PreservesPostAfterRealHplEdit(
      @TempDir Path tempDir) throws Exception {
    List<Seen> seen = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    for (int code : new int[] {302, 307}) {
      server.createContext("/post-redirect-" + code, exchange -> {
        try (exchange) {
          seen.add(new Seen("/post-redirect-" + code, exchange.getRequestMethod(),
              exchange.getRequestBody().readAllBytes()));
          exchange.getResponseHeaders().set("Location", "/post-final-" + code);
          exchange.sendResponseHeaders(code, -1);
        }
      });
      server.createContext("/post-final-" + code, exchange -> {
        try (exchange) {
          seen.add(new Seen("/post-final-" + code, exchange.getRequestMethod(),
              exchange.getRequestBody().readAllBytes()));
          byte[] response = ("final-" + code + "-" + exchange.getRequestMethod())
              .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
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
          .invoke(original, base + "/post-redirect-302");

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("rest-post-redirect.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-post-redirect");
      pipeline.addTransform(new TransformMeta("Rest", "rest-post-node", original));
      store.save(hpl, pipeline);

      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-post-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-post-doc",
          new PipelineConfigResource.ReadRequest("rest-post-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("url", base + "/post-redirect-302");
      draft.put("method", "POST");
      draft.put("applicationType", "JSON");
      draft.put("bodyField", "");
      ObjectNode result = (ObjectNode) draft.path("result");
      result.put("name", "响应正文");
      result.put("code", "最终状态");
      result.put("binary", false);
      var write = resource.write("rest-post-doc",
          new PipelineConfigResource.WriteRequest("rest-post-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-post-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals("POST", wire.at("/method").asText());
      assertEquals("JSON", wire.at("/applicationType").asText());
      assertEquals("", wire.at("/bodyField").asText());
      assertEquals("响应正文", wire.at("/result/name").asText());

      var contract = AutomaticConfigDescriptor.describe(diskMeta.getClass())
          .runtimePreconditions().stream()
          .filter(p -> p.code().equals("REST_POST_302_VS_307_REDIRECT_METHOD_SEMANTICS"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/url", "/method", "/bodyField", "/result/name", "/result/code"),
          contract.keyListPointers());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      Object data = dataType.getConstructor().newInstance();
      Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("rest-post-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      restType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
      assertTrue((Boolean) restType.getMethod("init").invoke(rest));
      dataType.getField("inputRowMeta").set(data, new RowMeta());
      dataType.getField("method").set(data, "POST");
      dataType.getField("useBody").setBoolean(data, false);
      var invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
          String.class, LinkedHashMap.class, LinkedHashMap.class, LinkedHashMap.class);
      invoke.setAccessible(true);
      try {
        for (int code : new int[] {302, 307}) {
          dataType.getField("realUrl").set(data, base + "/post-redirect-" + code);
          Object exchange = invoke.invoke(rest, new Object[0], null,
              new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
              new LinkedHashMap<String, String>());
          var status = exchange.getClass().getDeclaredField("status");
          status.setAccessible(true);
          assertEquals(200, status.getInt(exchange));
          var body = exchange.getClass().getDeclaredField("body");
          body.setAccessible(true);
          assertEquals("final-" + code + "-" + (code == 302 ? "GET" : "POST"),
              body.get(exchange));
        }
      } finally {
        restType.getMethod("dispose").invoke(rest);
      }
      assertEquals(4, seen.size(), "each redirect must reach exactly one final endpoint");
      assertEquals("POST", seen.get(0).method());
      assertEquals("GET", seen.get(1).method(),
          "302 redirects a POST as a GET, without replaying its request body");
      assertEquals(0, seen.get(1).body().length);
      assertEquals("POST", seen.get(2).method());
      assertEquals("POST", seen.get(3).method(),
          "307 preserves POST when following a same-origin relative redirect");
      assertEquals(0, seen.get(3).body().length);
      System.out.println("PROD_P0_REST_POST_302_GET_307_POST_REDIRECT_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }
}
