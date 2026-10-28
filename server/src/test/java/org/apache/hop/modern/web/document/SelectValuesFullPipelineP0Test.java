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
 * Native P0 engine acceptance for the three ordered SelectValues stages:
 * select/rename, remove, then metadata conversion. This verifies emitted
 * rows after T3 JSON editing and a real HPL disk reopen, rather than only
 * the serializer's metadata projection.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class SelectValuesFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void selectValuesThreeTypedListsConvertAndRemoveRealRowsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin selectPlugin = plugins.findPluginWithId(TransformPluginType.class, "SelectValues");
    assertNotNull(gridPlugin, "pinned DataGrid plugin required");
    assertNotNull(selectPlugin, "pinned SelectValues plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> fieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("原始量<&>", "临时", "测站")) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> gridLines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("42", "skip-a", "黄河"),
        List.of("7", "skip-b", "长江"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      gridLines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, gridLines);

    ITransformMeta select = plugins.loadClass(selectPlugin, ITransformMeta.class);
    select.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("SelectValues", "select-node", select);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("select-values-three-stages-full-pipeline");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("select-values-three-stages.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("select-full-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("select-full-doc",
        new PipelineConfigResource.ReadRequest("select-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    ObjectNode fields = (ObjectNode) draft.path("fields");
    assertTrue(fields.isObject(), "SelectValues wire must contain nested fields object");
    fields.put("select_unspecified", false);
    ArrayNode selected = fields.putArray("field");
    selected.addObject().put("name", "测站").put("rename", "站点");
    selected.addObject().put("name", "原始量<&>").put("rename", "阶段量<&>");
    selected.addObject().put("name", "临时").put("rename", "待删");
    fields.putArray("remove").addObject().put("name", "待删");
    fields.putArray("meta").addObject()
        .put("name", "阶段量<&>").put("rename", "流量_整数")
        .put("type", "Integer").put("length", -1).put("precision", -1);

    var write = resource.write("select-full-doc",
        new PipelineConfigResource.WriteRequest("select-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("select-node").getTransform(), provider);
    assertTrue(wire.at("/fields/select_unspecified").isBoolean());
    assertFalse(wire.at("/fields/select_unspecified").booleanValue());
    assertEquals(3, wire.at("/fields/field").size());
    assertEquals("测站", wire.at("/fields/field/0/name").asText());
    assertEquals("站点", wire.at("/fields/field/0/rename").asText());
    assertEquals("原始量<&>", wire.at("/fields/field/1/name").asText());
    assertEquals("阶段量<&>", wire.at("/fields/field/1/rename").asText());
    assertEquals("临时", wire.at("/fields/field/2/name").asText());
    assertEquals("待删", wire.at("/fields/remove/0/name").asText());
    assertEquals("阶段量<&>", wire.at("/fields/meta/0/name").asText());
    assertEquals("流量_整数", wire.at("/fields/meta/0/rename").asText());
    assertEquals("Integer", wire.at("/fields/meta/0/type").asText());
    assertTrue(wire.at("/fields/meta/0/length").isIntegralNumber());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> emittedMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("select-node", 0);
      assertNotNull(output, "real engine must prepare SelectValues");
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          emittedMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native SelectValues pipeline must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(emittedMeta.get(), "SelectValues must emit real rows");
    assertEquals(2, emittedMeta.get().size(), "removed column must not be emitted");
    assertEquals("站点", emittedMeta.get().getValueMeta(0).getName());
    assertEquals(IValueMeta.TYPE_STRING, emittedMeta.get().getValueMeta(0).getType());
    assertEquals("流量_整数", emittedMeta.get().getValueMeta(1).getName());
    assertEquals(IValueMeta.TYPE_INTEGER, emittedMeta.get().getValueMeta(1).getType());
    assertEquals(2, emitted.size());
    assertEquals("黄河", emitted.get(0)[0]);
    assertEquals(42L, ((Number) emitted.get(0)[1]).longValue());
    assertEquals("长江", emitted.get(1)[0]);
    assertEquals(7L, ((Number) emitted.get(1)[1]).longValue());
    System.out.println("PROD_P0_SELECTVALUES_FULL_PIPELINE_THREE_STAGES_TYPED_ROWS=PASS");
  }

  @Test
  void selectValuesExplicitSelectionPrecedesAlphabetizedUnspecifiedRealRowsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin selectPlugin = plugins.findPluginWithId(TransformPluginType.class, "SelectValues");
    assertNotNull(gridPlugin);
    assertNotNull(selectPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> columns = new ArrayList<>();
    for (String name : List.of("zeta", "beta", "黄河流量<&>", "alpha")) {
      Object column = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(column, name);
      fieldType.getMethod("setType", String.class).invoke(column, "String");
      columns.add(column);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, columns);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("z-1", "b-1", "42", "a-1"),
        List.of("z-2", "b-2", "7", "a-2"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta select = plugins.loadClass(selectPlugin, ITransformMeta.class);
    select.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("SelectValues", "select-node", select);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("select-values-unspecified-real-rows");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("select-values-unspecified.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("select-unspecified-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("select-unspecified-doc",
        new PipelineConfigResource.ReadRequest("select-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    ObjectNode fields = (ObjectNode) draft.path("fields");
    assertTrue(fields.isObject());
    fields.put("select_unspecified", true);
    fields.putArray("field").addObject()
        .put("name", "黄河流量<&>").put("rename", "选中_流量<&>");
    fields.putArray("remove");
    fields.putArray("meta");
    var write = resource.write("select-unspecified-doc",
        new PipelineConfigResource.WriteRequest("select-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("select-node").getTransform(), provider);
    assertTrue(wire.at("/fields/select_unspecified").isBoolean());
    assertTrue(wire.at("/fields/select_unspecified").booleanValue());
    assertEquals(1, wire.at("/fields/field").size());
    assertEquals("黄河流量<&>", wire.at("/fields/field/0/name").asText());
    assertEquals("选中_流量<&>", wire.at("/fields/field/0/rename").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> emittedMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("select-node", 0);
      assertNotNull(output);
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          emittedMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native SelectValues must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(emittedMeta.get());
    assertEquals(4, emittedMeta.get().size());
    assertEquals(List.of("选中_流量<&>", "alpha", "beta", "zeta"),
        List.of(emittedMeta.get().getValueMeta(0).getName(),
            emittedMeta.get().getValueMeta(1).getName(),
            emittedMeta.get().getValueMeta(2).getName(),
            emittedMeta.get().getValueMeta(3).getName()));
    for (int i = 0; i < 4; i++) {
      assertEquals(IValueMeta.TYPE_STRING, emittedMeta.get().getValueMeta(i).getType());
    }
    assertEquals(2, emitted.size());
    assertEquals(List.of("42", "a-1", "b-1", "z-1"),
        List.of(emitted.get(0)));
    assertEquals(List.of("7", "a-2", "b-2", "z-2"),
        List.of(emitted.get(1)));
    System.out.println("PROD_P0_SELECTVALUES_UNSPECIFIED_ALPHABETICAL_REAL_ROWS=PASS");
  }

  @Test
  void selectValuesLocalizedNumberConversionUsesDecimalAndGroupingSymbolsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin selectPlugin = plugins.findPluginWithId(TransformPluginType.class, "SelectValues");
    assertNotNull(gridPlugin);
    assertNotNull(selectPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> columns = new ArrayList<>();
    for (String name : List.of("流量文本<&>", "测站")) {
      Object column = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(column, name);
      fieldType.getMethod("setType", String.class).invoke(column, "String");
      columns.add(column);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, columns);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("1.234,50", "黄河"),
        List.of("-2.345,75", "长江"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta select = plugins.loadClass(selectPlugin, ITransformMeta.class);
    select.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("SelectValues", "select-node", select);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("select-values-locale-number-conversion");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("select-values-locale-number.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("select-locale-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("select-locale-doc",
        new PipelineConfigResource.ReadRequest("select-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    ObjectNode fields = (ObjectNode) draft.path("fields");
    assertTrue(fields.isObject());
    fields.putArray("meta").addObject()
        .put("name", "流量文本<&>")
        .put("rename", "流量_数值")
        .put("type", "Number")
        .put("length", -1)
        .put("precision", -1)
        .put("conversion_mask", "#,##0.00")
        .put("decimal_symbol", ",")
        .put("grouping_symbol", ".");
    var write = resource.write("select-locale-doc",
        new PipelineConfigResource.WriteRequest("select-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("select-node").getTransform(), provider);
    assertEquals(1, wire.at("/fields/meta").size());
    assertEquals("流量文本<&>", wire.at("/fields/meta/0/name").asText());
    assertEquals("流量_数值", wire.at("/fields/meta/0/rename").asText());
    assertEquals("Number", wire.at("/fields/meta/0/type").asText());
    assertEquals("#,##0.00", wire.at("/fields/meta/0/conversion_mask").asText());
    assertEquals(",", wire.at("/fields/meta/0/decimal_symbol").asText());
    assertEquals(".", wire.at("/fields/meta/0/grouping_symbol").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> emittedMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("select-node", 0);
      assertNotNull(output);
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          emittedMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "localized native number conversion must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(emittedMeta.get());
    assertEquals(2, emittedMeta.get().size());
    assertEquals("流量_数值", emittedMeta.get().getValueMeta(0).getName());
    assertEquals(IValueMeta.TYPE_NUMBER, emittedMeta.get().getValueMeta(0).getType());
    assertEquals("测站", emittedMeta.get().getValueMeta(1).getName());
    assertEquals(2, emitted.size());
    assertEquals(1234.50, ((Number) emitted.get(0)[0]).doubleValue(), 0.000001);
    assertEquals("黄河", emitted.get(0)[1]);
    assertEquals(-2345.75, ((Number) emitted.get(1)[0]).doubleValue(), 0.000001);
    assertEquals("长江", emitted.get(1)[1]);
    System.out.println("PROD_P0_SELECTVALUES_LOCALE_DECIMAL_GROUPING_JSON_HPL_ROWS=PASS");
  }

  /**
   * A Number (double) cannot represent a long decimal measurement exactly.
   * Verify that native SelectValues BigNumber conversion keeps the full decimal
   * through T3 JSON editing, real HPL disk reopen, and actual row execution.
   */
  @Test
  void selectValuesBigNumberRetainsExactHighPrecisionAfterRealHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin selectPlugin = plugins.findPluginWithId(TransformPluginType.class, "SelectValues");
    assertNotNull(gridPlugin);
    assertNotNull(selectPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> columns = new ArrayList<>();
    for (String name : List.of("精确水位<&>", "测站")) {
      Object column = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(column, name);
      fieldType.getMethod("setType", String.class).invoke(column, "String");
      columns.add(column);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, columns);
    String positive = "12345678901234567890.12345678901234567890";
    String negative = "-0.000000000000000000000123";
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of(positive, "黄河"), List.of(negative, "长江"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta select = plugins.loadClass(selectPlugin, ITransformMeta.class);
    select.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("SelectValues", "select-node", select);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("select-values-exact-big-number");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("select-values-exact-big-number.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("select-big-number-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("select-big-number-doc",
        new PipelineConfigResource.ReadRequest("select-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    ObjectNode fields = (ObjectNode) draft.path("fields");
    assertTrue(fields.isObject());
    fields.putArray("meta").addObject()
        .put("name", "精确水位<&>")
        .put("rename", "高精度水位")
        .put("type", "BigNumber")
        .put("length", -1)
        .put("precision", -1);
    var write = resource.write("select-big-number-doc",
        new PipelineConfigResource.WriteRequest("select-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("select-node").getTransform(), provider);
    assertEquals(1, wire.at("/fields/meta").size());
    assertEquals("精确水位<&>", wire.at("/fields/meta/0/name").asText());
    assertEquals("高精度水位", wire.at("/fields/meta/0/rename").asText());
    assertEquals("BigNumber", wire.at("/fields/meta/0/type").asText());
    assertTrue(wire.at("/fields/meta/0/length").isIntegralNumber());
    assertEquals(-1, wire.at("/fields/meta/0/precision").intValue());
    var contract = AutomaticConfigDescriptor.describe(
        reopened.findTransform("select-node").getTransform().getClass())
        .runtimePreconditions().stream()
        .filter(p -> p.code().equals("SELECT_VALUES_BIGNUMBER_EXACT_DECIMAL_CONVERSION"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/meta"), contract.keyListPointers());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> emittedMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("select-node", 0);
      assertNotNull(output);
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          emittedMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native BigNumber conversion must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(emittedMeta.get());
    assertEquals(2, emittedMeta.get().size());
    assertEquals("高精度水位", emittedMeta.get().getValueMeta(0).getName());
    assertEquals(IValueMeta.TYPE_BIGNUMBER, emittedMeta.get().getValueMeta(0).getType());
    assertEquals("测站", emittedMeta.get().getValueMeta(1).getName());
    assertEquals(2, emitted.size());
    assertInstanceOf(java.math.BigDecimal.class, emitted.get(0)[0]);
    assertEquals(0, new java.math.BigDecimal(positive)
        .compareTo((java.math.BigDecimal) emitted.get(0)[0]));
    assertEquals("黄河", emitted.get(0)[1]);
    assertInstanceOf(java.math.BigDecimal.class, emitted.get(1)[0]);
    assertEquals(0, new java.math.BigDecimal(negative)
        .compareTo((java.math.BigDecimal) emitted.get(1)[0]));
    assertEquals("长江", emitted.get(1)[1]);
    System.out.println("PROD_P0_SELECTVALUES_EXACT_BIGNUMBER_JSON_HPL_ROWS=PASS");
  }

  /**
   * A day-first timestamp in a STRING column is not an ISO date and cannot be
   * interpreted by simply changing the declared output type. Verify that
   * SelectValues applies its ordered fields.meta conversion_mask to real rows
   * after the T3 config API edit and an actual HPL disk reopen.
   */
  @Test
  void selectValuesDayFirstDateTimeMaskParsesRealRowsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin selectPlugin = plugins.findPluginWithId(TransformPluginType.class, "SelectValues");
    assertNotNull(gridPlugin);
    assertNotNull(selectPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> columns = new ArrayList<>();
    for (String name : List.of("观测时间<&>", "测站")) {
      Object column = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(column, name);
      fieldType.getMethod("setType", String.class).invoke(column, "String");
      columns.add(column);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, columns);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("29/02/2024 13:45:20", "黄河"),
        List.of("28/10/2026 07:08:09", "长江"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta select = plugins.loadClass(selectPlugin, ITransformMeta.class);
    select.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("SelectValues", "select-node", select);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("select-values-day-first-date-time-mask");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("select-values-day-first-date.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("select-date-mask-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("select-date-mask-doc",
        new PipelineConfigResource.ReadRequest("select-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    ObjectNode fields = (ObjectNode) draft.path("fields");
    assertTrue(fields.isObject());
    fields.putArray("meta").addObject()
        .put("name", "观测时间<&>")
        .put("rename", "观测时刻")
        .put("type", "Date")
        .put("length", -1)
        .put("precision", -1)
        .put("conversion_mask", "dd/MM/yyyy HH:mm:ss");
    var write = resource.write("select-date-mask-doc",
        new PipelineConfigResource.WriteRequest("select-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("select-node").getTransform(), provider);
    assertEquals(1, wire.at("/fields/meta").size());
    assertEquals("观测时间<&>", wire.at("/fields/meta/0/name").asText());
    assertEquals("观测时刻", wire.at("/fields/meta/0/rename").asText());
    assertEquals("Date", wire.at("/fields/meta/0/type").asText());
    assertEquals("dd/MM/yyyy HH:mm:ss",
        wire.at("/fields/meta/0/conversion_mask").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> emittedMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("select-node", 0);
      assertNotNull(output);
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          emittedMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native date-mask conversion must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(emittedMeta.get());
    assertEquals(2, emittedMeta.get().size());
    assertEquals("观测时刻", emittedMeta.get().getValueMeta(0).getName());
    assertEquals(IValueMeta.TYPE_DATE, emittedMeta.get().getValueMeta(0).getType());
    assertEquals("测站", emittedMeta.get().getValueMeta(1).getName());
    assertEquals(2, emitted.size());
    var outputFormat = new java.text.SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);
    for (Object[] row : emitted) {
      assertInstanceOf(java.util.Date.class, row[0]);
      String formatted = outputFormat.format((java.util.Date) row[0]);
      if ("黄河".equals(row[1])) {
        assertEquals("2024-02-29 13:45:20", formatted);
      } else {
        assertEquals("长江", row[1]);
        assertEquals("2026-10-28 07:08:09", formatted);
      }
    }
    System.out.println("PROD_P0_SELECTVALUES_DAY_FIRST_DATE_MASK_JSON_HPL_ROWS=PASS");
  }


  /**
   * Hop's native STRING-to-BOOLEAN conversion is permissive: unknown non-empty
   * tokens become false, not an error. Preserve this behavior and its data
   * quality implications across the T3 JSON editor and real HPL disk reopen.
   */
  @Test
  void selectValuesBooleanTokensAndUnknownFallbackExecuteAfterRealHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin selectPlugin = plugins.findPluginWithId(TransformPluginType.class, "SelectValues");
    assertNotNull(gridPlugin);
    assertNotNull(selectPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> columns = new ArrayList<>();
    for (String name : List.of("预警标记<&>", "测站")) {
      Object column = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(column, name);
      fieldType.getMethod("setType", String.class).invoke(column, "String");
      columns.add(column);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, columns);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("Y", "黄河-1"),
        List.of("true", "黄河-2"),
        List.of("YES", "黄河-3"),
        List.of("1", "黄河-4"),
        List.of("N", "长江-1"),
        List.of("false", "长江-2"),
        List.of("0", "长江-3"),
        List.of("UNKNOWN", "长江-4"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta select = plugins.loadClass(selectPlugin, ITransformMeta.class);
    select.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("SelectValues", "select-node", select);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("select-values-boolean-token-compatibility");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("select-values-boolean-token-compatibility.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("select-boolean-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("select-boolean-doc",
        new PipelineConfigResource.ReadRequest("select-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    ObjectNode fields = (ObjectNode) draft.path("fields");
    assertTrue(fields.isObject());
    fields.putArray("meta").addObject()
        .put("name", "预警标记<&>")
        .put("rename", "预警布尔")
        .put("type", "Boolean")
        .put("length", -1)
        .put("precision", -1);
    var write = resource.write("select-boolean-doc",
        new PipelineConfigResource.WriteRequest("select-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("select-node").getTransform(), provider);
    assertEquals(1, wire.at("/fields/meta").size());
    assertEquals("预警标记<&>", wire.at("/fields/meta/0/name").asText());
    assertEquals("预警布尔", wire.at("/fields/meta/0/rename").asText());
    assertEquals("Boolean", wire.at("/fields/meta/0/type").asText());
    var contract = AutomaticConfigDescriptor.describe(
        reopened.findTransform("select-node").getTransform().getClass())
        .runtimePreconditions().stream()
        .filter(p -> p.code().equals("SELECT_VALUES_STRING_BOOLEAN_UNKNOWN_TOKENS_MAP_FALSE"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/meta"), contract.keyListPointers());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> emittedMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("select-node", 0);
      assertNotNull(output);
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          emittedMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native Boolean conversion must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(emittedMeta.get());
    assertEquals(2, emittedMeta.get().size());
    assertEquals("预警布尔", emittedMeta.get().getValueMeta(0).getName());
    assertEquals(IValueMeta.TYPE_BOOLEAN, emittedMeta.get().getValueMeta(0).getType());
    assertEquals("测站", emittedMeta.get().getValueMeta(1).getName());
    assertEquals(8, emitted.size());
    var expected = java.util.Map.of(
        "黄河-1", true, "黄河-2", true, "黄河-3", true, "黄河-4", true,
        "长江-1", false, "长江-2", false, "长江-3", false, "长江-4", false);
    for (Object[] row : emitted) {
      assertInstanceOf(Boolean.class, row[0]);
      assertEquals(expected.get((String) row[1]), row[0], (String) row[1]);
    }
    System.out.println("PROD_P0_SELECTVALUES_BOOLEAN_UNKNOWN_TOKEN_JSON_HPL_ROWS=PASS");
  }
}
