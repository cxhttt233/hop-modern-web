package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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

/** Native REST query LIST wire order and duplicate names, loopback only. */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class RestRepeatedQueryP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void restDuplicateQueryNamesSurviveRealHplEditAndReachLoopbackInOrder(
      @TempDir Path tempDir) throws Exception {
    AtomicReference<String> receivedRawQuery = new AtomicReference<>();
    AtomicReference<String> receivedMethod = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/query", exchange -> {
      try (exchange) {
        receivedRawQuery.set(exchange.getRequestURI().getRawQuery());
        receivedMethod.set(exchange.getRequestMethod());
        byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort()
          + "/query?tag=base&fixed=1";
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin);
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class).invoke(original, url);

      var descriptor = AutomaticConfigDescriptor.describe(original.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "REST_REPEATED_QUERY_PARAMETER_NAMES_PRESERVE_ORDER_AND_VALUES"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/url", "/parameters/parameter"), contract.keyListPointers());
      var parameterProperty = descriptor.properties().stream()
          .filter(p -> p.key().equals("parameter")).findFirst().orElseThrow();
      assertEquals(AutomaticConfigDescriptor.Shape.LIST, parameterProperty.shape());
      assertEquals("parameters", parameterProperty.groupKey());
      assertEquals(List.of("field", "name"),
          parameterProperty.elementProperties().stream().map(p -> p.key()).toList());

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("rest-duplicate-query.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-duplicate-query");
      pipeline.addTransform(new TransformMeta("Rest", "rest-query-node", original));
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-query-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-query-doc",
          new PipelineConfigResource.ReadRequest("rest-query-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("method", "GET");
      draft.put("url", url);
      ArrayNode parameters = draft.putObject("parameters").putArray("parameter");
      parameters.addObject().put("field", "上游<&>甲").put("name", "tag");
      parameters.addObject().put("field", "second_雪").put("name", "tag");
      var write = resource.write("rest-query-doc",
          new PipelineConfigResource.WriteRequest("rest-query-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-query-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals("GET", wire.at("/method").asText());
      assertEquals(url, wire.at("/url").asText());
      assertTrue(wire.at("/parameters/parameter").isArray());
      assertEquals(2, wire.at("/parameters/parameter").size());
      assertEquals("上游<&>甲", wire.at("/parameters/parameter/0/field").asText());
      assertEquals("tag", wire.at("/parameters/parameter/0/name").asText());
      assertEquals("second_雪", wire.at("/parameters/parameter/1/field").asText());
      assertEquals("tag", wire.at("/parameters/parameter/1/name").asText());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      Object data = dataType.getConstructor().newInstance();
      Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("rest-query-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      restType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
      assertTrue((Boolean) restType.getMethod("init").invoke(rest));
      var rowMeta = new RowMeta();
      rowMeta.addValueMeta(new ValueMetaString("上游<&>甲"));
      rowMeta.addValueMeta(new ValueMetaString("second_雪"));
      dataType.getField("inputRowMeta").set(data, rowMeta);
      dataType.getField("realUrl").set(data, url);
      dataType.getField("method").set(data, "GET");
      dataType.getField("useParams").setBoolean(data, true);
      dataType.getField("nrParams").setInt(data, 2);
      dataType.getField("paramNames").set(data, new String[] {"tag", "tag"});
      dataType.getField("indexOfParamFields").set(data, new int[] {0, 1});
      Method invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
          String.class, LinkedHashMap.class, LinkedHashMap.class, LinkedHashMap.class);
      invoke.setAccessible(true);
      try {
        Object exchange = invoke.invoke(rest, new Object[] {"黄河<&>雪", "a+b 空"}, null,
            new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
            new LinkedHashMap<String, String>());
        var status = exchange.getClass().getDeclaredField("status");
        status.setAccessible(true);
        assertEquals(200, status.getInt(exchange));
      } finally {
        restType.getMethod("dispose").invoke(rest);
      }
      assertEquals("GET", receivedMethod.get());
      assertNotNull(receivedRawQuery.get());
      List<String> decodedPairs = new ArrayList<>();
      for (String pair : receivedRawQuery.get().split("&")) {
        int eq = pair.indexOf('=');
        assertTrue(eq > 0, "query pairs must contain a nonempty name and =");
        decodedPairs.add(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8)
            + "=" + URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
      assertEquals(List.of("tag=base", "fixed=1", "tag=黄河<&>雪", "tag=a+b 空"),
          decodedPairs, "existing URL pairs precede ordered duplicate LIST entries");
      assertTrue(receivedRawQuery.get().toUpperCase(java.util.Locale.ROOT).contains("%2B"),
          "literal plus must be percent-encoded, not converted into a space");
      System.out.println("PROD_P0_REST_DUPLICATE_QUERY_ORDER_LOOPBACK_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void restOrderedMatrixParametersUsePathEncodingBeforeQueryAfterRealHplReopen(
      @TempDir Path tempDir) throws Exception {
    AtomicReference<String> receivedPath = new AtomicReference<>();
    AtomicReference<String> receivedQuery = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/matrix", exchange -> {
      try (exchange) {
        receivedPath.set(exchange.getRequestURI().getRawPath());
        receivedQuery.set(exchange.getRequestURI().getRawQuery());
        byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort()
          + "/matrix?existing=1";
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin);
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class).invoke(original, url);
      var descriptor = AutomaticConfigDescriptor.describe(original.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "REST_MATRIX_PARAMETERS_USE_PATH_ENCODING_BEFORE_QUERY"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/url", "/matrixParameters/matrixParameter",
          "/parameters/parameter"), contract.keyListPointers());
      var matrixDescriptor = descriptor.properties().stream()
          .filter(p -> p.key().equals("matrixParameter")).findFirst().orElseThrow();
      assertEquals(AutomaticConfigDescriptor.Shape.LIST, matrixDescriptor.shape());
      assertEquals("matrixParameters", matrixDescriptor.groupKey());
      assertEquals(List.of("field", "name"),
          matrixDescriptor.elementProperties().stream().map(p -> p.key()).toList());

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("rest-matrix-ordered.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-matrix-ordered");
      pipeline.addTransform(new TransformMeta("Rest", "matrix-node", original));
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("matrix-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("matrix-doc",
          new PipelineConfigResource.ReadRequest("matrix-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("method", "GET");
      draft.put("url", url);
      ArrayNode matrix = draft.putObject("matrixParameters").putArray("matrixParameter");
      matrix.addObject().put("field", "路径甲<&>").put("name", "tag");
      matrix.addObject().put("field", "路径乙_雪").put("name", "tag");
      ArrayNode query = draft.putObject("parameters").putArray("parameter");
      query.addObject().put("field", "查询_甲").put("name", "q");
      var write = resource.write("matrix-doc",
          new PipelineConfigResource.WriteRequest("matrix-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("matrix-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals("GET", wire.at("/method").asText());
      assertEquals(url, wire.at("/url").asText());
      assertEquals(2, wire.at("/matrixParameters/matrixParameter").size());
      assertEquals("路径甲<&>", wire.at("/matrixParameters/matrixParameter/0/field").asText());
      assertEquals("tag", wire.at("/matrixParameters/matrixParameter/0/name").asText());
      assertEquals("路径乙_雪", wire.at("/matrixParameters/matrixParameter/1/field").asText());
      assertEquals("tag", wire.at("/matrixParameters/matrixParameter/1/name").asText());
      assertEquals("查询_甲", wire.at("/parameters/parameter/0/field").asText());
      assertEquals("q", wire.at("/parameters/parameter/0/name").asText());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      Object data = dataType.getConstructor().newInstance();
      Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("matrix-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      restType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
      assertTrue((Boolean) restType.getMethod("init").invoke(rest));
      var rowMeta = new RowMeta();
      rowMeta.addValueMeta(new ValueMetaString("路径甲<&>"));
      rowMeta.addValueMeta(new ValueMetaString("路径乙_雪"));
      rowMeta.addValueMeta(new ValueMetaString("查询_甲"));
      dataType.getField("inputRowMeta").set(data, rowMeta);
      dataType.getField("realUrl").set(data, url);
      dataType.getField("method").set(data, "GET");
      dataType.getField("useMatrixParams").setBoolean(data, true);
      dataType.getField("nrMatrixParams").setInt(data, 2);
      dataType.getField("matrixParamNames").set(data, new String[] {"tag", "tag"});
      dataType.getField("indexOfMatrixParamFields").set(data, new int[] {0, 1});
      dataType.getField("useParams").setBoolean(data, true);
      dataType.getField("nrParams").setInt(data, 1);
      dataType.getField("paramNames").set(data, new String[] {"q"});
      dataType.getField("indexOfParamFields").set(data, new int[] {2});
      Method invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
          String.class, LinkedHashMap.class, LinkedHashMap.class, LinkedHashMap.class);
      invoke.setAccessible(true);
      try {
        Object exchange = invoke.invoke(rest,
            new Object[] {"黄河 +雪", "a+b/雪", "查询 +空"}, null,
            new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
            new LinkedHashMap<String, String>());
        var status = exchange.getClass().getDeclaredField("status");
        status.setAccessible(true);
        assertEquals(200, status.getInt(exchange));
      } finally {
        restType.getMethod("dispose").invoke(rest);
      }

      String rawPath = receivedPath.get();
      assertNotNull(rawPath, "native REST request must reach loopback server");
      assertTrue(rawPath.startsWith("/matrix;"), rawPath);
      String[] matrixPairs = rawPath.substring("/matrix;".length()).split(";");
      assertEquals(2, matrixPairs.length, rawPath);
      assertEquals("tag=黄河 +雪", URLDecoder.decode(matrixPairs[0], StandardCharsets.UTF_8));
      assertEquals("tag=a+b/雪", URLDecoder.decode(matrixPairs[1], StandardCharsets.UTF_8));
      assertTrue(rawPath.contains("%20"), rawPath);
      assertTrue(rawPath.toUpperCase(java.util.Locale.ROOT).contains("%2B"), rawPath);
      assertTrue(rawPath.toUpperCase(java.util.Locale.ROOT).contains("%2F"), rawPath);
      String rawQuery = receivedQuery.get();
      assertNotNull(rawQuery);
      List<String> queryPairs = new ArrayList<>();
      for (String pair : rawQuery.split("&")) {
        int eq = pair.indexOf('=');
        assertTrue(eq > 0);
        queryPairs.add(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8)
            + "=" + URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
      assertEquals(List.of("existing=1", "q=查询 +空"), queryPairs);
      System.out.println("PROD_P0_REST_MATRIX_ORDERED_PATH_QUERY_LOOPBACK_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }
}
