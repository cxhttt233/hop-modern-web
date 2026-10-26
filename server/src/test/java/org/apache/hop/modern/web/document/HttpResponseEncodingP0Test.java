package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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

/**
 * T3-owned native HTTP response decoding compatibility, using pinned Hop's real Http transform.
 * This test does not send any network requests and does not change Hop Core.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class HttpResponseEncodingP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void httpConfiguredResponseEncodingOverridesEntityCharsetAfterRealHplDiskReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Http");
    assertNotNull(plugin);
    ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
    original.setDefault();
    original.getClass().getMethod("setUrl", String.class)
        .invoke(original, "https://example.invalid/encoding?river=雪");

    var descriptor = AutomaticConfigDescriptor.describe(original.getClass());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "HTTP_RESPONSE_BODY_DECODING_USES_CONFIG_ENCODING_NOT_RESPONSE_CHARSET"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/encoding", "/result/name"), contract.keyListPointers());
    assertEquals(AutomaticConfigDescriptor.Shape.STRING, descriptor.properties().stream()
        .filter(p -> p.key().equals("encoding")).findFirst().orElseThrow().shape());

    var provider = new MemoryMetadataProvider();
    var variables = new Variables();
    var store = new PipelineDocumentStore(provider, variables);
    Path hpl = tempDir.resolve("http-response-encoding.hpl");
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("http-response-encoding");
    pipeline.addTransform(new TransformMeta("Http", "http-encoding-node", original));
    store.save(hpl, pipeline);

    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("http-encoding-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("http-encoding-doc",
        new PipelineConfigResource.ReadRequest("http-encoding-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("encoding", "ISO-8859-1");
    assertTrue(draft.path("result").isObject());
    ((ObjectNode) draft.path("result")).put("name", "响应<&>文本");
    var write = resource.write("http-encoding-doc",
        new PipelineConfigResource.WriteRequest("http-encoding-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    ITransformMeta diskMeta = reopened.findTransform("http-encoding-node").getTransform();
    JsonNode wire = ConfigJsonSerializer.toJson(diskMeta, provider);
    assertTrue(wire.at("/encoding").isTextual());
    assertEquals("ISO-8859-1", wire.at("/encoding").asText());
    assertEquals("响应<&>文本", wire.at("/result/name").asText());

    // Exercise Http.readResponseBody() with a real HttpCore entity. Its advertised
    // Content-Type charset deliberately contradicts the configured Hop encoding.
    ClassLoader loader = diskMeta.getClass().getClassLoader();
    Class<?> httpType = loader.loadClass("org.apache.hop.pipeline.transforms.http.Http");
    Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.http.HttpData");
    Object data = dataType.getConstructor().newInstance();
    Object nativeHttp = httpType.getConstructor(TransformMeta.class, diskMeta.getClass(),
        dataType, int.class, PipelineMeta.class, org.apache.hop.pipeline.Pipeline.class)
        .newInstance(reopened.findTransform("http-encoding-node"), diskMeta, data, 0,
            reopened, Mockito.spy(new LocalPipelineEngine()));

    Class<?> entityType = loader.loadClass("org.apache.hc.core5.http.HttpEntity");
    Class<?> contentType = loader.loadClass("org.apache.hc.core5.http.ContentType");
    Class<?> byteArrayEntity = loader.loadClass(
        "org.apache.hc.core5.http.io.entity.ByteArrayEntity");
    Constructor<?> entityConstructor = byteArrayEntity.getConstructor(byte[].class, contentType);
    Method contentTypeCreate = contentType.getMethod("create", String.class, Charset.class);
    Method nativeDecode = httpType.getDeclaredMethod("readResponseBody", entityType);
    nativeDecode.setAccessible(true);

    byte[] latin1 = "café".getBytes(StandardCharsets.ISO_8859_1);
    Object misleadingUtf8 = contentTypeCreate.invoke(
        null, "text/plain", StandardCharsets.UTF_8);
    Object latinEntity = entityConstructor.newInstance(latin1, misleadingUtf8);
    assertEquals("café", nativeDecode.invoke(nativeHttp, latinEntity),
        "configured ISO-8859-1 must win over response Content-Type UTF-8");

    diskMeta.getClass().getMethod("setEncoding", String.class)
        .invoke(diskMeta, "UTF-8");
    byte[] utf8 = "黄河<&>雪".getBytes(StandardCharsets.UTF_8);
    Object misleadingLatin1 = contentTypeCreate.invoke(
        null, "text/plain", StandardCharsets.ISO_8859_1);
    Object utf8Entity = entityConstructor.newInstance(utf8, misleadingLatin1);
    assertEquals("黄河<&>雪", nativeDecode.invoke(nativeHttp, utf8Entity),
        "configured UTF-8 must win over response Content-Type ISO-8859-1");

    System.out.println("PROD_P0_HTTP_CONFIGURED_RESPONSE_ENCODING_DISK_REOPEN=PASS");
  }
}
