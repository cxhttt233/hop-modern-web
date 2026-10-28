package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Native GroupBy runtime acceptance: ordered grouping and aggregate LISTs
 * survive the T3 config API and HPL disk reopen, then actually collapse
 * contiguous input rows into typed COUNT_ANY and CONCAT_COMMA results.
 * This checks emitted rows, not just metadata or JSON round-trip.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class GroupByFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void groupByCountAndConcatenationExecuteFullPipelineAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin groupPlugin = plugins.findPluginWithId(TransformPluginType.class, "GroupBy");
    assertNotNull(gridPlugin, "pinned DataGrid plugin required");
    assertNotNull(groupPlugin, "pinned GroupBy plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("分组<&>", "样本")) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("黄河<&>", "甲"),
        List.of("黄河<&>", "乙"),
        List.of("长江", "丙"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta group = plugins.loadClass(groupPlugin, ITransformMeta.class);
    group.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("GroupBy", "group-node", group);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("groupby-native-count-concat-full-pipeline");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("groupby-native-count-concat.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("group-full-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("group-full-doc",
        new PipelineConfigResource.ReadRequest("group-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("all_rows", false);
    draft.put("give_back_row", false);
    draft.putObject("group").putArray("field")
        .addObject().put("name", "分组<&>");
    var aggregations = draft.putObject("fields").putArray("field");
    aggregations.addObject()
        .put("aggregate", "记录数")
        .put("subject", "")
        .put("type", "COUNT_ANY");
    aggregations.addObject()
        .put("aggregate", "样本列表")
        .put("subject", "样本")
        .put("type", "CONCAT_COMMA");

    var write = resource.write("group-full-doc",
        new PipelineConfigResource.WriteRequest("group-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("group-node").getTransform(), provider);
    assertTrue(wire.at("/all_rows").isBoolean());
    assertFalse(wire.at("/all_rows").booleanValue());
    assertEquals(1, wire.at("/group/field").size());
    assertEquals("分组<&>", wire.at("/group/field/0/name").asText());
    assertEquals(2, wire.at("/fields/field").size());
    assertEquals("记录数", wire.at("/fields/field/0/aggregate").asText());
    assertEquals("COUNT_ANY", wire.at("/fields/field/0/type").asText());
    assertEquals("样本列表", wire.at("/fields/field/1/aggregate").asText());
    assertEquals("样本", wire.at("/fields/field/1/subject").asText());
    assertEquals("CONCAT_COMMA", wire.at("/fields/field/1/type").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("group-node", 0);
      assertNotNull(output, "native GroupBy engine must prepare");
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          outputMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native GroupBy pipeline must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get(), "GroupBy must emit rows");
    assertEquals(3, outputMeta.get().size());
    assertEquals("分组<&>", outputMeta.get().getValueMeta(0).getName());
    assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(0).getType());
    assertEquals("记录数", outputMeta.get().getValueMeta(1).getName());
    assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(1).getType());
    assertEquals("样本列表", outputMeta.get().getValueMeta(2).getName());
    assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(2).getType());
    assertEquals(2, emitted.size(), "three contiguous source rows must form two groups");
    assertEquals("黄河<&>", emitted.get(0)[0]);
    assertEquals(2L, ((Number) emitted.get(0)[1]).longValue());
    assertEquals("甲, 乙", emitted.get(0)[2]);
    assertEquals("长江", emitted.get(1)[0]);
    assertEquals(1L, ((Number) emitted.get(1)[1]).longValue());
    assertEquals("丙", emitted.get(1)[2]);
    System.out.println("PROD_P0_GROUPBY_FULL_PIPELINE_COUNT_CONCAT_JSON_HPL_ROWS=PASS");
  }

  /**
   * Native pass-all-rows contract: each original row is preserved, group totals
   * are repeated on every row, and the INTEGER line number resets per group.
   * The config is edited through the real API and reopened from a disk HPL.
   */
  @Test
  void groupByPassAllRowsRepeatsTotalsAndResetsLineNumberAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin groupPlugin = plugins.findPluginWithId(TransformPluginType.class, "GroupBy");
    assertNotNull(gridPlugin);
    assertNotNull(groupPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("分组<&>", "样本")) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> gridLines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("黄河<&>", "甲"),
        List.of("黄河<&>", "乙"),
        List.of("长江", "丙"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      gridLines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, gridLines);

    ITransformMeta group = plugins.loadClass(groupPlugin, ITransformMeta.class);
    group.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("GroupBy", "group-node", group);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("groupby-pass-all-rows-line-number");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("groupby-pass-all-rows.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("group-pass-all-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("group-pass-all-doc",
        new PipelineConfigResource.ReadRequest("group-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("all_rows", true);
    draft.put("add_linenr", true);
    draft.put("linenr_fieldname", "组内序号<&>");
    draft.put("give_back_row", false);
    draft.putObject("group").putArray("field")
        .addObject().put("name", "分组<&>");
    draft.putObject("fields").putArray("field")
        .addObject().put("aggregate", "记录数")
        .put("subject", "").put("type", "COUNT_ANY");
    var write = resource.write("group-pass-all-doc",
        new PipelineConfigResource.WriteRequest("group-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("group-node").getTransform(), provider);
    assertTrue(wire.at("/all_rows").isBoolean());
    assertTrue(wire.at("/all_rows").booleanValue());
    assertTrue(wire.at("/add_linenr").isBoolean());
    assertTrue(wire.at("/add_linenr").booleanValue());
    assertEquals("组内序号<&>", wire.at("/linenr_fieldname").asText());
    assertEquals("分组<&>", wire.at("/group/field/0/name").asText());
    assertEquals("COUNT_ANY", wire.at("/fields/field/0/type").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("group-node", 0);
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
      assertEquals(0, engine.getErrors(), "pass-all-rows GroupBy must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get());
    assertEquals(4, outputMeta.get().size());
    assertEquals("分组<&>", outputMeta.get().getValueMeta(0).getName());
    assertEquals("样本", outputMeta.get().getValueMeta(1).getName());
    assertEquals("记录数", outputMeta.get().getValueMeta(2).getName());
    assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(2).getType());
    assertEquals("组内序号<&>", outputMeta.get().getValueMeta(3).getName());
    assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(3).getType());
    assertEquals(3, emitted.size(), "pass-all-rows must retain all source records");
    assertEquals("黄河<&>", emitted.get(0)[0]);
    assertEquals("甲", emitted.get(0)[1]);
    assertEquals(2L, ((Number) emitted.get(0)[2]).longValue());
    assertEquals(1L, ((Number) emitted.get(0)[3]).longValue());
    assertEquals("黄河<&>", emitted.get(1)[0]);
    assertEquals("乙", emitted.get(1)[1]);
    assertEquals(2L, ((Number) emitted.get(1)[2]).longValue());
    assertEquals(2L, ((Number) emitted.get(1)[3]).longValue());
    assertEquals("长江", emitted.get(2)[0]);
    assertEquals("丙", emitted.get(2)[1]);
    assertEquals(1L, ((Number) emitted.get(2)[2]).longValue());
    assertEquals(1L, ((Number) emitted.get(2)[3]).longValue());
    System.out.println("PROD_P0_GROUPBY_PASS_ALL_ROWS_GROUP_LINENR_JSON_HPL_ROWS=PASS");
  }

  /**
   * Native zero-input behavior is not a generic empty-list roundtrip: give_back_row
   * determines whether an aggregate row exists even when the upstream emits none.
   */
  @Test
  void groupByGiveBackRowEmitsZeroCountWithoutSourceRowsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin groupPlugin = plugins.findPluginWithId(TransformPluginType.class, "GroupBy");
    assertNotNull(gridPlugin);
    assertNotNull(groupPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> gridField = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Object column = gridField.getConstructor().newInstance();
    gridField.getMethod("setName", String.class).invoke(column, "流域<&>");
    gridField.getMethod("setType", String.class).invoke(column, "String");
    grid.getClass().getMethod("setDataGridFields", List.class)
        .invoke(grid, List.of(column));
    grid.getClass().getMethod("setDataLines", List.class)
        .invoke(grid, List.of());

    ITransformMeta group = plugins.loadClass(groupPlugin, ITransformMeta.class);
    group.setDefault();
    ClassLoader groupLoader = group.getClass().getClassLoader();
    Class<?> groupField = groupLoader.loadClass(
        "org.apache.hop.pipeline.transforms.groupby.GroupingField");
    Object grouping = groupField.getConstructor(String.class).newInstance("流域<&>");
    group.getClass().getMethod("setGroupingFields", List.class)
        .invoke(group, List.of(grouping));
    Class<?> aggregationType = groupLoader.loadClass(
        "org.apache.hop.pipeline.transforms.groupby.Aggregation");
    Object count = aggregationType.getConstructor().newInstance();
    aggregationType.getMethod("setField", String.class).invoke(count, "零行计数");
    aggregationType.getMethod("setSubject", String.class).invoke(count, "");
    aggregationType.getMethod("setTypeLabel", String.class).invoke(count, "COUNT_ANY");
    group.getClass().getMethod("setAggregations", List.class)
        .invoke(group, List.of(count));

    var contract = AutomaticConfigDescriptor.describe(group.getClass())
        .runtimePreconditions().stream()
        .filter(p -> p.code().equals("GROUPBY_EMPTY_INPUT_GIVE_BACK_ROW_EMITS_ZERO_COUNT"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/give_back_row", "/all_rows", "/group/field", "/fields/field"),
        contract.keyListPointers());

    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("GroupBy", "group-node", group);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("groupby-zero-input-give-back");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("groupby-zero-input-give-back.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("group-zero-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("group-zero-doc",
        new PipelineConfigResource.ReadRequest("group-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("all_rows", false);
    draft.put("give_back_row", true);
    assertEquals(1, draft.path("group").path("field").size());
    assertEquals(1, draft.path("fields").path("field").size());
    var write = resource.write("group-zero-doc",
        new PipelineConfigResource.WriteRequest("group-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("group-node").getTransform(), provider);
    assertTrue(wire.at("/give_back_row").isBoolean());
    assertTrue(wire.at("/give_back_row").booleanValue());
    assertTrue(wire.at("/all_rows").isBoolean());
    assertFalse(wire.at("/all_rows").booleanValue());
    assertEquals("流域<&>", wire.at("/group/field/0/name").asText());
    assertEquals("COUNT_ANY", wire.at("/fields/field/0/type").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("group-node", 0);
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
      assertEquals(0, engine.getErrors(), "zero-input GroupBy must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get(), "give_back_row must emit one aggregate row");
    assertEquals(2, outputMeta.get().size());
    assertEquals("流域<&>", outputMeta.get().getValueMeta(0).getName());
    assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(0).getType());
    assertEquals("零行计数", outputMeta.get().getValueMeta(1).getName());
    assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(1).getType());
    assertEquals(1, emitted.size(), "zero upstream rows must still yield one aggregate");
    assertNull(emitted.get(0)[0], "no group key value exists without input rows");
    assertEquals(0L, ((Number) emitted.get(0)[1]).longValue());
    System.out.println("PROD_P0_GROUPBY_EMPTY_INPUT_GIVE_BACK_ZERO_COUNT_JSON_HPL=PASS");
  }

  /**
   * FIRST/LAST ignore null subjects; FIRST_INCL_NULL/LAST_INCL_NULL preserve
   * their positional nulls. Test actual typed rows, not only metadata or
   * the GroupBy type-label serializer.
   */
  @Test
  void groupByFirstLastNullInclusionSurvivesConfigAndRealHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin groupPlugin = plugins.findPluginWithId(TransformPluginType.class, "GroupBy");
    assertNotNull(gridPlugin);
    assertNotNull(groupPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> fieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> columns = new ArrayList<>();
    for (String[] definition : new String[][] {
        {"流域<&>", "String"}, {"流量", "Integer"}}) {
      Object column = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(column, definition[0]);
      fieldType.getMethod("setType", String.class).invoke(column, definition[1]);
      columns.add(column);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, columns);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("黄河<&>", ""), List.of("黄河<&>", "7"),
        List.of("黄河<&>", ""), List.of("长江", "2"),
        List.of("长江", ""))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta group = plugins.loadClass(groupPlugin, ITransformMeta.class);
    group.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("GroupBy", "group-node", group);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("groupby-first-last-null-inclusion");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("groupby-first-last-null.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("group-null-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("group-null-doc",
        new PipelineConfigResource.ReadRequest("group-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("all_rows", false);
    draft.put("give_back_row", false);
    draft.putObject("group").putArray("field")
        .addObject().put("name", "流域<&>");
    var aggregates = draft.putObject("fields").putArray("field");
    for (String[] definition : new String[][] {
        {"首个非空", "FIRST"}, {"首项含空", "FIRST_INCL_NULL"},
        {"末个非空", "LAST"}, {"末项含空", "LAST_INCL_NULL"}}) {
      aggregates.addObject().put("aggregate", definition[0])
          .put("subject", "流量").put("type", definition[1]);
    }
    var write = resource.write("group-null-doc",
        new PipelineConfigResource.WriteRequest("group-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("group-node").getTransform(), provider);
    assertEquals("流域<&>", wire.at("/group/field/0/name").asText());
    assertEquals(4, wire.at("/fields/field").size());
    assertEquals("FIRST", wire.at("/fields/field/0/type").asText());
    assertEquals("FIRST_INCL_NULL", wire.at("/fields/field/1/type").asText());
    assertEquals("LAST", wire.at("/fields/field/2/type").asText());
    assertEquals("LAST_INCL_NULL", wire.at("/fields/field/3/type").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("group-node", 0);
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
      assertEquals(0, engine.getErrors(), "native GroupBy null handling must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get());
    assertEquals(5, outputMeta.get().size());
    for (int i = 1; i < 5; i++) {
      assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(i).getType());
    }
    assertEquals(2, emitted.size());
    assertEquals("黄河<&>", emitted.get(0)[0]);
    assertEquals(7L, ((Number) emitted.get(0)[1]).longValue());
    assertNull(emitted.get(0)[2], "FIRST_INCL_NULL must preserve the first null");
    assertEquals(7L, ((Number) emitted.get(0)[3]).longValue());
    assertNull(emitted.get(0)[4], "LAST_INCL_NULL must preserve the final null");
    assertEquals("长江", emitted.get(1)[0]);
    assertEquals(2L, ((Number) emitted.get(1)[1]).longValue());
    assertEquals(2L, ((Number) emitted.get(1)[2]).longValue());
    assertEquals(2L, ((Number) emitted.get(1)[3]).longValue());
    assertNull(emitted.get(1)[4]);
    System.out.println("PROD_P0_GROUPBY_FIRST_LAST_NULL_INCLUSION_JSON_HPL_ROWS=PASS");
  }


  /** Native COUNT_DISTINCT, COUNT_ALL and COUNT_ANY differ on duplicates and null subjects. */
  @Test
  void groupByDistinctAndNullCountsSurviveConfigAndRealHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin groupPlugin = plugins.findPluginWithId(TransformPluginType.class, "GroupBy");
    assertNotNull(gridPlugin);
    assertNotNull(groupPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> fieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> columns = new ArrayList<>();
    for (String[] definition : new String[][] {
        {"流域<&>", "String"}, {"样本", "Integer"}}) {
      Object column = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(column, definition[0]);
      fieldType.getMethod("setType", String.class).invoke(column, definition[1]);
      columns.add(column);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, columns);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("黄河<&>", "7"), List.of("黄河<&>", "7"),
        List.of("黄河<&>", "9"), List.of("黄河<&>", ""),
        List.of("长江", ""), List.of("长江", ""))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta group = plugins.loadClass(groupPlugin, ITransformMeta.class);
    group.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("GroupBy", "group-node", group);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("groupby-distinct-null-counts");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("groupby-distinct-null-counts.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("group-count-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("group-count-doc",
        new PipelineConfigResource.ReadRequest("group-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("all_rows", false);
    draft.put("give_back_row", false);
    draft.putObject("group").putArray("field")
        .addObject().put("name", "流域<&>");
    var aggregates = draft.putObject("fields").putArray("field");
    for (String[] definition : new String[][] {
        {"去重数", "COUNT_DISTINCT"}, {"非空数", "COUNT_ALL"},
        {"总行数", "COUNT_ANY"}}) {
      aggregates.addObject().put("aggregate", definition[0])
          .put("subject", "COUNT_ANY".equals(definition[1]) ? "" : "样本")
          .put("type", definition[1]);
    }
    var write = resource.write("group-count-doc",
        new PipelineConfigResource.WriteRequest("group-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("group-node").getTransform(), provider);
    assertEquals("流域<&>", wire.at("/group/field/0/name").asText());
    assertEquals(3, wire.at("/fields/field").size());
    assertEquals("COUNT_DISTINCT", wire.at("/fields/field/0/type").asText());
    assertEquals("COUNT_ALL", wire.at("/fields/field/1/type").asText());
    assertEquals("COUNT_ANY", wire.at("/fields/field/2/type").asText());
    assertEquals("样本", wire.at("/fields/field/0/subject").asText());
    assertEquals("", wire.at("/fields/field/2/subject").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("group-node", 0);
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
      assertEquals(0, engine.getErrors(), "native GroupBy counts must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get());
    assertEquals(4, outputMeta.get().size());
    assertEquals("流域<&>", outputMeta.get().getValueMeta(0).getName());
    for (int i = 1; i < 4; i++) {
      assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(i).getType());
    }
    assertEquals(2, emitted.size(), "six rows must form two contiguous groups");
    assertEquals("黄河<&>", emitted.get(0)[0]);
    assertEquals(2L, ((Number) emitted.get(0)[1]).longValue());
    assertEquals(3L, ((Number) emitted.get(0)[2]).longValue());
    assertEquals(4L, ((Number) emitted.get(0)[3]).longValue());
    assertEquals("长江", emitted.get(1)[0]);
    assertNull(emitted.get(1)[1], "COUNT_DISTINCT stays null when all subjects are null");
    assertEquals(0L, ((Number) emitted.get(1)[2]).longValue());
    assertEquals(2L, ((Number) emitted.get(1)[3]).longValue());
    System.out.println("PROD_P0_GROUPBY_DISTINCT_ALL_ANY_NULL_JSON_HPL_ROWS=PASS");
  }
}
