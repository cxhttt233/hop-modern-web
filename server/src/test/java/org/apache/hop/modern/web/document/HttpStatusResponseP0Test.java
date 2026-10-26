package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.InvocationTargetException;
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
import org.apache.hop.core.row.IRowMeta;
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
 * Native pinned Hop Http transform compatibility against isolated 127.0.0.1 only.
 * A 401 is fatal, while 204 and 404 have distinct, nonfatal output-row behavior.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class HttpStatusResponseP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void http401FailsWhile204And404PreserveNativeResultColumnsAfterHplDiskReopen(
      @TempDir Path tempDir) throws Exception {
    AtomicInteger received = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/status", exchange -> {
      try (exchange) {
        received.incrementAndGet();
        int status = Integer.parseInt(exchange.getRequestURI().getRawQuery());
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        if (status == 401) {
          exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"local-test\"");
        }
        if (status == 204) {
          exchange.sendResponseHeaders(status, -1);
        } else {
          byte[] body = (status == 404 ? "missing-雪" : status == 401
              ? "unauthorized-雪" : "ok-雪").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
        }
      }
    });
    server.start();
    try {
      String localUrl = "http://127.0.0.1:" + server.getAddress().getPort()
          + "/status?";
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Http");
      assertNotNull(plugin);
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class).invoke(original, localUrl + "404");

      var descriptor = AutomaticConfigDescriptor.describe(original.getClass());
      var contract = descriptor.runtimePreconditions().stream()
          .filter(p -> p.code().equals("HTTP_401_FATAL_204_EMPTY_OTHER_ERROR_STATUS_BODY"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/url", "/encoding", "/result/name", "/result/code",
          "/result/response_header"), contract.keyListPointers());
      assertEquals(AutomaticConfigDescriptor.Shape.OBJECT,
          descriptor.properties().stream().filter(p -> p.key().equals("result"))
              .findFirst().orElseThrow().shape());

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("http-status-compat.hpl");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("http-status-compat");
      pipeline.addTransform(new TransformMeta("Http", "http-status-node", original));
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("http-status-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("http-status-doc",
          new PipelineConfigResource.ReadRequest("http-status-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("encoding", "UTF-8");
      ObjectNode result = (ObjectNode) draft.path("result");
      result.put("name", "响应体_雪");
      result.put("code", "响应码");
      result.put("response_header", "响应头");
      var write = resource.write("http-status-doc",
          new PipelineConfigResource.WriteRequest("http-status-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("http-status-node").getTransform();
      JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
      assertEquals("UTF-8", wire.at("/encoding").asText());
      assertEquals("响应体_雪", wire.at("/result/name").asText());
      assertEquals("响应码", wire.at("/result/code").asText());
      assertEquals("响应头", wire.at("/result/response_header").asText());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> httpType = loader.loadClass("org.apache.hop.pipeline.transforms.http.Http");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.http.HttpData");
      Object data = dataType.getConstructor().newInstance();
      Object nativeHttp = httpType.getConstructor(TransformMeta.class, diskMeta.getClass(),
          dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
          .newInstance(reopened.findTransform("http-status-node"), diskMeta, data, 0,
              reopened, Mockito.spy(new LocalPipelineEngine()));
      httpType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class)
          .invoke(nativeHttp, provider);
      assertTrue((Boolean) httpType.getMethod("init").invoke(nativeHttp));
      dataType.getField("argNrs").set(data, new int[0]);
      Method call = httpType.getDeclaredMethod("callHttpService", IRowMeta.class, Object[].class);
      call.setAccessible(true);
      RowMeta input = new RowMeta();
      try {
        dataType.getField("realUrl").set(data, localUrl + "200");
        Object[] ok = (Object[]) call.invoke(nativeHttp, input, (Object) new Object[0]);
        assertEquals("ok-雪", ok[0]);
        assertEquals(200L, ok[1]);

        dataType.getField("realUrl").set(data, localUrl + "204");
        Object[] noContent = (Object[]) call.invoke(nativeHttp, input, (Object) new Object[0]);
        assertEquals("", noContent[0]);
        assertEquals(204L, noContent[1]);

        dataType.getField("realUrl").set(data, localUrl + "404");
        Object[] missing = (Object[]) call.invoke(nativeHttp, input, (Object) new Object[0]);
        assertEquals("missing-雪", missing[0],
            "404 is a returned response body, not a fatal transform exception");
        assertEquals(404L, missing[1]);
        assertTrue(missing[2] instanceof String);

        dataType.getField("realUrl").set(data, localUrl + "401");
        InvocationTargetException rejected = assertThrows(InvocationTargetException.class,
            () -> call.invoke(nativeHttp, input, (Object) new Object[0]));
        assertTrue(rejected.getCause() instanceof org.apache.hop.core.exception.HopException);
        assertEquals("HopTransformException",
            rejected.getCause().getCause().getClass().getSimpleName(),
            "native 401 handling must fail rather than return result columns");
      } finally {
        httpType.getMethod("dispose").invoke(nativeHttp);
      }
      assertTrue(received.get() >= 4, "all four HTTP status cases must reach loopback");
      System.out.println("PROD_P0_HTTP_401_FATAL_204_EMPTY_404_BODY_DISK_REOPEN=PASS");
    } finally {
      server.stop(0);
    }
  }
}
