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
 * Native SortRows engine acceptance, distinct from metadata-only key checks:
 * T3 edits two ordered typed sort criteria through Hop JSON, persists real
 * XML to disk, reopens the HPL and checks actual multi-key row ordering.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class SortRowsFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }


  @Test
  void sortRowsCaseInsensitiveNonCollatorOrdersNativeRowsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin sortPlugin = plugins.findPluginWithId(TransformPluginType.class, "SortRows");
    assertNotNull(gridPlugin, "pinned DataGrid plugin required");
    assertNotNull(sortPlugin, "pinned SortRows plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> fieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("排序键<&>", "批次")) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> gridLines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("z", "r-z"), List.of("B", "r-B"),
        List.of("a", "r-a"), List.of("M", "r-M"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      gridLines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, gridLines);

    ITransformMeta sort = plugins.loadClass(sortPlugin, ITransformMeta.class);
    sort.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("SortRows", "sort-node", sort);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("sort-rows-case-insensitive-noncollator");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("sort-rows-case-insensitive.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("sort-case-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("sort-case-doc",
        new PipelineConfigResource.ReadRequest("sort-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("directory", tempDir.toAbsolutePath().toString());
    draft.put("sort_size", "1000");
    draft.put("unique_rows", false);
    draft.putObject("fields").putArray("field").addObject()
        .put("name", "排序键<&>")
        .put("ascending", true)
        .put("case_sensitive", false)
        .put("collator_enabled", false)
        .put("collator_strength", 0)
        .put("presorted", false);
    var write = resource.write("sort-case-doc",
        new PipelineConfigResource.WriteRequest("sort-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("sort-node").getTransform(), provider);
    assertEquals("排序键<&>", wire.at("/fields/field/0/name").asText());
    assertTrue(wire.at("/fields/field/0/case_sensitive").isBoolean());
    assertFalse(wire.at("/fields/field/0/case_sensitive").booleanValue());
    assertTrue(wire.at("/fields/field/0/collator_enabled").isBoolean());
    assertFalse(wire.at("/fields/field/0/collator_enabled").booleanValue());
    assertTrue(wire.at("/fields/field/0/ascending").booleanValue());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> emittedMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("sort-node", 0);
      assertNotNull(output, "real engine must prepare SortRows");
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          emittedMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native SortRows pipeline must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(emittedMeta.get());
    assertEquals(2, emittedMeta.get().size());
    assertTrue(emittedMeta.get().getValueMeta(0).isCaseInsensitive());
    assertTrue(emittedMeta.get().getValueMeta(0).isCollatorDisabled());
    assertFalse(emittedMeta.get().getValueMeta(0).isSortedDescending());
    assertEquals(4, emitted.size());
    // Pinned ValueMetaBase.compare() uses String.compareToIgnoreCase here.
    // Case-sensitive Java ordering would instead put B and M before a.
    assertEquals(List.of("r-a", "r-B", "r-M", "r-z"),
        emitted.stream().map(row -> (String) row[1]).toList());
    assertEquals(List.of("a", "B", "M", "z"),
        emitted.stream().map(row -> (String) row[0]).toList());
    System.out.println("PROD_P0_SORTROWS_CASE_INSENSITIVE_NONCOLLATOR_JSON_HPL_ROWS=PASS");
  }

  @Test
  void sortRowsTwoOrderedKeysExecuteNativeFullPipelineAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin sortPlugin = plugins.findPluginWithId(TransformPluginType.class, "SortRows");
    assertNotNull(gridPlugin, "pinned DataGrid plugin required");
    assertNotNull(sortPlugin, "pinned SortRows plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> fieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("优先级<&>", "站点", "标记")) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> gridLines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("1", "乙", "r4"),
        List.of("2", "乙", "r2"),
        List.of("1", "甲", "r1"),
        List.of("2", "甲", "r3"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      gridLines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, gridLines);

    ITransformMeta sort = plugins.loadClass(sortPlugin, ITransformMeta.class);
    sort.setDefault();
    var compatibility = AutomaticConfigDescriptor.describe(sort.getClass())
        .runtimePreconditions().stream()
        .filter(rule -> rule.code().equals(
            "SORT_ROWS_NONCOLLATOR_STRING_KEYS_USE_JAVA_CODE_UNIT_ORDER"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), compatibility.keyListPointers());
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("SortRows", "sort-node", sort);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("sort-rows-two-keys-full-pipeline");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("sort-rows-two-keys.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("sort-full-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("sort-full-doc",
        new PipelineConfigResource.ReadRequest("sort-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("directory", tempDir.toAbsolutePath().toString());
    draft.put("sort_size", "1000");
    draft.put("unique_rows", false);
    ArrayNode keys = draft.putObject("fields").putArray("field");
    keys.addObject()
        .put("name", "优先级<&>")
        .put("ascending", false)
        .put("case_sensitive", true)
        .put("collator_enabled", false)
        .put("collator_strength", 0)
        .put("presorted", false);
    keys.addObject()
        .put("name", "站点")
        .put("ascending", true)
        .put("case_sensitive", true)
        .put("collator_enabled", false)
        .put("collator_strength", 0)
        .put("presorted", false);

    var write = resource.write("sort-full-doc",
        new PipelineConfigResource.WriteRequest("sort-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("sort-node").getTransform(), provider);
    assertEquals(tempDir.toAbsolutePath().toString(), wire.at("/directory").asText());
    assertEquals("1000", wire.at("/sort_size").asText());
    assertTrue(wire.at("/unique_rows").isBoolean());
    assertFalse(wire.at("/unique_rows").booleanValue());
    assertEquals(2, wire.at("/fields/field").size());
    assertEquals("优先级<&>", wire.at("/fields/field/0/name").asText());
    assertTrue(wire.at("/fields/field/0/ascending").isBoolean());
    assertFalse(wire.at("/fields/field/0/ascending").booleanValue());
    assertTrue(wire.at("/fields/field/0/collator_strength").isIntegralNumber());
    assertEquals(0, wire.at("/fields/field/0/collator_strength").intValue());
    assertEquals("站点", wire.at("/fields/field/1/name").asText());
    assertTrue(wire.at("/fields/field/1/ascending").booleanValue());
    assertFalse(wire.at("/fields/field/1/collator_enabled").booleanValue());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> emittedMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("sort-node", 0);
      assertNotNull(output, "real engine must prepare SortRows");
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          emittedMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native SortRows pipeline must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(emittedMeta.get(), "SortRows must emit actual rows");
    assertEquals(3, emittedMeta.get().size());
    assertEquals("优先级<&>", emittedMeta.get().getValueMeta(0).getName());
    assertEquals(IValueMeta.TYPE_STRING, emittedMeta.get().getValueMeta(0).getType());
    assertTrue(emittedMeta.get().getValueMeta(0).isSortedDescending());
    assertEquals("站点", emittedMeta.get().getValueMeta(1).getName());
    assertFalse(emittedMeta.get().getValueMeta(1).isSortedDescending());
    assertEquals(4, emitted.size());
    // Native non-collator String comparison follows UTF-16 code-unit order:
    // 乙 (U+4E59) precedes 甲 (U+7532), despite common spoken ordering.
    assertEquals(List.of("r2", "r3", "r4", "r1"),
        emitted.stream().map(row -> (String) row[2]).toList(),
        "primary key descending and secondary key ascending must both affect real rows");
    assertEquals(List.of("2", "2", "1", "1"),
        emitted.stream().map(row -> (String) row[0]).toList());
    assertEquals(List.of("乙", "甲", "乙", "甲"),
        emitted.stream().map(row -> (String) row[1]).toList());
    System.out.println("PROD_P0_SORTROWS_FULL_PIPELINE_TWO_ORDERED_KEYS_JSON_HPL_ROWS=PASS");
  }

  /**
   * Regression: unique_rows is a DISTINCT on configured sort keys, including
   * when duplicate keys are distributed across multiple temporary sort runs.
   */
  @Test
  void sortRowsUniqueCompositeKeysDeduplicateAcrossSpilledRunsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin sortPlugin = plugins.findPluginWithId(TransformPluginType.class, "SortRows");
    assertNotNull(gridPlugin, "pinned DataGrid plugin required");
    assertNotNull(sortPlugin, "pinned SortRows plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> fieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("优先级<&>", "站点", "标记")) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> gridLines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("2", "乙", "r1"),
        List.of("1", "甲", "r2"),
        List.of("2", "乙", "r3"),
        List.of("2", "甲", "r4"),
        List.of("1", "甲", "r5"),
        List.of("2", "甲", "r6"),
        List.of("1", "乙", "r7"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      gridLines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, gridLines);

    ITransformMeta sort = plugins.loadClass(sortPlugin, ITransformMeta.class);
    sort.setDefault();
    var compatibility = AutomaticConfigDescriptor.describe(sort.getClass())
        .runtimePreconditions().stream()
        .filter(rule -> rule.code().equals(
            "SORT_ROWS_UNIQUE_KEYS_DROP_NONKEY_PAYLOAD_ACROSS_SPILLS"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/unique_rows", "/fields/field", "/sort_size"),
        compatibility.keyListPointers());
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("SortRows", "sort-node", sort);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("sort-rows-unique-composite-spills-pipeline");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("sort-rows-unique-composite-spills.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("sort-unique-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("sort-unique-doc",
        new PipelineConfigResource.ReadRequest("sort-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("directory", tempDir.toAbsolutePath().toString());
    draft.put("sort_size", "2");
    draft.put("unique_rows", true);
    ArrayNode keys = draft.putObject("fields").putArray("field");
    keys.addObject()
        .put("name", "优先级<&>")
        .put("ascending", false)
        .put("case_sensitive", true)
        .put("collator_enabled", false)
        .put("collator_strength", 0)
        .put("presorted", false);
    keys.addObject()
        .put("name", "站点")
        .put("ascending", true)
        .put("case_sensitive", true)
        .put("collator_enabled", false)
        .put("collator_strength", 0)
        .put("presorted", false);

    var write = resource.write("sort-unique-doc",
        new PipelineConfigResource.WriteRequest("sort-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("sort-node").getTransform(), provider);
    assertEquals(tempDir.toAbsolutePath().toString(), wire.at("/directory").asText());
    assertEquals("2", wire.at("/sort_size").asText());
    assertTrue(wire.at("/unique_rows").isBoolean());
    assertTrue(wire.at("/unique_rows").booleanValue());
    assertEquals(2, wire.at("/fields/field").size());
    assertEquals("优先级<&>", wire.at("/fields/field/0/name").asText());
    assertTrue(wire.at("/fields/field/0/ascending").isBoolean());
    assertFalse(wire.at("/fields/field/0/ascending").booleanValue());
    assertTrue(wire.at("/fields/field/0/collator_strength").isIntegralNumber());
    assertEquals(0, wire.at("/fields/field/0/collator_strength").intValue());
    assertEquals("站点", wire.at("/fields/field/1/name").asText());
    assertTrue(wire.at("/fields/field/1/ascending").booleanValue());
    assertFalse(wire.at("/fields/field/1/collator_enabled").booleanValue());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> emittedMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("sort-node", 0);
      assertNotNull(output, "real engine must prepare SortRows");
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          emittedMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native SortRows pipeline must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(emittedMeta.get(), "SortRows must emit actual rows");
    assertEquals(3, emittedMeta.get().size());
    assertEquals("优先级<&>", emittedMeta.get().getValueMeta(0).getName());
    assertEquals(IValueMeta.TYPE_STRING, emittedMeta.get().getValueMeta(0).getType());
    assertTrue(emittedMeta.get().getValueMeta(0).isSortedDescending());
    assertEquals("站点", emittedMeta.get().getValueMeta(1).getName());
    assertFalse(emittedMeta.get().getValueMeta(1).isSortedDescending());
    assertEquals(4, emitted.size(), "seven source rows must collapse to four unique key pairs");
    // With sort_size=2, duplicate composite keys span separate on-disk runs.
    // Hop compares only the configured sort keys, not the unselected payload.
    assertEquals(List.of("2", "2", "1", "1"),
        emitted.stream().map(row -> (String) row[0]).toList());
    assertEquals(List.of("乙", "甲", "乙", "甲"),
        emitted.stream().map(row -> (String) row[1]).toList());
    assertTrue(List.of("r1", "r3").contains(emitted.get(0)[2]));
    assertTrue(List.of("r4", "r6").contains(emitted.get(1)[2]));
    assertEquals("r7", emitted.get(2)[2]);
    assertTrue(List.of("r2", "r5").contains(emitted.get(3)[2]));
    System.out.println("PROD_P0_SORTROWS_UNIQUE_COMPOSITE_SPILLED_KEYS_JSON_HPL_ROWS=PASS");
  }


  /**
   * The pinned numeric comparator reverses null placement with ascending/descending.
   * Exercise both directions with actual missing INTEGER input, not just schema checks.
   */
  @Test
  void sortRowsNumericNullPlacementFollowsDirectionAfterRealHplDiskReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin sortPlugin = plugins.findPluginWithId(TransformPluginType.class, "SortRows");
    assertNotNull(gridPlugin);
    assertNotNull(sortPlugin);

    for (boolean ascending : new boolean[] {true, false}) {
      ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
      grid.setDefault();
      ClassLoader loader = grid.getClass().getClassLoader();
      Class<?> fieldType = loader.loadClass(
          "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
      Class<?> lineType = loader.loadClass(
          "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
      List<Object> gridFields = new ArrayList<>();
      for (String name : List.of("流量<&>", "标记")) {
        Object field = fieldType.getConstructor().newInstance();
        fieldType.getMethod("setName", String.class).invoke(field, name);
        fieldType.getMethod("setType", String.class)
            .invoke(field, name.equals("流量<&>") ? "Integer" : "String");
        gridFields.add(field);
      }
      grid.getClass().getMethod("setDataGridFields", List.class)
          .invoke(grid, gridFields);
      List<Object> lines = new ArrayList<>();
      for (List<String> values : List.of(
          List.of("10", "大值"),
          List.of("-1", "负值"),
          java.util.Arrays.asList(null, "缺测"),
          List.of("2", "小值"))) {
        Object line = lineType.getConstructor().newInstance();
        lineType.getMethod("setDatalines", List.class).invoke(line, values);
        lines.add(line);
      }
      grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

      ITransformMeta sort = plugins.loadClass(sortPlugin, ITransformMeta.class);
      sort.setDefault();
      var compatibility = AutomaticConfigDescriptor.describe(sort.getClass())
          .runtimePreconditions().stream()
          .filter(rule -> rule.code().equals(
              "SORT_ROWS_NUMERIC_NULL_PLACEMENT_FOLLOWS_DIRECTION"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/fields/field"), compatibility.keyListPointers());

      TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
      TransformMeta target = new TransformMeta("SortRows", "sort-node", sort);
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("sort-numeric-null-" + ascending);
      pipeline.addTransform(source);
      pipeline.addTransform(target);
      pipeline.addPipelineHop(new PipelineHopMeta(source, target));

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("sort-numeric-null-" + ascending + ".hpl");
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      String documentId = "sort-null-" + ascending;
      documents.put(new PipelineDocument(documentId, hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read(documentId,
          new PipelineConfigResource.ReadRequest("sort-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("directory", tempDir.toAbsolutePath().toString());
      draft.put("sort_size", "1000");
      draft.put("unique_rows", false);
      draft.putObject("fields").putArray("field").addObject()
          .put("name", "流量<&>")
          .put("ascending", ascending)
          .put("case_sensitive", true)
          .put("collator_enabled", false)
          .put("collator_strength", 0)
          .put("presorted", false);
      var write = resource.write(documentId,
          new PipelineConfigResource.WriteRequest("sort-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      assertEquals(2, reopened.nrTransforms());
      assertEquals(1, reopened.nrPipelineHops());
      JsonNode wire = ConfigJsonSerializer.toJson(
          reopened.findTransform("sort-node").getTransform(), provider);
      assertEquals("流量<&>", wire.at("/fields/field/0/name").asText());
      assertTrue(wire.at("/fields/field/0/ascending").isBoolean());
      assertEquals(ascending, wire.at("/fields/field/0/ascending").booleanValue());

      List<Object[]> rows = new CopyOnWriteArrayList<>();
      AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
      LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
      engine.setMetadataProvider(provider);
      try {
        engine.prepareExecution();
        ITransform output = engine.getRunThread("sort-node", 0);
        assertNotNull(output);
        output.addRowListener(new RowAdapter() {
          @Override
          public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
            outputMeta.set(rowMeta.clone());
            rows.add(row.clone());
          }
        });
        engine.startThreads();
        engine.waitUntilFinished();
        assertEquals(0, engine.getErrors(), "native SortRows must finish");
      } finally {
        if (!engine.isFinishedOrStopped()) engine.stopAll();
        engine.cleanup();
      }

      assertNotNull(outputMeta.get());
      assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(0).getType());
      assertEquals(!ascending, outputMeta.get().getValueMeta(0).isSortedDescending());
      assertEquals(4, rows.size());
      assertEquals(ascending
              ? List.of("缺测", "负值", "小值", "大值")
              : List.of("大值", "小值", "负值", "缺测"),
          rows.stream().map(row -> (String) row[1]).toList());
      assertNull(rows.get(ascending ? 0 : 3)[0], "missing numeric input stays null");
      assertEquals(ascending ? -1L : 10L, rows.get(ascending ? 1 : 0)[0]);
    }
    System.out.println("PROD_P0_SORTROWS_NUMERIC_NULL_ASC_DESC_JSON_HPL_ROWS=PASS");
  }


  /**
   * Full-engine follow-up to the descriptor/RowMeta comparator acceptance:
   * verify that native SortRows unique_rows actually emits different row
   * counts at PRIMARY and TERTIARY collation strength, independently of
   * case_sensitive, after the server config write and real HPL disk reopen.
   */
  @Test
  void sortRowsCollatorStrengthChangesActualDistinctRowsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    java.util.Locale originalLocale = java.util.Locale.getDefault();
    java.util.Locale.setDefault(java.util.Locale.US);
    try {
      PluginRegistry plugins = PluginRegistry.getInstance();
      IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
      IPlugin sortPlugin = plugins.findPluginWithId(TransformPluginType.class, "SortRows");
      assertNotNull(gridPlugin);
      assertNotNull(sortPlugin);

      ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
      grid.setDefault();
      ClassLoader gridLoader = grid.getClass().getClassLoader();
      Class<?> fieldType = gridLoader.loadClass(
          "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
      Class<?> lineType = gridLoader.loadClass(
          "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
      List<Object> fields = new ArrayList<>();
      for (String name : List.of("名称<&>", "来源")) {
        Object field = fieldType.getConstructor().newInstance();
        fieldType.getMethod("setName", String.class).invoke(field, name);
        fieldType.getMethod("setType", String.class).invoke(field, "String");
        fields.add(field);
      }
      grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, fields);
      List<Object> lines = new ArrayList<>();
      for (List<String> values : List.of(
          List.of("Resume", "capital"),
          List.of("resume", "lower"),
          List.of("résumé", "accent"),
          List.of("zeta", "other"))) {
        Object line = lineType.getConstructor().newInstance();
        lineType.getMethod("setDatalines", List.class).invoke(line, values);
        lines.add(line);
      }
      grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

      ITransformMeta sort = plugins.loadClass(sortPlugin, ITransformMeta.class);
      sort.setDefault();
      assertEquals(List.of("/fields/field", "/unique_rows"),
          AutomaticConfigDescriptor.describe(sort.getClass()).runtimePreconditions()
              .stream()
              .filter(rule -> rule.code().equals(
                  "SORT_ROWS_COLLATOR_OVERRIDES_CASE_FLAG_AND_INHERITS_ROW_LOCALE"))
              .findFirst().orElseThrow().keyListPointers());

      TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
      TransformMeta target = new TransformMeta("SortRows", "sort-node", sort);
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("sort-collator-native-unique-rows");
      pipeline.addTransform(source);
      pipeline.addTransform(target);
      pipeline.addPipelineHop(new PipelineHopMeta(source, target));

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("sort-collator-unique.hpl");
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("collator-unique-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);

      int[] strengths = {
          java.text.Collator.PRIMARY,
          java.text.Collator.PRIMARY,
          java.text.Collator.TERTIARY
      };
      boolean[] caseSensitiveFlags = {true, false, false};
      int[] expectedRows = {2, 2, 4};

      for (int scenario = 0; scenario < strengths.length; scenario++) {
        var read = resource.read("collator-unique-doc",
            new PipelineConfigResource.ReadRequest("sort-node"));
        assertEquals(200, read.getStatus());
        ObjectNode draft = ((ObjectNode)
            ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
        draft.put("directory", tempDir.toAbsolutePath().toString());
        draft.put("sort_size", "1000");
        draft.put("unique_rows", true);
        draft.putObject("fields").putArray("field").addObject()
            .put("name", "名称<&>")
            .put("ascending", true)
            .put("case_sensitive", caseSensitiveFlags[scenario])
            .put("collator_enabled", true)
            .put("collator_strength", strengths[scenario])
            .put("presorted", false);
        var write = resource.write("collator-unique-doc",
            new PipelineConfigResource.WriteRequest("sort-node", draft));
        assertEquals(200, write.getStatus());
        store.save(hpl, loaded);

        PipelineMeta reopened = store.open(hpl);
        assertEquals(1, reopened.nrPipelineHops());
        JsonNode wire = ConfigJsonSerializer.toJson(
            reopened.findTransform("sort-node").getTransform(), provider);
        assertTrue(wire.at("/unique_rows").booleanValue());
        assertEquals("名称<&>", wire.at("/fields/field/0/name").asText());
        assertTrue(wire.at("/fields/field/0/collator_enabled").booleanValue());
        assertTrue(wire.at("/fields/field/0/collator_strength").isIntegralNumber());
        assertEquals(strengths[scenario],
            wire.at("/fields/field/0/collator_strength").intValue());
        assertEquals(caseSensitiveFlags[scenario],
            wire.at("/fields/field/0/case_sensitive").booleanValue());

        List<Object[]> emitted = new CopyOnWriteArrayList<>();
        AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
        LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
        engine.setMetadataProvider(provider);
        try {
          engine.prepareExecution();
          ITransform output = engine.getRunThread("sort-node", 0);
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
          assertEquals(0, engine.getErrors(), "native SortRows engine must finish");
        } finally {
          if (!engine.isFinishedOrStopped()) engine.stopAll();
          engine.cleanup();
        }

        assertNotNull(outputMeta.get());
        IValueMeta keyMeta = outputMeta.get().getValueMeta(0);
        assertFalse(keyMeta.isCollatorDisabled());
        assertEquals(java.util.Locale.US, keyMeta.getCollatorLocale());
        assertEquals(strengths[scenario], keyMeta.getCollatorStrength());
        assertEquals(!caseSensitiveFlags[scenario], keyMeta.isCaseInsensitive());
        assertEquals(expectedRows[scenario], emitted.size(),
            "native unique_rows must use Collator strength, not case_sensitive");
        List<String> names = emitted.stream().map(row -> (String) row[0]).toList();
        assertTrue(names.contains("zeta"));
        if (strengths[scenario] == java.text.Collator.PRIMARY) {
          assertEquals(1, names.stream()
              .filter(name -> name.equals("Resume")
                  || name.equals("resume") || name.equals("résumé")).count());
        } else {
          assertTrue(names.containsAll(List.of("Resume", "resume", "résumé")));
        }
      }
      System.out.println("PROD_P0_SORTROWS_COLLATOR_NATIVE_UNIQUE_ROWS_HPL=PASS");
    } finally {
      java.util.Locale.setDefault(originalLocale);
    }
  }


  /**
   * Engine-level complement to the existing native-init compression test.
   * With a tiny sort_size, verify real external spill files have the GZIP
   * magic bytes only when the named runtime variable enables compression.
   * All files are isolated under the JUnit temp directory and are deleted
   * by native SortRows cleanup; inspect the first file during row emission.
   */
  @Test
  void sortRowsVariableOverridesStaticFlagForActualGzipSpillAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin sortPlugin = plugins.findPluginWithId(TransformPluginType.class, "SortRows");
    assertNotNull(gridPlugin);
    assertNotNull(sortPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> fields = new ArrayList<>();
    for (String name : List.of("排序键<&>", "批次")) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      fields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, fields);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("z", "批次五"), List.of("b", "批次二"),
        List.of("d", "批次四"), List.of("a", "批次一"),
        List.of("c", "批次三"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta sort = plugins.loadClass(sortPlugin, ITransformMeta.class);
    sort.setDefault();
    assertEquals(List.of("/compress", "/compress_variables", "/sort_size"),
        AutomaticConfigDescriptor.describe(sort.getClass()).runtimePreconditions()
            .stream().filter(rule -> rule.code().equals(
                "SORT_ROWS_COMPRESSION_VARIABLE_OVERRIDES_STATIC_FLAG_AT_INIT"))
            .findFirst().orElseThrow().keyListPointers());

    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("SortRows", "sort-node", sort);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("sort-variable-compressed-external-spill");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("sort-variable-compressed-spill.hpl");
    store.save(hpl, pipeline);

    boolean[] staticFlags = {false, true};
    String[] runtimeValues = {"Y", "N"};
    boolean[] expectedCompression = {true, false};
    for (int scenario = 0; scenario < staticFlags.length; scenario++) {
      PipelineMeta loaded = store.open(hpl);
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("sort-spill-doc", hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("sort-spill-doc",
          new PipelineConfigResource.ReadRequest("sort-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("compress", staticFlags[scenario]);
      draft.put("compress_variables", "SORT_GZIP");
      draft.put("sort_size", "2");
      draft.put("directory", tempDir.toAbsolutePath().toString());
      draft.put("sort_prefix", "sort-spill-" + scenario + "-雪");
      draft.put("unique_rows", false);
      draft.putObject("fields").putArray("field").addObject()
          .put("name", "排序键<&>")
          .put("ascending", true)
          .put("case_sensitive", true)
          .put("collator_enabled", false)
          .put("collator_strength", 0)
          .put("presorted", false);
      assertEquals(200, resource.write("sort-spill-doc",
          new PipelineConfigResource.WriteRequest("sort-node", draft)).getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      assertEquals(1, reopened.nrPipelineHops());
      JsonNode wire = ConfigJsonSerializer.toJson(
          reopened.findTransform("sort-node").getTransform(), provider);
      assertTrue(wire.at("/compress").isBoolean());
      assertEquals(staticFlags[scenario], wire.at("/compress").booleanValue());
      assertEquals("SORT_GZIP", wire.at("/compress_variables").asText());
      assertEquals("2", wire.at("/sort_size").asText());
      assertEquals("排序键<&>", wire.at("/fields/field/0/name").asText());

      List<Object[]> emitted = new CopyOnWriteArrayList<>();
      AtomicReference<List<Integer>> spillHeader = new AtomicReference<>();
      AtomicReference<String> inspectionError = new AtomicReference<>();
      LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
      engine.setMetadataProvider(provider);
      engine.setVariable("SORT_GZIP", runtimeValues[scenario]);
      try {
        engine.prepareExecution();
        ITransform output = engine.getRunThread("sort-node", 0);
        assertNotNull(output);
        Object nativeData = output.getData();
        output.addRowListener(new RowAdapter() {
          @Override
          public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
            emitted.add(row.clone());
            if (spillHeader.get() != null || inspectionError.get() != null) return;
            try {
              List<?> files = (List<?>) nativeData.getClass().getField("files").get(nativeData);
              if (files.isEmpty()) {
                inspectionError.set("no native external sort file at first output");
                return;
              }
              var firstFile = (org.apache.commons.vfs2.FileObject) files.get(0);
              try (var stream = firstFile.getContent().getInputStream()) {
                spillHeader.set(List.of(stream.read(), stream.read()));
              }
            } catch (Exception e) {
              inspectionError.set(e.toString());
            }
          }
        });
        engine.startThreads();
        engine.waitUntilFinished();
        assertEquals(0, engine.getErrors(), "native external SortRows must finish");
        assertEquals(expectedCompression[scenario],
            nativeData.getClass().getField("compressFiles").getBoolean(nativeData),
            "runtime variable must override the opposite static compress flag");
        List<?> files = (List<?>) nativeData.getClass().getField("files").get(nativeData);
        assertTrue(files.size() >= 2, "sort_size=2 must create multiple real spill files");
      } finally {
        if (!engine.isFinishedOrStopped()) engine.stopAll();
        engine.cleanup();
      }
      assertNull(inspectionError.get(), "native spill file must be readable before cleanup");
      assertNotNull(spillHeader.get());
      if (expectedCompression[scenario]) {
        assertEquals(List.of(0x1f, 0x8b), spillHeader.get(),
            "enabled variable must create a real GZIP spill file");
      } else {
        assertNotEquals(List.of(0x1f, 0x8b), spillHeader.get(),
            "disabled variable must create an uncompressed spill file");
      }
      assertEquals(5, emitted.size());
      assertEquals(List.of("a", "b", "c", "d", "z"),
          emitted.stream().map(row -> (String) row[0]).toList());
    }
    System.out.println("PROD_P0_SORTROWS_VARIABLE_GZIP_SPILL_NATIVE_ENGINE_JSON_HPL=PASS");
  }

}
