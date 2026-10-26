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

  private static Object exchange(Constructor<?> ctor, int status) {
    try {
      return ctor.newInstance(status, new byte[0], Map.of(), null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
