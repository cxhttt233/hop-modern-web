package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.json.ConfigJsonSerializer;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engines.local.LocalPipelineEngine;
import org.apache.hop.pipeline.transform.ITransform;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.pipeline.transform.RowAdapter;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production engine acceptance: execute a real DataGrid -> CheckSum pipeline
 * after editing the nested field list and coded enums through the T3 JSON API,
 * saving native Hop XML, and reopening the .hpl from disk. This checks emitted
 * SHA-256 bytes, not just metadata or serializer round trips.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class CheckSumFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void checkSumProducerPublishesCanonicalIndexedNativeFieldList() throws Exception {
    IPlugin plugin = PluginRegistry.getInstance()
        .findPluginWithId(TransformPluginType.class, "CheckSum");
    assertNotNull(plugin, "pinned CheckSum plugin required");
    ITransformMeta nativeMeta = PluginRegistry.getInstance()
        .loadClass(plugin, ITransformMeta.class);
    var descriptor = AutomaticConfigDescriptor.describe(nativeMeta.getClass());
    assertEquals(1, descriptor.wireLists().size());
    var list = descriptor.wireLists().get(0);
    assertEquals("field", list.descriptorPath());
    assertEquals("/fields/field", list.listPointer());
    assertEquals("/fields/field/{index}", list.itemPointerTemplate());
    assertEquals("/fields/field/0", list.itemPointer(0));
    assertEquals("/fields/field/1", list.itemPointer(1));
    var property = descriptor.properties().stream()
        .filter(p -> p.key().equals("field") && p.groupKey().equals("fields"))
        .findFirst().orElseThrow();
    assertEquals(AutomaticConfigDescriptor.Shape.LIST, property.shape());
    assertEquals("fields", property.groupKey());
    var published = new com.fasterxml.jackson.databind.ObjectMapper()
        .valueToTree(descriptor);
    assertEquals("/fields/field",
        published.at("/wireLists/0/listPointer").asText());
    assertEquals("/fields/field/{index}",
        published.at("/wireLists/0/itemPointerTemplate").asText());
    System.out.println("PROD_P0_CHECKSUM_CANONICAL_INDEXED_FIELD_POINTERS=PASS");
  }

  @Test
  void checkSumOrderedFieldsAndBinarySha256ExecuteFullPipelineAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin checksumPlugin = plugins.findPluginWithId(TransformPluginType.class, "CheckSum");
    assertNotNull(gridPlugin, "pinned DataGrid plugin required");
    assertNotNull(checksumPlugin, "pinned CheckSum plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> fieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("河流<&>甲", "工程_乙")) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> gridLines = new ArrayList<>();
    List<List<String>> sourceRows = List.of(
        List.of("黄河", "水网"),
        List.of("长江", "数字孪生"));
    for (List<String> values : sourceRows) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      gridLines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, gridLines);

    ITransformMeta checksum = plugins.loadClass(checksumPlugin, ITransformMeta.class);
    checksum.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("CheckSum", "checksum-node", checksum);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("checksum-binary-full-pipeline");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("checksum-binary-full-pipeline.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("checksum-full-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("checksum-full-doc",
        new PipelineConfigResource.ReadRequest("checksum-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("checksumtype", "SHA-256");
    draft.put("resultType", "binary");
    draft.put("resultfieldName", "摘要<&>值");
    draft.put("separator", "|");
    draft.put("prefix", "前缀：");
    draft.put("suffix", "<尾>");
    ArrayNode selected = draft.putObject("fields").putArray("field");
    selected.addObject().put("name", "河流<&>甲");
    selected.addObject().put("name", "工程_乙");
    var write = resource.write("checksum-full-doc",
        new PipelineConfigResource.WriteRequest("checksum-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("checksum-node").getTransform(), provider);
    assertEquals("SHA-256", wire.at("/checksumtype").asText());
    assertEquals("binary", wire.at("/resultType").asText());
    assertEquals("摘要<&>值", wire.at("/resultfieldName").asText());
    assertEquals("河流<&>甲", wire.at("/fields/field/0/name").asText());
    assertEquals("工程_乙", wire.at("/fields/field/1/name").asText());
    // The descriptor's producer-owned path must address the real Hop JSON
    // emitted after the XML -> config/write -> XML disk-reopen cycle.
    var listContract = AutomaticConfigDescriptor.describe(
        reopened.findTransform("checksum-node").getTransform().getClass())
        .wireLists().get(0);
    assertEquals("河流<&>甲", wire.at(listContract.itemPointer(0) + "/name").asText());
    assertEquals("工程_乙", wire.at(listContract.itemPointer(1) + "/name").asText());
    assertEquals("|", wire.at("/separator").asText());
    assertEquals("前缀：", wire.at("/prefix").asText());
    assertEquals("<尾>", wire.at("/suffix").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> emittedMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("checksum-node", 0);
      assertNotNull(output, "real engine must prepare CheckSum");
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          emittedMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native full pipeline must finish without errors");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(emittedMeta.get(), "CheckSum must emit native rows");
    assertEquals(3, emittedMeta.get().size());
    assertEquals("河流<&>甲", emittedMeta.get().getValueMeta(0).getName());
    assertEquals("工程_乙", emittedMeta.get().getValueMeta(1).getName());
    assertEquals("摘要<&>值", emittedMeta.get().getValueMeta(2).getName());
    assertEquals(IValueMeta.TYPE_BINARY, emittedMeta.get().getValueMeta(2).getType());
    assertEquals(2, emitted.size());
    for (int i = 0; i < sourceRows.size(); i++) {
      assertEquals(sourceRows.get(i).get(0), emitted.get(i)[0]);
      assertEquals(sourceRows.get(i).get(1), emitted.get(i)[1]);
      byte[] expected = MessageDigest.getInstance("SHA-256").digest(
          ("前缀：" + sourceRows.get(i).get(0) + "|" + sourceRows.get(i).get(1) + "<尾>")
              .getBytes(StandardCharsets.UTF_8));
      assertInstanceOf(byte[].class, emitted.get(i)[2]);
      assertArrayEquals(expected, (byte[]) emitted.get(i)[2]);
    }
    System.out.println("PROD_P0_CHECKSUM_FULL_PIPELINE_BINARY_SHA256_JSON_HPL_ROWS=PASS");
  }

  /**
   * CRC32 and ADLER32 are native INTEGER outputs even when the serialized
   * resultType requests binary. Verify both algorithms on actual UTF-8 rows
   * after API edits and native HPL disk reopen, not just getFields metadata.
   */
  @Test
  void checkSumCrc32AndAdler32KeepIntegerOutputsDespiteBinaryResultTypeAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin checksumPlugin = plugins.findPluginWithId(TransformPluginType.class, "CheckSum");
    assertNotNull(gridPlugin);
    assertNotNull(checksumPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    Object field = fieldType.getConstructor().newInstance();
    fieldType.getMethod("setName", String.class).invoke(field, "测站<&>");
    fieldType.getMethod("setType", String.class).invoke(field, "String");
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, List.of(field));
    List<String> stations = List.of("黄河", "长江");
    List<Object> lines = new ArrayList<>();
    for (String station : stations) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, List.of(station));
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta crc = plugins.loadClass(checksumPlugin, ITransformMeta.class);
    crc.setDefault();
    ITransformMeta adler = plugins.loadClass(checksumPlugin, ITransformMeta.class);
    adler.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta crcNode = new TransformMeta("CheckSum", "crc-node", crc);
    TransformMeta adlerNode = new TransformMeta("CheckSum", "adler-node", adler);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("checksum-crc32-adler32-integer-compatibility");
    pipeline.addTransform(source);
    pipeline.addTransform(crcNode);
    pipeline.addTransform(adlerNode);
    pipeline.addPipelineHop(new PipelineHopMeta(source, crcNode));
    pipeline.addPipelineHop(new PipelineHopMeta(crcNode, adlerNode));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("checksum-crc32-adler32.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(2, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("checksum-integer-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    for (String node : List.of("crc-node", "adler-node")) {
      var read = resource.read("checksum-integer-doc",
          new PipelineConfigResource.ReadRequest(node));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("checksumtype", node.equals("crc-node") ? "CRC32" : "ADLER32");
      draft.put("resultType", "binary");
      draft.put("resultfieldName", node.equals("crc-node") ? "CRC结果" : "Adler结果");
      draft.put("prefix", "");
      draft.put("separator", "");
      draft.put("suffix", "");
      ArrayNode selected = draft.putObject("fields").putArray("field");
      selected.addObject().put("name", "测站<&>");
      var write = resource.write("checksum-integer-doc",
          new PipelineConfigResource.WriteRequest(node, draft));
      assertEquals(200, write.getStatus(), node + " JSON config edit must succeed");
    }
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(3, reopened.nrTransforms());
    assertEquals(2, reopened.nrPipelineHops());
    for (String node : List.of("crc-node", "adler-node")) {
      JsonNode wire = ConfigJsonSerializer.toJson(
          reopened.findTransform(node).getTransform(), provider);
      assertEquals(node.equals("crc-node") ? "CRC32" : "ADLER32",
          wire.at("/checksumtype").asText());
      assertEquals("binary", wire.at("/resultType").asText());
      assertEquals("测站<&>", wire.at("/fields/field/0/name").asText());
      assertEquals(node.equals("crc-node") ? "CRC结果" : "Adler结果",
          wire.at("/resultfieldName").asText());
    }

    var contract = AutomaticConfigDescriptor.describe(
        reopened.findTransform("crc-node").getTransform().getClass())
        .runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "CHECKSUM_RESULT_ROW_TYPE_DEPENDS_ON_ALGORITHM_AND_ENCODING"))
        .findFirst().orElseThrow();
    assertTrue(contract.keyListPointers().contains("/checksumtype"));
    assertTrue(contract.keyListPointers().contains("/resultType"));

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("adler-node", 0);
      assertNotNull(output);
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          outputMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "both native checksums must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get());
    assertEquals(List.of("测站<&>", "CRC结果", "Adler结果"),
        outputMeta.get().getValueMetaList().stream().map(IValueMeta::getName).toList());
    assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(1).getType());
    assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(2).getType());
    assertEquals(stations.size(), emitted.size());
    for (int i = 0; i < stations.size(); i++) {
      String station = stations.get(i);
      byte[] bytes = station.getBytes(StandardCharsets.UTF_8);
      java.util.zip.CRC32 expectedCrc = new java.util.zip.CRC32();
      expectedCrc.update(bytes);
      java.util.zip.Adler32 expectedAdler = new java.util.zip.Adler32();
      expectedAdler.update(bytes);
      assertEquals(station, emitted.get(i)[0]);
      assertInstanceOf(Long.class, emitted.get(i)[1]);
      assertInstanceOf(Long.class, emitted.get(i)[2]);
      assertEquals(expectedCrc.getValue(), emitted.get(i)[1]);
      assertEquals(expectedAdler.getValue(), emitted.get(i)[2]);
    }
    System.out.println("PROD_P0_CHECKSUM_CRC_ADLER_BINARY_REQUEST_INTEGER_REAL_ROWS=PASS");
  }

  /**
   * Native CheckSum STRING is a dash-separated sequence of unsigned decimal
   * digest bytes, not Base64 or hexadecimal. HEXADECIMAL is lowercase hex.
   * Both are STRING columns despite having different wire representations.
   */
  @Test
  void checkSumHexAndDecimalByteStringRepresentationsSurviveHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin checksumPlugin = plugins.findPluginWithId(TransformPluginType.class, "CheckSum");
    assertNotNull(gridPlugin);
    assertNotNull(checksumPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    Object field = fieldType.getConstructor().newInstance();
    fieldType.getMethod("setName", String.class).invoke(field, "测站<&>");
    fieldType.getMethod("setType", String.class).invoke(field, "String");
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, List.of(field));
    List<String> stations = List.of("黄河", "长江");
    List<Object> lines = new ArrayList<>();
    for (String station : stations) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, List.of(station));
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta hex = plugins.loadClass(checksumPlugin, ITransformMeta.class);
    hex.setDefault();
    ITransformMeta decimal = plugins.loadClass(checksumPlugin, ITransformMeta.class);
    decimal.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta hexNode = new TransformMeta("CheckSum", "hex-node", hex);
    TransformMeta decimalNode = new TransformMeta("CheckSum", "decimal-node", decimal);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("checksum-sha256-string-encoding-compatibility");
    pipeline.addTransform(source);
    pipeline.addTransform(hexNode);
    pipeline.addTransform(decimalNode);
    pipeline.addPipelineHop(new PipelineHopMeta(source, hexNode));
    pipeline.addPipelineHop(new PipelineHopMeta(hexNode, decimalNode));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("checksum-sha256-string-encodings.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("checksum-encodings-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);

    for (String node : List.of("hex-node", "decimal-node")) {
      var read = resource.read("checksum-encodings-doc",
          new PipelineConfigResource.ReadRequest(node));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("checksumtype", "SHA-256");
      draft.put("resultType", node.equals("hex-node") ? "hexadecimal" : "string");
      draft.put("resultfieldName", node.equals("hex-node") ? "十六进制摘要" : "十进制字节摘要");
      draft.put("prefix", "");
      draft.put("separator", "");
      draft.put("suffix", "");
      ArrayNode selected = draft.putObject("fields").putArray("field");
      selected.addObject().put("name", "测站<&>");
      var write = resource.write("checksum-encodings-doc",
          new PipelineConfigResource.WriteRequest(node, draft));
      assertEquals(200, write.getStatus(), node + " JSON edit must succeed");
    }
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(3, reopened.nrTransforms());
    assertEquals(2, reopened.nrPipelineHops());
    for (String node : List.of("hex-node", "decimal-node")) {
      JsonNode wire = ConfigJsonSerializer.toJson(
          reopened.findTransform(node).getTransform(), provider);
      assertEquals("SHA-256", wire.at("/checksumtype").asText());
      assertEquals(node.equals("hex-node") ? "hexadecimal" : "string",
          wire.at("/resultType").asText());
      assertEquals("测站<&>", wire.at("/fields/field/0/name").asText());
      assertEquals(node.equals("hex-node") ? "十六进制摘要" : "十进制字节摘要",
          wire.at("/resultfieldName").asText());
    }

    var contract = AutomaticConfigDescriptor.describe(
        reopened.findTransform("hex-node").getTransform().getClass())
        .runtimePreconditions().stream()
        .filter(p -> p.code().equals("CHECKSUM_RESULT_ROW_TYPE_DEPENDS_ON_ALGORITHM_AND_ENCODING"))
        .findFirst().orElseThrow();
    assertTrue(contract.keyListPointers().contains("/checksumtype"));
    assertTrue(contract.keyListPointers().contains("/resultType"));

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("decimal-node", 0);
      assertNotNull(output);
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          outputMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "both SHA-256 encodings must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get());
    assertEquals(List.of("测站<&>", "十六进制摘要", "十进制字节摘要"),
        outputMeta.get().getValueMetaList().stream().map(IValueMeta::getName).toList());
    assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(1).getType());
    assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(2).getType());
    assertEquals(stations.size(), emitted.size());
    for (int i = 0; i < stations.size(); i++) {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(
          stations.get(i).getBytes(StandardCharsets.UTF_8));
      String expectedHex = java.util.HexFormat.of().formatHex(digest);
      List<String> unsignedBytes = new ArrayList<>();
      for (byte b : digest) unsignedBytes.add(Integer.toString(b & 0xff));
      String expectedDecimal = String.join("-", unsignedBytes);
      assertEquals(stations.get(i), emitted.get(i)[0]);
      assertEquals(expectedHex, emitted.get(i)[1]);
      assertEquals(expectedDecimal, emitted.get(i)[2]);
      assertNotEquals(expectedHex, expectedDecimal);
    }
    System.out.println("PROD_P0_CHECKSUM_SHA256_HEX_VS_DECIMAL_BYTES_JSON_HPL_ROWS=PASS");
  }

}
