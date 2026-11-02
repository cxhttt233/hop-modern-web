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

/** Native REST GET redirects: final response contract, loopback-only HTTP, real HPL disk reopen. */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class RestRedirectP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void restGetRelative302And307RedirectsExposeFinalBodyStatusAndHeadersAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    AtomicInteger intermediateRequests = new AtomicInteger();
    AtomicInteger finalRequests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    for (int code : new int[] {302, 307}) {
      server.createContext("/redirect" + code, exchange -> {
        try (exchange) {
          assertEquals("GET", exchange.getRequestMethod());
          intermediateRequests.incrementAndGet();
          exchange.getResponseHeaders().set("Location", "/final?from=" + code);
          exchange.getResponseHeaders().set("X-Intermediate-Only", "not-final");
          exchange.sendResponseHeaders(code, -1);
        }
      });
    }
    server.createContext("/final", exchange -> {
      try (exchange) {
        assertEquals("GET", exchange.getRequestMethod());
        finalRequests.incrementAndGet();
        String query = exchange.getRequestURI().getRawQuery();
        byte[] bytes = ("rest-redirected-雪-" + query).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.getResponseHeaders().set("X-Final-Only", "yes");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin, "pinned source-derived REST plugin required");
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class)
          .invoke(original, base + "/redirect302");

      var descriptor = AutomaticConfigDescriptor.describe(original.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "REST_GET_RELATIVE_302_307_REDIRECTS_RETURN_FINAL_RESPONSE"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/url", "/method", "/result/name", "/result/code",
          "/result/response_header"), contract.keyListPointers());

      var provider = new MemoryMetadataProvider();
      var variables = new Variables();
      var store = new PipelineDocumentStore(provider, variables);
      Path hpl = tempDir.resolve("rest-get-redirect.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-get-redirect");
      pipeline.addTransform(new TransformMeta("Rest", "rest-redirect-node", original));
      store.save(hpl, pipeline);

      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-redirect-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-redirect-doc",
          new PipelineConfigResource.ReadRequest("rest-redirect-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("url", base + "/redirect302");
      draft.put("method", "GET");
      ObjectNode result = (ObjectNode) draft.path("result");
      assertTrue(result.isObject());
      result.put("name", "响应正文_雪<&>");
      result.put("code", "最终状态");
      result.put("response_header", "最终响应头");
      result.put("response_time", "");
      result.put("binary", false);
      var write = resource.write("rest-redirect-doc",
          new PipelineConfigResource.WriteRequest("rest-redirect-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-redirect-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals(base + "/redirect302", wire.at("/url").asText());
      assertEquals("GET", wire.at("/method").asText());
      assertEquals("响应正文_雪<&>", wire.at("/result/name").asText());
      assertEquals("最终状态", wire.at("/result/code").asText());
      assertEquals("最终响应头", wire.at("/result/response_header").asText());
      assertTrue(wire.at("/result/binary").isBoolean());
      assertFalse(wire.at("/result/binary").booleanValue());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      Object data = dataType.getConstructor().newInstance();
      Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("rest-redirect-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      restType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
      assertTrue((Boolean) restType.getMethod("init").invoke(rest));
      dataType.getField("inputRowMeta").set(data, new RowMeta());
      dataType.getField("method").set(data, "GET");
      dataType.getField("binaryResult").setBoolean(data, false);
      dataType.getField("resultFieldName").set(data, "响应正文_雪<&>");
      dataType.getField("resultCodeFieldName").set(data, "最终状态");
      dataType.getField("resultResponseFieldName").set(data, null);
      dataType.getField("resultHeaderFieldName").set(data, "最终响应头");
      var invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
          String.class, LinkedHashMap.class, LinkedHashMap.class, LinkedHashMap.class);
      invoke.setAccessible(true);
      ObjectMapper mapper = new ObjectMapper();
      try {
        for (int code : new int[] {302, 307}) {
          dataType.getField("realUrl").set(data, base + "/redirect" + code);
          Object exchange = invoke.invoke(rest, new Object[0], null,
              new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
              new LinkedHashMap<String, String>());
          var status = exchange.getClass().getDeclaredField("status");
          status.setAccessible(true);
          assertEquals(200, status.getInt(exchange),
              "REST result must report final HTTP status");
          var body = exchange.getClass().getDeclaredField("body");
          body.setAccessible(true);
          assertEquals("rest-redirected-雪-from=" + code, body.get(exchange));
          var assemble = restType.getDeclaredMethod("assembleResultRow",
              Object[].class, exchange.getClass());
          assemble.setAccessible(true);
          Object[] row = (Object[]) assemble.invoke(rest, new Object[0], exchange);
          assertEquals("rest-redirected-雪-from=" + code, row[0]);
          assertEquals(200L, row[1]);
          JsonNode headers = mapper.readTree((String) row[2]);
          boolean foundFinal = false;
          for (var keys = headers.fieldNames(); keys.hasNext();) {
            String key = keys.next();
            assertFalse(key.equalsIgnoreCase("X-Intermediate-Only"),
                "intermediate redirect headers must not leak into final output");
            if (key.equalsIgnoreCase("X-Final-Only")) {
              foundFinal = true;
              assertEquals("yes", headers.path(key).asText());
            }
          }
          assertTrue(foundFinal, "final response header must be present");
        }
      } finally {
        restType.getMethod("dispose").invoke(rest);
      }
      assertEquals(2, intermediateRequests.get());
      assertEquals(2, finalRequests.get());
      System.out.println("PROD_P0_REST_REDIRECT_FINAL_RESPONSE_LOOPBACK_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }

  /**
   * Static REST Basic credentials are scoped to the original request origin.
   * Unlike same-origin 302/307, native HttpClient5 does not follow a redirect
   * to a different loopback port when the original request is authenticated.
   * Verify this at the HTTP boundary, not merely through an auth-origin check.
   */
  @Test
  void restStaticBasicAuthorizationDoesNotFollowCrossOrigin302Or307AfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    String expectedAuth = "Basic " + java.util.Base64.getEncoder().encodeToString(
        "fixture-user:fixture-password".getBytes(StandardCharsets.UTF_8));
    AtomicInteger sourceAuthorized = new AtomicInteger();
    AtomicInteger sourceRequests = new AtomicInteger();
    AtomicInteger targetRequests = new AtomicInteger();
    AtomicInteger targetAuthHeaders = new AtomicInteger();
    HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    target.createContext("/final", exchange -> {
      try (exchange) {
        targetRequests.incrementAndGet();
        if (exchange.getRequestHeaders().getFirst("Authorization") != null) {
          targetAuthHeaders.incrementAndGet();
        }
        byte[] body = "redirect-target-雪".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
    });
    HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String targetUrl = "http://127.0.0.1:" + target.getAddress().getPort() + "/final";
    source.createContext("/redirect", exchange -> {
      try (exchange) {
        sourceRequests.incrementAndGet();
        if (expectedAuth.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
          sourceAuthorized.incrementAndGet();
        }
        int status = exchange.getRequestURI().getPath().endsWith("307") ? 307 : 302;
        exchange.getResponseHeaders().set("Location", targetUrl);
        exchange.sendResponseHeaders(status, -1);
      }
    });
    target.start();
    source.start();
    try {
      String sourceBase = "http://127.0.0.1:" + source.getAddress().getPort();
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin, "pinned REST plugin required");
      ITransformMeta meta = plugins.loadClass(plugin, ITransformMeta.class);
      meta.setDefault();
      meta.getClass().getMethod("setUrl", String.class)
          .invoke(meta, sourceBase + "/redirect302");
      meta.getClass().getMethod("setHttpLogin", String.class)
          .invoke(meta, "fixture-user");
      meta.getClass().getMethod("setHttpPassword", String.class)
          .invoke(meta, "fixture-password");

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("rest-cross-origin-redirect.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-cross-origin-redirect");
      pipeline.addTransform(new TransformMeta("Rest", "rest-redirect-node", meta));
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-cross-origin-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-cross-origin-doc",
          new PipelineConfigResource.ReadRequest("rest-redirect-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("url", sourceBase + "/redirect302");
      draft.put("method", "GET");
      draft.put("non_preemptive_basic_auth", false);
      ObjectNode result = (ObjectNode) draft.path("result");
      result.put("name", "响应正文_雪<&>");
      result.put("code", "最终状态");
      result.put("response_header", "");
      result.put("response_time", "");
      result.put("binary", false);
      var write = resource.write("rest-cross-origin-doc",
          new PipelineConfigResource.WriteRequest("rest-redirect-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-redirect-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals(sourceBase + "/redirect302", wire.at("/url").asText());
      assertEquals("GET", wire.at("/method").asText());
      assertTrue(wire.at("/non_preemptive_basic_auth").isBoolean());
      assertFalse(wire.at("/non_preemptive_basic_auth").booleanValue());
      assertEquals("响应正文_雪<&>", wire.at("/result/name").asText());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      Object data = dataType.getConstructor().newInstance();
      Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("rest-redirect-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      restType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
      assertTrue((Boolean) restType.getMethod("init").invoke(rest));
      dataType.getField("inputRowMeta").set(data, new RowMeta());
      dataType.getField("method").set(data, "GET");
      dataType.getField("binaryResult").setBoolean(data, false);
      dataType.getField("resultFieldName").set(data, "响应正文_雪<&>");
      dataType.getField("resultCodeFieldName").set(data, "最终状态");
      dataType.getField("resultResponseFieldName").set(data, null);
      dataType.getField("resultHeaderFieldName").set(data, null);
      var invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
          String.class, LinkedHashMap.class, LinkedHashMap.class, LinkedHashMap.class);
      invoke.setAccessible(true);
      try {
        for (int status : new int[] {302, 307}) {
          dataType.getField("realUrl").set(data, sourceBase + "/redirect" + status);
          Object exchange = invoke.invoke(rest, new Object[0], null,
              new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
              new LinkedHashMap<String, String>());
          var finalStatus = exchange.getClass().getDeclaredField("status");
          finalStatus.setAccessible(true);
          assertEquals(status, finalStatus.getInt(exchange),
              "native REST must expose the cross-origin redirect rather than follow it");
          var finalBody = exchange.getClass().getDeclaredField("body");
          finalBody.setAccessible(true);
          assertNotEquals("redirect-target-雪", finalBody.get(exchange),
              "target origin must not have supplied a response body");
        }
      } finally {
        restType.getMethod("dispose").invoke(rest);
      }
      assertEquals(2, sourceRequests.get(), "both redirects must be exercised");
      assertEquals(2, sourceAuthorized.get(), "Basic credentials must reach original origin");
      assertEquals(0, targetRequests.get(), "cross-origin redirects must not be followed");
      assertEquals(0, targetAuthHeaders.get(),
          "Authorization must not reach the other origin");
      System.out.println("PROD_P0_REST_CROSS_ORIGIN_REDIRECT_NOT_FOLLOWED_BASIC_SCOPE_HPL=PASS");
    } finally {
      source.stop(0);
      target.stop(0);
    }
  }

}
