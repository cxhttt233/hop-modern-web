package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaBinary;
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

/**
 * Pinned production REST plugin, isolated 127.0.0.1 HTTP exchange only.
 * Verifies raw binary request bytes and the native restricted Content-Length rule.
 * This is not a remote-network or complete pipeline-execution test.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class RestBinaryRequestBodyP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void restBinaryBodyPreservesRawBytesAndIgnoresConfiguredContentLengthAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    AtomicReference<byte[]> received = new AtomicReference<>();
    AtomicReference<String> lengthHeader = new AtomicReference<>();
    AtomicReference<String> contentTypeHeader = new AtomicReference<>();
    AtomicReference<String> receivedMethod = new AtomicReference<>();
    HttpServer server = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/binary", exchange -> {
      try (exchange) {
        receivedMethod.set(exchange.getRequestMethod());
        lengthHeader.set(exchange.getRequestHeaders().getFirst("Content-Length"));
        contentTypeHeader.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        received.set(exchange.getRequestBody().readAllBytes());
        byte[] response = "OK".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
      }
    });
    server.start();
    try {
      String localUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress()
          + ":" + server.getAddress().getPort() + "/binary";
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
      assertNotNull(plugin);
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class).invoke(original, localUrl);

      var descriptor = AutomaticConfigDescriptor.describe(original.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "REST_BINARY_REQUEST_BODY_PRESERVES_BYTES_AND_CLIENT_OWNS_CONTENT_LENGTH"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/method", "/applicationType", "/bodyField", "/headers/header"),
          contract.keyListPointers());
      var headersProperty = descriptor.properties().stream()
          .filter(p -> p.key().equals("header")).findFirst().orElseThrow();
      assertEquals(AutomaticConfigDescriptor.Shape.LIST, headersProperty.shape());
      assertEquals("headers", headersProperty.groupKey());
      assertEquals(AutomaticConfigDescriptor.Shape.STRING,
          descriptor.properties().stream().filter(p -> p.key().equals("bodyField"))
              .findFirst().orElseThrow().shape());

      var provider = new MemoryMetadataProvider();
      var variables = new Variables();
      var store = new PipelineDocumentStore(provider, variables);
      Path hpl = tempDir.resolve("rest-binary-request.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("rest-binary-request");
      pipeline.addTransform(new TransformMeta("Rest", "rest-binary-node", original));
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-binary-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-binary-doc",
          new PipelineConfigResource.ReadRequest("rest-binary-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("method", "POST");
      draft.put("applicationType", "OCTET STREAM");
      draft.put("bodyField", "payload_雪");
      draft.putObject("headers").putArray("header").addObject()
          .put("field", "length_hint").put("name", "cOnTeNt-LeNgTh");
      var write = resource.write("rest-binary-doc",
          new PipelineConfigResource.WriteRequest("rest-binary-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-binary-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals("POST", wire.at("/method").asText());
      assertEquals("OCTET STREAM", wire.at("/applicationType").asText());
      assertEquals("payload_雪", wire.at("/bodyField").asText());
      assertEquals("length_hint", wire.at("/headers/header/0/field").asText());
      assertEquals("cOnTeNt-LeNgTh", wire.at("/headers/header/0/name").asText());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> restType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      Object data = dataType.getConstructor().newInstance();
      Object rest = restType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("rest-binary-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      restType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class).invoke(rest, provider);
      assertTrue((Boolean) restType.getMethod("init").invoke(rest));

      var rowMeta = new RowMeta();
      rowMeta.addValueMeta(new ValueMetaBinary("payload_雪"));
      rowMeta.addValueMeta(new ValueMetaString("length_hint"));
      dataType.getField("inputRowMeta").set(data, rowMeta);
      dataType.getField("realUrl").set(data, localUrl);
      dataType.getField("useBody").setBoolean(data, true);
      dataType.getField("binaryBody").setBoolean(data, true);
      dataType.getField("indexOfBodyField").setInt(data, 0);
      dataType.getField("useHeaders").setBoolean(data, true);
      dataType.getField("nrheader").setInt(data, 1);
      dataType.getField("headerNames").set(data, new String[]{"cOnTeNt-LeNgTh"});
      dataType.getField("indexOfHeaderFields").set(data, new int[]{1});

      byte[] payload = new byte[]{0, (byte) 0xFF, (byte) 0x80, (byte) 0xC3, 0x28, 0x0A};
      var invoke = restType.getDeclaredMethod("invokeRestExchange", Object[].class,
          String.class, LinkedHashMap.class, LinkedHashMap.class, LinkedHashMap.class);
      invoke.setAccessible(true);
      try {
        Object result = invoke.invoke(rest, new Object[]{payload, "99999"}, null,
            new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
            new LinkedHashMap<String, String>());
        var status = result.getClass().getDeclaredField("status");
        status.setAccessible(true);
        assertEquals(200, status.getInt(result));
      } finally {
        restType.getMethod("dispose").invoke(rest);
      }
      assertEquals("POST", receivedMethod.get());
      assertArrayEquals(payload, received.get(),
          "native REST must transmit invalid-UTF8 binary bytes without text transcoding");
      assertEquals(Integer.toString(payload.length), lengthHeader.get(),
          "HTTP client must own Content-Length instead of using the input field value 99999");
      assertTrue(contentTypeHeader.get().startsWith("application/octet-stream"),
          "configured OCTET STREAM must produce an octet-stream entity");
      assertFalse(Arrays.equals(payload, "99999".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      System.out.println("PROD_P0_REST_BINARY_REQUEST_LENGTH_LOCAL_LOOPBACK_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }
}
