package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

/** Pinned Hop Http GET redirect behavior; only isolated 127.0.0.1 traffic is permitted. */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class HttpRedirectP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void httpRelative302And307RedirectsReturnFinalResponseAfterHplDiskReopen(
      @TempDir Path tempDir) throws Exception {
    AtomicInteger redirects = new AtomicInteger();
    AtomicInteger finals = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/redirect302", exchange -> {
      try (exchange) {
        redirects.incrementAndGet();
        exchange.getResponseHeaders().add("Location", "/final?from=302");
        exchange.getResponseHeaders().add("X-Intermediate-Only", "not-final");
        exchange.sendResponseHeaders(302, -1);
      }
    });
    server.createContext("/redirect307", exchange -> {
      try (exchange) {
        redirects.incrementAndGet();
        exchange.getResponseHeaders().add("Location", "/final?from=307");
        exchange.getResponseHeaders().add("X-Intermediate-Only", "not-final");
        exchange.sendResponseHeaders(307, -1);
      }
    });
    server.createContext("/final", exchange -> {
      try (exchange) {
        finals.incrementAndGet();
        String source = exchange.getRequestURI().getRawQuery();
        byte[] bytes = ("redirected-雪-" + source).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.getResponseHeaders().add("X-Final-Only", "yes");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.start();
    try {
      String localUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Http");
      assertNotNull(plugin);
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class)
          .invoke(original, localUrl + "redirect302");

      var descriptor = AutomaticConfigDescriptor.describe(original.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals("HTTP_GET_REDIRECTS_RETURN_FINAL_STATUS_BODY_AND_HEADERS"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/url", "/encoding", "/result/name", "/result/code",
          "/result/response_header"), contract.keyListPointers());

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("http-redirect-compat.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("http-redirect-compat");
      pipeline.addTransform(new TransformMeta("Http", "http-redirect-node", original));
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("http-redirect-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("http-redirect-doc",
          new PipelineConfigResource.ReadRequest("http-redirect-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("url", localUrl + "redirect302");
      draft.put("encoding", "UTF-8");
      ObjectNode result = (ObjectNode) draft.path("result");
      result.put("name", "响应正文_雪");
      result.put("code", "响应状态");
      result.put("response_header", "最终响应头");
      var write = resource.write("http-redirect-doc",
          new PipelineConfigResource.WriteRequest("http-redirect-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("http-redirect-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals(localUrl + "redirect302", wire.at("/url").asText());
      assertEquals("UTF-8", wire.at("/encoding").asText());
      assertEquals("响应正文_雪", wire.at("/result/name").asText());
      assertEquals("响应状态", wire.at("/result/code").asText());
      assertEquals("最终响应头", wire.at("/result/response_header").asText());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> httpType = loader.loadClass("org.apache.hop.pipeline.transforms.http.Http");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.http.HttpData");
      Object data = dataType.getConstructor().newInstance();
      Object nativeHttp = httpType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("http-redirect-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      httpType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class)
          .invoke(nativeHttp, provider);
      assertTrue((Boolean) httpType.getMethod("init").invoke(nativeHttp));
      dataType.getField("argNrs").set(data, new int[0]);
      Method call = httpType.getDeclaredMethod("callHttpService",
          org.apache.hop.core.row.IRowMeta.class, Object[].class);
      call.setAccessible(true);
      ObjectMapper mapper = new ObjectMapper();
      try {
        for (int status : new int[] {302, 307}) {
          dataType.getField("realUrl").set(data, localUrl + "redirect" + status);
          Object[] output = (Object[]) call.invoke(nativeHttp, new RowMeta(),
              (Object) new Object[0]);
          assertEquals("redirected-雪-from=" + status, output[0]);
          assertEquals(200L, output[1],
              "the status column must describe the final response, not the redirect");
          JsonNode headers = mapper.readTree((String) output[2]);
          String finalHeaderKey = null;
          for (var names = headers.fieldNames(); names.hasNext();) {
            String key = names.next();
            if (key.equalsIgnoreCase("X-Final-Only")) finalHeaderKey = key;
            assertFalse(key.equalsIgnoreCase("X-Intermediate-Only"),
                "headers from the intermediate 3xx must not leak into final output");
          }
          assertNotNull(finalHeaderKey, "final response header must be present");
          assertEquals("yes", headers.path(finalHeaderKey).asText());
        }
      } finally {
        httpType.getMethod("dispose").invoke(nativeHttp);
      }
      assertEquals(2, redirects.get());
      assertEquals(2, finals.get());
      System.out.println("PROD_P0_HTTP_REDIRECT_FINAL_RESPONSE_LOOPBACK_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }
}
