package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
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

/** T3-owned production compatibility test; uses the actual REST transform, no HTTP network. */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class RestRetryExactTokenP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void restRetryMethodAndStatusListsUseExactTokensAfterHplDiskReopen(@TempDir Path tempDir)
      throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin);
    ITransformMeta meta = plugins.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    meta.getClass().getMethod("setUrl", String.class)
        .invoke(meta, "https://example.invalid/retry?river=雪");

    var descriptor = AutomaticConfigDescriptor.describe(meta.getClass());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("REST_RETRY_METHOD_AND_STATUS_LISTS_MATCH_EXACT_TOKENS"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/method", "/retryMethods/retryMethod",
        "/retryStatusCodes/retryStatusCode", "/retryTimes", "/retryDelayMs"),
        contract.keyListPointers());
    var methodList = descriptor.properties().stream()
        .filter(p -> p.key().equals("retryMethod")).findFirst().orElseThrow();
    var statusList = descriptor.properties().stream()
        .filter(p -> p.key().equals("retryStatusCode")).findFirst().orElseThrow();
    assertEquals(AutomaticConfigDescriptor.Shape.LIST, methodList.shape());
    assertEquals("retryMethods", methodList.groupKey());
    assertEquals("java.lang.String", methodList.elementJavaType());
    assertEquals(AutomaticConfigDescriptor.Shape.LIST, statusList.shape());
    assertEquals("retryStatusCodes", statusList.groupKey());
    assertEquals("java.lang.String", statusList.elementJavaType());

    var provider = new MemoryMetadataProvider();
    var variables = new Variables();
    var store = new PipelineDocumentStore(provider, variables);
    Path hpl = tempDir.resolve("rest-retry-exact-token.hpl");
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("rest-retry-exact-token");
    pipeline.addTransform(new TransformMeta("Rest", "rest-retry-node", meta));
    store.save(hpl, pipeline);

    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("rest-retry-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("rest-retry-doc",
        new PipelineConfigResource.ReadRequest("rest-retry-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("method", "GET");
    draft.put("retryTimes", 1);
    draft.put("retryDelayMs", 1);
    ArrayNode methods = draft.putObject("retryMethods").putArray("retryMethod");
    methods.add("get");
    methods.add("GET");
    methods.add("GET");
    ArrayNode statuses = draft.putObject("retryStatusCodes").putArray("retryStatusCode");
    statuses.add("503");
    statuses.add(" 503 ");
    var write = resource.write("rest-retry-doc",
        new PipelineConfigResource.WriteRequest("rest-retry-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    ITransformMeta diskMeta = reopened.findTransform("rest-retry-node").getTransform();
    JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
    JsonNode methodWire = wire.at("/retryMethods/retryMethod");
    JsonNode statusWire = wire.at("/retryStatusCodes/retryStatusCode");
    assertTrue(methodWire.isArray(), "groupKey is a real wire container");
    assertEquals(3, methodWire.size());
    assertEquals("get", methodWire.get(0).asText());
    assertEquals("GET", methodWire.get(1).asText());
    assertEquals("GET", methodWire.get(2).asText(), "duplicate LIST entries survive");
    assertTrue(statusWire.isArray());
    assertEquals("503", statusWire.get(0).asText());
    assertEquals(" 503 ", statusWire.get(1).asText());
    assertTrue(wire.at("/retryTimes").isNumber());
    assertEquals(1, wire.at("/retryTimes").intValue());
    assertTrue(wire.at("/retryDelayMs").isNumber());
    assertEquals(1L, wire.at("/retryDelayMs").longValue());

    ClassLoader loader = diskMeta.getClass().getClassLoader();
    Class<?> runtimeType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
    Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
    Object data = dataType.getConstructor().newInstance();
    dataType.getField("method").set(data, "GET");
    Object nativeRest = runtimeType.getConstructor(TransformMeta.class, diskMeta.getClass(),
        dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
        .newInstance(reopened.findTransform("rest-retry-node"), diskMeta, data, 0, reopened,
            Mockito.spy(new LocalPipelineEngine()));

    Class<?> exchangeType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.rest.Rest$HttpExchange");
    Class<?> contentType = loader.loadClass("org.apache.hc.core5.http.ContentType");
    Constructor<?> exchangeConstructor = exchangeType.getDeclaredConstructor(
        int.class, byte[].class, Map.class, contentType);
    exchangeConstructor.setAccessible(true);
    Method status = exchangeType.getDeclaredMethod("status");
    status.setAccessible(true);
    Method retry = runtimeType.getDeclaredMethod("executeWithRetry", Supplier.class);
    retry.setAccessible(true);

    // Native method is GET: lower-case 'get' is ignored, upper-case 'GET' matches.
    AtomicInteger calls = new AtomicInteger();
    Supplier<Object> response = () -> exchange(exchangeConstructor,
        calls.incrementAndGet() == 1 ? 503 : 200);
    Object result = retry.invoke(nativeRest, response);
    assertEquals(200, ((Number) status.invoke(result)).intValue());
    assertEquals(2, calls.get(), "duplicates must not add extra retry attempts");

    // Lower-case-only retry list is a valid draft, but does not match native GET.
    diskMeta.getClass().getMethod("setRetryMethods", List.class)
        .invoke(diskMeta, List.of("get"));
    calls.set(0);
    result = retry.invoke(nativeRest, response);
    assertEquals(503, ((Number) status.invoke(result)).intValue());
    assertEquals(1, calls.get());

    // Status text is also compared exactly; whitespace prevents a retry.
    diskMeta.getClass().getMethod("setRetryMethods", List.class)
        .invoke(diskMeta, List.of("GET"));
    diskMeta.getClass().getMethod("setRetryStatusCodes", List.class)
        .invoke(diskMeta, List.of(" 503 "));
    calls.set(0);
    result = retry.invoke(nativeRest, response);
    assertEquals(503, ((Number) status.invoke(result)).intValue());
    assertEquals(1, calls.get());
    System.out.println("PROD_P0_REST_RETRY_EXACT_LIST_TOKENS_DISK_REOPEN=PASS");
  }


  @Test
  void restHttpClientAutomaticallyRetries429And503WithoutMatchingHopRetryListsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    AtomicInteger count429 = new AtomicInteger();
    AtomicInteger count503 = new AtomicInteger();
    com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/status429", request -> {
      try (request) {
        int status = count429.incrementAndGet() == 1 ? 429 : 200;
        request.getResponseHeaders().add("Retry-After", "0");
        byte[] body = "rate-limit-雪".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        request.sendResponseHeaders(status, body.length);
        request.getResponseBody().write(body);
      }
    });
    server.createContext("/status503", request -> {
      try (request) {
        int status = count503.incrementAndGet() == 1 ? 503 : 200;
        request.getResponseHeaders().add("Retry-After", "0");
        byte[] body = "temporary-雪".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        request.sendResponseHeaders(status, body.length);
        request.getResponseBody().write(body);
      }
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin);
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class)
          .invoke(original, base + "/status429");

      var descriptor = AutomaticConfigDescriptor.describe(original.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "REST_HTTPCLIENT_DEFAULT_429_503_RETRIES_ARE_INDEPENDENT_OF_HOP_LISTS"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/method", "/retryMethods/retryMethod",
          "/retryStatusCodes/retryStatusCode", "/retryTimes", "/retryDelayMs"),
          contract.keyListPointers());

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("rest-httpclient-automatic-retry.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-httpclient-automatic-retry");
      pipeline.addTransform(new TransformMeta("Rest", "rest-auto-retry-node", original));
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-auto-retry-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-auto-retry-doc",
          new PipelineConfigResource.ReadRequest("rest-auto-retry-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("method", "GET");
      draft.put("retryTimes", 0);
      draft.put("retryDelayMs", 1);
      draft.putObject("retryMethods").putArray("retryMethod").add("POST");
      draft.putObject("retryStatusCodes").putArray("retryStatusCode").add("418");
      var write = resource.write("rest-auto-retry-doc",
          new PipelineConfigResource.WriteRequest("rest-auto-retry-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-auto-retry-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals("GET", wire.at("/method").asText());
      assertEquals("POST", wire.at("/retryMethods/retryMethod/0").asText());
      assertEquals("418", wire.at("/retryStatusCodes/retryStatusCode/0").asText());
      assertTrue(wire.at("/retryTimes").isNumber());
      assertEquals(0, wire.at("/retryTimes").intValue());
      assertTrue(wire.at("/retryDelayMs").isNumber());
      assertEquals(1L, wire.at("/retryDelayMs").longValue());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      Object data = dataType.getConstructor().newInstance();
      Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("rest-auto-retry-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      restType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
      assertTrue((Boolean) restType.getMethod("init").invoke(rest));
      Method invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
          String.class, java.util.LinkedHashMap.class, java.util.LinkedHashMap.class,
          java.util.LinkedHashMap.class);
      invoke.setAccessible(true);
      try {
        for (int status : new int[] {429, 503}) {
          dataType.getField("realUrl").set(data, base + "/status" + status);
          dataType.getField("method").set(data, "GET");
          Object result = invoke.invoke(rest, (Object) new Object[0], null,
              new java.util.LinkedHashMap<String, String>(),
              new java.util.LinkedHashMap<String, String>(),
              new java.util.LinkedHashMap<String, String>());
          var code = result.getClass().getDeclaredField("status");
          code.setAccessible(true);
          assertEquals(200, code.getInt(result),
              "the default HttpClient5 retry must return the second response");
        }
      } finally {
        restType.getMethod("dispose").invoke(rest);
      }
      assertEquals(2, count429.get(), "Hop-level retry lists do not include GET or 429");
      assertEquals(2, count503.get(), "HttpClient5 also retries 503 independently");
      System.out.println("PROD_P0_REST_HTTPCLIENT_DEFAULT_429_503_RETRY_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void restHttpClientMayRepeatPostOn503WithHopRetryListsExcludedAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    // HttpClient5's response-status retry is not restricted to idempotent verbs.
    // A successful POST after a 503 can therefore mean two remote write attempts.
    AtomicInteger attempts = new AtomicInteger();
    var methods = new java.util.concurrent.CopyOnWriteArrayList<String>();
    com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/post503", request -> {
      try (request) {
        methods.add(request.getRequestMethod());
        request.getRequestBody().readAllBytes();
        int status = attempts.incrementAndGet() == 1 ? 503 : 200;
        request.getResponseHeaders().add("Retry-After", "0");
        byte[] body = "post-retry-雪".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        request.sendResponseHeaders(status, body.length);
        request.getResponseBody().write(body);
      }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/post503";
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin);
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class).invoke(original, url);

      var contract = AutomaticConfigDescriptor.describe(original.getClass())
          .runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "REST_HTTPCLIENT_DEFAULT_429_503_RETRIES_ARE_INDEPENDENT_OF_HOP_LISTS"))
          .findFirst().orElseThrow();
      assertTrue(contract.explanation().contains("POST receiving 503"));
      assertEquals(List.of("/method", "/retryMethods/retryMethod",
          "/retryStatusCodes/retryStatusCode", "/retryTimes", "/retryDelayMs"),
          contract.keyListPointers());

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("rest-post-503-auto-retry.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-post-503-auto-retry");
      pipeline.addTransform(new TransformMeta("Rest", "rest-post-retry-node", original));
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-post-retry-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-post-retry-doc",
          new PipelineConfigResource.ReadRequest("rest-post-retry-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("method", "POST");
      draft.put("retryTimes", 0);
      draft.put("retryDelayMs", 1);
      draft.putObject("retryMethods").putArray("retryMethod").add("GET");
      draft.putObject("retryStatusCodes").putArray("retryStatusCode").add("418");
      var write = resource.write("rest-post-retry-doc",
          new PipelineConfigResource.WriteRequest("rest-post-retry-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-post-retry-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals("POST", wire.at("/method").asText());
      assertEquals(0, wire.at("/retryTimes").intValue());
      assertEquals("GET", wire.at("/retryMethods/retryMethod/0").asText());
      assertEquals("418", wire.at("/retryStatusCodes/retryStatusCode/0").asText());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      Object data = dataType.getConstructor().newInstance();
      Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("rest-post-retry-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      restType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
      assertTrue((Boolean) restType.getMethod("init").invoke(rest));
      try {
        dataType.getField("realUrl").set(data, url);
        dataType.getField("method").set(data, "POST");
        Method invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
            String.class, java.util.LinkedHashMap.class, java.util.LinkedHashMap.class,
            java.util.LinkedHashMap.class);
        invoke.setAccessible(true);
        Object result = invoke.invoke(rest, (Object) new Object[0], null,
            new java.util.LinkedHashMap<String, String>(),
            new java.util.LinkedHashMap<String, String>(),
            new java.util.LinkedHashMap<String, String>());
        var status = result.getClass().getDeclaredField("status");
        status.setAccessible(true);
        assertEquals(200, status.getInt(result));
      } finally {
        restType.getMethod("dispose").invoke(rest);
      }
      assertEquals(2, attempts.get(),
          "HttpClient5 repeats POST on 503 despite Hop retryTimes=0 and excluded POST");
      assertEquals(List.of("POST", "POST"), methods);
      System.out.println("PROD_P0_REST_HTTPCLIENT_POST_503_AUTO_RETRY_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void restMatchingRetryStatusWithZeroAttemptsFailsButNonmatchingStatusReturnsNativeResponse(
      @TempDir Path tempDir) throws Exception {
    // Native Rest.executeWithRetry treats a matching status as an error even
    // when retryTimes is zero; this is different from an unmatched HTTP error.
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin);
    ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
    original.setDefault();
    original.getClass().getMethod("setUrl", String.class)
        .invoke(original, "https://example.invalid/zero-retry");

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("rest-zero-retry-status.hpl");
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("rest-zero-retry-status");
    pipeline.addTransform(new TransformMeta("Rest", "rest-zero-retry-node", original));
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("rest-zero-retry-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("rest-zero-retry-doc",
        new PipelineConfigResource.ReadRequest("rest-zero-retry-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("method", "GET");
    draft.put("retryTimes", 0);
    draft.put("retryDelayMs", 1);
    draft.putObject("retryMethods").putArray("retryMethod").add("GET");
    draft.putObject("retryStatusCodes").putArray("retryStatusCode").add("418");
    var write = resource.write("rest-zero-retry-doc",
        new PipelineConfigResource.WriteRequest("rest-zero-retry-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    ITransformMeta diskMeta = reopened.findTransform("rest-zero-retry-node").getTransform();
    JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
    assertEquals(0, wire.at("/retryTimes").intValue());
    assertTrue(wire.at("/retryTimes").isNumber());
    assertEquals("GET", wire.at("/retryMethods/retryMethod/0").asText());
    assertEquals("418", wire.at("/retryStatusCodes/retryStatusCode/0").asText());

    ClassLoader loader = diskMeta.getClass().getClassLoader();
    Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
    Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
    Object data = dataType.getConstructor().newInstance();
    dataType.getField("method").set(data, "GET");
    Object nativeRest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
        dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
        .newInstance(reopened.findTransform("rest-zero-retry-node"), diskMeta, data, 0,
            reopened, Mockito.spy(new LocalPipelineEngine()));

    Class<?> exchangeType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.rest.Rest$HttpExchange");
    Class<?> contentType = loader.loadClass("org.apache.hc.core5.http.ContentType");
    Constructor<?> exchangeConstructor = exchangeType.getDeclaredConstructor(
        int.class, byte[].class, Map.class, contentType);
    exchangeConstructor.setAccessible(true);
    Method status = exchangeType.getDeclaredMethod("status");
    status.setAccessible(true);
    Method retry = restType.getDeclaredMethod("executeWithRetry", Supplier.class);
    retry.setAccessible(true);

    AtomicInteger calls = new AtomicInteger();
    Supplier<Object> always418 = () -> {
      calls.incrementAndGet();
      return exchange(exchangeConstructor, 418);
    };
    var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> retry.invoke(nativeRest, always418));
    assertInstanceOf(org.apache.hop.core.exception.HopException.class, thrown.getCause());
    assertTrue(thrown.getCause().getMessage().contains("Request failed after retries"));
    assertEquals(1, calls.get(), "zero retries still rejects the matching 418 response");

    // Excluding the same status from the list returns the original HTTP response.
    diskMeta.getClass().getMethod("setRetryStatusCodes", List.class)
        .invoke(diskMeta, List.of("419"));
    calls.set(0);
    Object result = retry.invoke(nativeRest, always418);
    assertEquals(418, ((Number) status.invoke(result)).intValue());
    assertEquals(1, calls.get());

    // With one configured retry the same 418 is retried and a 200 can succeed.
    diskMeta.getClass().getMethod("setRetryStatusCodes", List.class)
        .invoke(diskMeta, List.of("418"));
    diskMeta.getClass().getMethod("setRetryTimes", Integer.class)
        .invoke(diskMeta, 1);
    calls.set(0);
    Supplier<Object> then200 = () -> exchange(exchangeConstructor,
        calls.incrementAndGet() == 1 ? 418 : 200);
    result = retry.invoke(nativeRest, then200);
    assertEquals(200, ((Number) status.invoke(result)).intValue());
    assertEquals(2, calls.get());
    System.out.println("PROD_P0_REST_ZERO_RETRY_MATCHING_STATUS_FAILS_DISK_REOPEN=PASS");
  }

  private static Object exchange(Constructor<?> ctor, int status) {
    try {
      return ctor.newInstance(status, new byte[0], Map.of(), null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
