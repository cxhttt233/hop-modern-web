package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
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

/** Native Http GET gzip compatibility against isolated 127.0.0.1 only. */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class HttpGzipResponseP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void httpGzipResponseDecodesAndCountsDecompressedBytesAfterRealHplDiskReopen(
      @TempDir Path tempDir) throws Exception {
    String body = "黄河数据<&>雪".repeat(80) + "-完成";
    byte[] utf8 = body.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream compressedBuffer = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(compressedBuffer)) {
      gzip.write(utf8);
    }
    byte[] compressed = compressedBuffer.toByteArray();
    assertTrue(compressed.length < utf8.length, "fixture must have distinct wire/entity sizes");
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/gzip", exchange -> {
      try (exchange) {
        requests.incrementAndGet();
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.getResponseHeaders().add("Content-Encoding", "gzip");
        exchange.sendResponseHeaders(200, compressed.length);
        exchange.getResponseBody().write(compressed);
      }
    });
    server.createContext("/identity", exchange -> {
      try (exchange) {
        requests.incrementAndGet();
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, utf8.length);
        exchange.getResponseBody().write(utf8);
      }
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Http");
      assertNotNull(plugin);
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class)
          .invoke(original, base + "/gzip");

      var descriptor = AutomaticConfigDescriptor.describe(original.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals(
              "HTTP_GZIP_RESPONSE_DECODES_BEFORE_CONFIGURED_TEXT_AND_BYTE_COUNT"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/url", "/encoding", "/result/name", "/result/code"),
          contract.keyListPointers());

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("http-gzip-response.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("http-gzip-response");
      pipeline.addTransform(new TransformMeta("Http", "http-gzip-node", original));
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("http-gzip-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("http-gzip-doc",
          new PipelineConfigResource.ReadRequest("http-gzip-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("url", base + "/gzip");
      draft.put("encoding", "UTF-8");
      ObjectNode result = (ObjectNode) draft.path("result");
      result.put("name", "响应正文_雪");
      result.put("code", "状态码");
      var write = resource.write("http-gzip-doc",
          new PipelineConfigResource.WriteRequest("http-gzip-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("http-gzip-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals(base + "/gzip", wire.at("/url").asText());
      assertEquals("UTF-8", wire.at("/encoding").asText());
      assertEquals("响应正文_雪", wire.at("/result/name").asText());
      assertEquals("状态码", wire.at("/result/code").asText());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> httpType = loader.loadClass("org.apache.hop.pipeline.transforms.http.Http");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.http.HttpData");
      Object data = dataType.getConstructor().newInstance();
      Object nativeHttp = httpType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("http-gzip-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      httpType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class)
          .invoke(nativeHttp, provider);
      assertTrue((Boolean) httpType.getMethod("init").invoke(nativeHttp));
      dataType.getField("argNrs").set(data, new int[0]);
      Method call = httpType.getDeclaredMethod("callHttpService",
          org.apache.hop.core.row.IRowMeta.class, Object[].class);
      call.setAccessible(true);
      try {
        dataType.getField("realUrl").set(data, base + "/gzip");
        Object[] gzipped = (Object[]) call.invoke(nativeHttp, new RowMeta(),
            (Object) new Object[0]);
        assertEquals(body, gzipped[0], "native HttpClient must decompress gzip before decoding");
        assertEquals(200L, gzipped[1]);
        assertEquals(utf8.length, dataType.getField("lastHttpResponseBodyBytes").getInt(data),
            "native byte counter measures decompressed entity, not wire Content-Length");

        dataType.getField("realUrl").set(data, base + "/identity");
        Object[] identity = (Object[]) call.invoke(nativeHttp, new RowMeta(),
            (Object) new Object[0]);
        assertEquals(body, identity[0]);
        assertEquals(200L, identity[1]);
        assertEquals(utf8.length, dataType.getField("lastHttpResponseBodyBytes").getInt(data));
      } finally {
        httpType.getMethod("dispose").invoke(nativeHttp);
      }
      assertEquals(2, requests.get(), "both requests must stay on isolated loopback");
      System.out.println("PROD_P0_HTTP_GZIP_DECOMPRESSED_BYTES_LOOPBACK_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }
}
