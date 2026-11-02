package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
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
 * Native JsonInput execution acceptance: edit two ordered JsonPath fields through
 * the production config API, save/reopen the real HPL, then parse actual upstream
 * JSON rows. This deliberately goes beyond the existing metadata-only disk test.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class JsonInputFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void jsonInputTwoOrderedTypedPathsExecuteFullPipelineAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin jsonPlugin = plugins.findPluginWithId(TransformPluginType.class, "JsonInput");
    assertNotNull(gridPlugin, "pinned DataGrid plugin required");
    assertNotNull(jsonPlugin, "pinned JsonInput plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> gridFieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> gridLineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("payload", "来源")) {
      Object field = gridFieldType.getConstructor().newInstance();
      gridFieldType.getMethod("setName", String.class).invoke(field, name);
      gridFieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("{\"station\":\"黄河<&>\",\"flow\":42}", "批次一"),
        List.of("{\"station\":\"长江雪\",\"flow\":7}", "批次二"))) {
      Object line = gridLineType.getConstructor().newInstance();
      gridLineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta json = plugins.loadClass(jsonPlugin, ITransformMeta.class);
    json.setDefault();
    Class<?> jsonFieldType = json.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.jsoninput.JsonInputField");
    Object station = jsonFieldType.getConstructor(String.class).newInstance("站点<&>雪");
    jsonFieldType.getMethod("setPath", String.class).invoke(station, "$.legacyStation");
    jsonFieldType.getMethod("setType", int.class).invoke(station, IValueMeta.TYPE_STRING);
    Object flow = jsonFieldType.getConstructor(String.class).newInstance("流量");
    jsonFieldType.getMethod("setPath", String.class).invoke(flow, "$.legacyFlow");
    jsonFieldType.getMethod("setType", int.class).invoke(flow, IValueMeta.TYPE_INTEGER);
    json.getClass().getMethod("setInputFields", List.class)
        .invoke(json, List.of(station, flow));

    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("JsonInput", "json-node", json);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("jsoninput-native-two-paths-full-pipeline");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("jsoninput-native-two-paths.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("json-full-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("json-full-doc",
        new PipelineConfigResource.ReadRequest("json-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("IsInFields", true);
    draft.put("IsAFile", false);
    draft.put("valueField", "payload");
    draft.put("removeSourceField", true);
    draft.put("ignoreMissingPath", false);
    ArrayNode paths = (ArrayNode) draft.path("fields").path("field");
    assertEquals(2, paths.size(), "initial typed JSONPath fields must be grouped");
    ((ObjectNode) paths.get(0)).put("path", "$.station");
    ((ObjectNode) paths.get(1)).put("path", "$.flow");

    var write = resource.write("json-full-doc",
        new PipelineConfigResource.WriteRequest("json-node", draft));
    assertEquals(200, write.getStatus(), "two native JSONPath edits must be accepted");
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("json-node").getTransform(), provider);
    assertTrue(wire.at("/IsInFields").isBoolean());
    assertTrue(wire.at("/IsInFields").booleanValue());
    assertTrue(wire.at("/removeSourceField").booleanValue());
    assertEquals("payload", wire.at("/valueField").asText());
    assertEquals(2, wire.at("/fields/field").size());
    assertEquals("站点<&>雪", wire.at("/fields/field/0/name").asText());
    assertEquals("$.station", wire.at("/fields/field/0/path").asText());
    assertEquals("流量", wire.at("/fields/field/1/name").asText());
    assertEquals("$.flow", wire.at("/fields/field/1/path").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("json-node", 0);
      assertNotNull(output, "native JsonInput engine must prepare");
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          outputMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native JsonInput pipeline must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get(), "JsonInput must emit parsed rows");
    assertEquals(3, outputMeta.get().size(), "source payload must be removed");
    assertEquals("来源", outputMeta.get().getValueMeta(0).getName());
    assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(0).getType());
    assertEquals("站点<&>雪", outputMeta.get().getValueMeta(1).getName());
    assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(1).getType());
    assertEquals("流量", outputMeta.get().getValueMeta(2).getName());
    assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(2).getType());
    assertEquals(2, emitted.size(), "two upstream JSON documents must produce two rows");
    assertEquals("批次一", emitted.get(0)[0]);
    assertEquals("黄河<&>", emitted.get(0)[1]);
    assertEquals(42L, ((Number) emitted.get(0)[2]).longValue());
    assertEquals("批次二", emitted.get(1)[0]);
    assertEquals("长江雪", emitted.get(1)[1]);
    assertEquals(7L, ((Number) emitted.get(1)[2]).longValue());
    System.out.println("PROD_P0_JSONINPUT_FULL_PIPELINE_TWO_TYPED_PATHS_JSON_HPL_ROWS=PASS");
  }

  @Test
  void jsonInputMissingAndExplicitNullPathsPreserveTypedRowsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin jsonPlugin = plugins.findPluginWithId(TransformPluginType.class, "JsonInput");
    assertNotNull(gridPlugin, "pinned DataGrid plugin required");
    assertNotNull(jsonPlugin, "pinned JsonInput plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> gridFieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> gridLineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("payload", "来源")) {
      Object field = gridFieldType.getConstructor().newInstance();
      gridFieldType.getMethod("setName", String.class).invoke(field, name);
      gridFieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("{\"station\":\"黄河<&>\",\"flow\":42}", "批次一"),
        List.of("{\"station\":\"长江雪\"}", "批次二"),
        List.of("{\"station\":null,\"flow\":7}", "批次三"))) {
      Object line = gridLineType.getConstructor().newInstance();
      gridLineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta json = plugins.loadClass(jsonPlugin, ITransformMeta.class);
    json.setDefault();
    Class<?> jsonFieldType = json.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.jsoninput.JsonInputField");
    Object station = jsonFieldType.getConstructor(String.class).newInstance("站点<&>雪");
    jsonFieldType.getMethod("setPath", String.class).invoke(station, "$.legacyStation");
    jsonFieldType.getMethod("setType", int.class).invoke(station, IValueMeta.TYPE_STRING);
    Object flow = jsonFieldType.getConstructor(String.class).newInstance("流量");
    jsonFieldType.getMethod("setPath", String.class).invoke(flow, "$.legacyFlow");
    jsonFieldType.getMethod("setType", int.class).invoke(flow, IValueMeta.TYPE_INTEGER);
    json.getClass().getMethod("setInputFields", List.class)
        .invoke(json, List.of(station, flow));

    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("JsonInput", "json-node", json);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("jsoninput-missing-null-full-pipeline");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("jsoninput-missing-null.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("json-null-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("json-null-doc",
        new PipelineConfigResource.ReadRequest("json-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("IsInFields", true);
    draft.put("IsAFile", false);
    draft.put("valueField", "payload");
    draft.put("removeSourceField", false);
    draft.put("ignoreMissingPath", true);
    draft.put("defaultPathLeafToNull", true);
    ArrayNode paths = (ArrayNode) draft.path("fields").path("field");
    assertEquals(2, paths.size(), "initial typed JSONPath fields must be grouped");
    ((ObjectNode) paths.get(0)).put("path", "$.station");
    ((ObjectNode) paths.get(1)).put("path", "$.flow");

    var write = resource.write("json-null-doc",
        new PipelineConfigResource.WriteRequest("json-node", draft));
    assertEquals(200, write.getStatus(), "two native JSONPath edits must be accepted");
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("json-node").getTransform(), provider);
    assertTrue(wire.at("/IsInFields").isBoolean());
    assertTrue(wire.at("/IsInFields").booleanValue());
    assertFalse(wire.at("/removeSourceField").booleanValue());
    assertTrue(wire.at("/ignoreMissingPath").isBoolean());
    assertTrue(wire.at("/ignoreMissingPath").booleanValue());
    assertTrue(wire.at("/defaultPathLeafToNull").booleanValue());
    assertEquals("payload", wire.at("/valueField").asText());
    assertEquals(2, wire.at("/fields/field").size());
    assertEquals("站点<&>雪", wire.at("/fields/field/0/name").asText());
    assertEquals("$.station", wire.at("/fields/field/0/path").asText());
    assertEquals("流量", wire.at("/fields/field/1/name").asText());
    assertEquals("$.flow", wire.at("/fields/field/1/path").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("json-node", 0);
      assertNotNull(output, "native JsonInput engine must prepare");
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          outputMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native JsonInput pipeline must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get(), "JsonInput must emit parsed rows");
    assertEquals(4, outputMeta.get().size(), "source JSON and upstream label must remain");
    assertEquals("payload", outputMeta.get().getValueMeta(0).getName());
    assertEquals("来源", outputMeta.get().getValueMeta(1).getName());
    assertEquals("站点<&>雪", outputMeta.get().getValueMeta(2).getName());
    assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(2).getType());
    assertEquals("流量", outputMeta.get().getValueMeta(3).getName());
    assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(3).getType());
    assertEquals(3, emitted.size(), "missing and explicit-null paths must not drop rows");
    assertEquals("{\"station\":\"黄河<&>\",\"flow\":42}", emitted.get(0)[0]);
    assertEquals("批次一", emitted.get(0)[1]);
    assertEquals("黄河<&>", emitted.get(0)[2]);
    assertEquals(42L, ((Number) emitted.get(0)[3]).longValue());
    assertEquals("{\"station\":\"长江雪\"}", emitted.get(1)[0]);
    assertEquals("批次二", emitted.get(1)[1]);
    assertEquals("长江雪", emitted.get(1)[2]);
    assertNull(emitted.get(1)[3], "missing numeric JsonPath must remain null");
    assertEquals("{\"station\":null,\"flow\":7}", emitted.get(2)[0]);
    assertEquals("批次三", emitted.get(2)[1]);
    assertNull(emitted.get(2)[2], "explicit JSON null must remain null");
    assertEquals(7L, ((Number) emitted.get(2)[3]).longValue());
    System.out.println("PROD_P0_JSONINPUT_MISSING_AND_EXPLICIT_NULL_TYPED_ROWS=PASS");
  }


  @Test
  void jsonInputWildcardArraysExpandAlignedRowsAfterRealHplReopen(
      @TempDir Path tempDir) throws Exception {
    // JsonPath wildcard results are row-producing lists, not a scalar JSON string.
    // Exercise two aligned typed lists with different per-document lengths.
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin jsonPlugin = plugins.findPluginWithId(TransformPluginType.class, "JsonInput");
    assertNotNull(gridPlugin);
    assertNotNull(jsonPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> gridFieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> gridLineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("payload", "来源")) {
      Object field = gridFieldType.getConstructor().newInstance();
      gridFieldType.getMethod("setName", String.class).invoke(field, name);
      gridFieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("{\"samples\":[{\"station\":\"黄河<&>\",\"flow\":42},{\"station\":\"渭河\",\"flow\":7}]}",
            "批次一"),
        List.of("{\"samples\":[{\"station\":\"汾河\",\"flow\":3}]}",
            "批次二"))) {
      Object line = gridLineType.getConstructor().newInstance();
      gridLineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta json = plugins.loadClass(jsonPlugin, ITransformMeta.class);
    json.setDefault();
    Class<?> jsonFieldType = json.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.jsoninput.JsonInputField");
    Object station = jsonFieldType.getConstructor(String.class).newInstance("测站<&>");
    jsonFieldType.getMethod("setPath", String.class).invoke(station, "$.legacyStation");
    jsonFieldType.getMethod("setType", int.class).invoke(station, IValueMeta.TYPE_STRING);
    Object flow = jsonFieldType.getConstructor(String.class).newInstance("流量");
    jsonFieldType.getMethod("setPath", String.class).invoke(flow, "$.legacyFlow");
    jsonFieldType.getMethod("setType", int.class).invoke(flow, IValueMeta.TYPE_INTEGER);
    json.getClass().getMethod("setInputFields", List.class).invoke(json, List.of(station, flow));

    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("JsonInput", "json-node", json);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("jsoninput-wildcard-aligned-rows");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("jsoninput-wildcard-aligned.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("json-wildcard-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("json-wildcard-doc",
        new PipelineConfigResource.ReadRequest("json-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("IsInFields", true);
    draft.put("IsAFile", false);
    draft.put("valueField", "payload");
    draft.put("removeSourceField", true);
    draft.put("ignoreMissingPath", false);
    ArrayNode paths = (ArrayNode) draft.path("fields").path("field");
    assertEquals(2, paths.size());
    ((ObjectNode) paths.get(0)).put("path", "$.samples[*].station");
    ((ObjectNode) paths.get(1)).put("path", "$.samples[*].flow");
    var write = resource.write("json-wildcard-doc",
        new PipelineConfigResource.WriteRequest("json-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("json-node").getTransform(), provider);
    assertTrue(wire.at("/IsInFields").booleanValue());
    assertTrue(wire.at("/removeSourceField").booleanValue());
    assertTrue(wire.at("/fields/field").isArray());
    assertEquals(2, wire.at("/fields/field").size());
    assertEquals("测站<&>", wire.at("/fields/field/0/name").asText());
    assertEquals("$.samples[*].station", wire.at("/fields/field/0/path").asText());
    assertEquals("流量", wire.at("/fields/field/1/name").asText());
    assertEquals("$.samples[*].flow", wire.at("/fields/field/1/path").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("json-node", 0);
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
      assertEquals(0, engine.getErrors(), "wildcard JSON input must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get());
    assertEquals(3, outputMeta.get().size(), "source JSON column removed");
    assertEquals("来源", outputMeta.get().getValueMeta(0).getName());
    assertEquals("测站<&>", outputMeta.get().getValueMeta(1).getName());
    assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(1).getType());
    assertEquals("流量", outputMeta.get().getValueMeta(2).getName());
    assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(2).getType());
    assertEquals(3, emitted.size(), "2 wildcard matches plus 1 match");
    assertEquals("批次一", emitted.get(0)[0]);
    assertEquals("黄河<&>", emitted.get(0)[1]);
    assertEquals(42L, ((Number) emitted.get(0)[2]).longValue());
    assertEquals("批次一", emitted.get(1)[0]);
    assertEquals("渭河", emitted.get(1)[1]);
    assertEquals(7L, ((Number) emitted.get(1)[2]).longValue());
    assertEquals("批次二", emitted.get(2)[0]);
    assertEquals("汾河", emitted.get(2)[1]);
    assertEquals(3L, ((Number) emitted.get(2)[2]).longValue());
    System.out.println("PROD_P0_JSONINPUT_WILDCARD_ALIGNED_ARRAY_ROWS_JSON_HPL=PASS");
  }


  @Test
  void jsonInputUnequalNonemptyWildcardListsFailNativeEngineAfterRealHplReopen(
      @TempDir Path tempDir) throws Exception {
    // This is not a missing-path test: both JSONPath results exist, but their
    // nonempty cardinalities disagree. FastJsonReader must not silently align them.
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin jsonPlugin = plugins.findPluginWithId(TransformPluginType.class, "JsonInput");
    assertNotNull(gridPlugin);
    assertNotNull(jsonPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> gridFieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> gridLineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    Object gridField = gridFieldType.getConstructor().newInstance();
    gridFieldType.getMethod("setName", String.class).invoke(gridField, "payload");
    gridFieldType.getMethod("setType", String.class).invoke(gridField, "String");
    grid.getClass().getMethod("setDataGridFields", List.class)
        .invoke(grid, List.of(gridField));
    Object line = gridLineType.getConstructor().newInstance();
    gridLineType.getMethod("setDatalines", List.class).invoke(line,
        List.of("{\"stations\":[\"黄河<&>\",\"渭河\"],\"flows\":[42]}"));
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, List.of(line));

    ITransformMeta json = plugins.loadClass(jsonPlugin, ITransformMeta.class);
    json.setDefault();
    Class<?> fieldType = json.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.jsoninput.JsonInputField");
    Object station = fieldType.getConstructor(String.class).newInstance("测站<&>");
    fieldType.getMethod("setPath", String.class).invoke(station, "$.legacyStation");
    fieldType.getMethod("setType", int.class).invoke(station, IValueMeta.TYPE_STRING);
    Object flow = fieldType.getConstructor(String.class).newInstance("流量");
    fieldType.getMethod("setPath", String.class).invoke(flow, "$.legacyFlow");
    fieldType.getMethod("setType", int.class).invoke(flow, IValueMeta.TYPE_INTEGER);
    json.getClass().getMethod("setInputFields", List.class)
        .invoke(json, List.of(station, flow));

    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("JsonInput", "json-node", json);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("jsoninput-unequal-nonempty-wildcards");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));
    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("jsoninput-unequal-wildcards.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("json-unequal-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("json-unequal-doc",
        new PipelineConfigResource.ReadRequest("json-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("IsInFields", true);
    draft.put("IsAFile", false);
    draft.put("valueField", "payload");
    draft.put("removeSourceField", true);
    draft.put("ignoreMissingPath", true);
    draft.put("defaultPathLeafToNull", false);
    ArrayNode paths = (ArrayNode) draft.path("fields").path("field");
    assertEquals(2, paths.size());
    ((ObjectNode) paths.get(0)).put("path", "$.stations[*]");
    ((ObjectNode) paths.get(1)).put("path", "$.flows[*]");
    var write = resource.write("json-unequal-doc",
        new PipelineConfigResource.WriteRequest("json-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("json-node").getTransform(), provider);
    assertTrue(wire.at("/ignoreMissingPath").booleanValue());
    assertFalse(wire.at("/defaultPathLeafToNull").booleanValue());
    assertEquals("payload", wire.at("/valueField").asText());
    assertEquals(2, wire.at("/fields/field").size());
    assertEquals("测站<&>", wire.at("/fields/field/0/name").asText());
    assertEquals("$.stations[*]", wire.at("/fields/field/0/path").asText());
    assertEquals("流量", wire.at("/fields/field/1/name").asText());
    assertEquals("$.flows[*]", wire.at("/fields/field/1/path").asText());

    var contract = AutomaticConfigDescriptor.describe(
        reopened.findTransform("json-node").getTransform().getClass())
        .runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "JSON_INPUT_UNEQUAL_NONEMPTY_WILDCARD_ARRAYS_FAIL_RUNTIME"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field", "/ignoreMissingPath",
        "/defaultPathLeafToNull"), contract.keyListPointers());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("json-node", 0);
      assertNotNull(output);
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertTrue(engine.getErrors() > 0,
          "unequal nonempty JSONPath result lists must fail native FastJsonReader");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }
    assertTrue(emitted.isEmpty(),
        "a mismatched JSONPath document must not produce a padded partial row");
    System.out.println("PROD_P0_JSONINPUT_UNEQUAL_NONEMPTY_WILDCARDS_NATIVE_ERROR_HPL=PASS");
  }


  @Test
  void jsonInputLimitCountsExpandedRowsAndOneBasedRowNumberAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    // Pinned JsonInput.init() initializes rownr=1. Each wildcard-expanded output
    // receives that counter before writeOutputRowIfPossible() increments it;
    // the post-emit limit check counts output rows, not source JSON documents.
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin jsonPlugin = plugins.findPluginWithId(TransformPluginType.class, "JsonInput");
    assertNotNull(gridPlugin);
    assertNotNull(jsonPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> gridFieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> gridLineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("payload", "来源")) {
      Object field = gridFieldType.getConstructor().newInstance();
      gridFieldType.getMethod("setName", String.class).invoke(field, name);
      gridFieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    Object line = gridLineType.getConstructor().newInstance();
    gridLineType.getMethod("setDatalines", List.class).invoke(line, List.of(
        "{\"items\":[{\"station\":\"黄河<&>\"},{\"station\":\"渭河\"},"
            + "{\"station\":\"汾河\"}]}", "批次<&>"));
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, List.of(line));

    ITransformMeta json = plugins.loadClass(jsonPlugin, ITransformMeta.class);
    json.setDefault();
    Class<?> fieldType = json.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.jsoninput.JsonInputField");
    Object station = fieldType.getConstructor(String.class).newInstance("测站<&>");
    fieldType.getMethod("setPath", String.class).invoke(station, "$.legacy");
    fieldType.getMethod("setType", int.class).invoke(station, IValueMeta.TYPE_STRING);
    json.getClass().getMethod("setInputFields", List.class)
        .invoke(json, List.of(station));

    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("JsonInput", "json-node", json);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("jsoninput-rowlimit-zero-based-rownum");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));
    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("jsoninput-rowlimit-rownum.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("json-rowlimit-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("json-rowlimit-doc",
        new PipelineConfigResource.ReadRequest("json-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("IsInFields", true);
    draft.put("IsAFile", false);
    draft.put("valueField", "payload");
    draft.put("removeSourceField", true);
    draft.put("rownum", true);
    draft.put("rownum_field", "序号<&>");
    draft.put("limit", 2);
    ArrayNode paths = (ArrayNode) draft.path("fields").path("field");
    assertEquals(1, paths.size());
    ((ObjectNode) paths.get(0)).put("path", "$.items[*].station");
    var write = resource.write("json-rowlimit-doc",
        new PipelineConfigResource.WriteRequest("json-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("json-node").getTransform(), provider);
    assertTrue(wire.at("/IsInFields").booleanValue());
    assertTrue(wire.at("/removeSourceField").booleanValue());
    assertTrue(wire.at("/rownum").isBoolean());
    assertTrue(wire.at("/rownum").booleanValue());
    assertEquals("序号<&>", wire.at("/rownum_field").asText());
    assertTrue(wire.at("/limit").isNumber());
    assertEquals(2L, wire.at("/limit").longValue());
    assertEquals("$.items[*].station", wire.at("/fields/field/0/path").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("json-node", 0);
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
      assertEquals(0, engine.getErrors());
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get());
    assertEquals(3, outputMeta.get().size());
    assertEquals("来源", outputMeta.get().getValueMeta(0).getName());
    assertEquals("测站<&>", outputMeta.get().getValueMeta(1).getName());
    assertEquals("序号<&>", outputMeta.get().getValueMeta(2).getName());
    assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(2).getType());
    assertEquals(2, emitted.size(),
        "native limit=2 must stop after two wildcard-expanded rows");
    assertEquals("批次<&>", emitted.get(0)[0]);
    assertEquals("黄河<&>", emitted.get(0)[1]);
    assertEquals(1L, ((Number) emitted.get(0)[2]).longValue());
    assertEquals("批次<&>", emitted.get(1)[0]);
    assertEquals("渭河", emitted.get(1)[1]);
    assertEquals(2L, ((Number) emitted.get(1)[2]).longValue());
    System.out.println("PROD_P0_JSONINPUT_LIMIT_EXPANDED_ROWS_ONE_BASED_ROWNUM_NATIVE_ENGINE=PASS");
  }

}
