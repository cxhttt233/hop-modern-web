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

}
