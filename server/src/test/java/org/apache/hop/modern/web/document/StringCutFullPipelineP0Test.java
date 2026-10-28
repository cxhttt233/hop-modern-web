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
 * Native StringCut execution acceptance: two ordered rules, one new alias and
 * one in-place replacement, survive T3 config editing and real HPL disk reopen.
 * Unlike metadata-only checks this proves the emitted row values.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class StringCutFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void stringCutOrderedAliasAndInPlaceRulesExecuteFullPipelineAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin cutPlugin = plugins.findPluginWithId(TransformPluginType.class, "StringCut");
    assertNotNull(gridPlugin, "pinned DataGrid plugin required");
    assertNotNull(cutPlugin, "pinned StringCut plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("测站<&>", "编号")) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> gridLines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("黄河水网", "A1234"),
        List.of("长江数字", "B4567"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      gridLines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, gridLines);

    ITransformMeta cut = plugins.loadClass(cutPlugin, ITransformMeta.class);
    cut.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("StringCut", "cut-node", cut);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("stringcut-alias-inplace-full-pipeline");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("stringcut-alias-inplace.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("stringcut-full-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("stringcut-full-doc",
        new PipelineConfigResource.ReadRequest("cut-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    ArrayNode rules = draft.putObject("fields").putArray("field");
    rules.addObject()
        .put("in_stream_name", "测站<&>")
        .put("out_stream_name", "截取站点")
        .put("cut_from", "0")
        .put("cut_to", "2");
    rules.addObject()
        .put("in_stream_name", "编号")
        .put("out_stream_name", "")
        .put("cut_from", "1")
        .put("cut_to", "3");

    var write = resource.write("stringcut-full-doc",
        new PipelineConfigResource.WriteRequest("cut-node", draft));
    assertEquals(200, write.getStatus(), "ordered native StringCut rules must be accepted");
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("cut-node").getTransform(), provider);
    assertEquals(2, wire.at("/fields/field").size());
    assertEquals("测站<&>", wire.at("/fields/field/0/in_stream_name").asText());
    assertEquals("截取站点", wire.at("/fields/field/0/out_stream_name").asText());
    assertEquals("0", wire.at("/fields/field/0/cut_from").asText());
    assertEquals("2", wire.at("/fields/field/0/cut_to").asText());
    assertEquals("编号", wire.at("/fields/field/1/in_stream_name").asText());
    assertEquals("1", wire.at("/fields/field/1/cut_from").asText());
    assertEquals("3", wire.at("/fields/field/1/cut_to").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("cut-node", 0);
      assertNotNull(output, "native StringCut engine must prepare");
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          outputMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native StringCut pipeline must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get(), "StringCut must emit real rows");
    assertEquals(3, outputMeta.get().size(), "alias must append one column");
    assertEquals("测站<&>", outputMeta.get().getValueMeta(0).getName());
    assertEquals("编号", outputMeta.get().getValueMeta(1).getName());
    assertEquals("截取站点", outputMeta.get().getValueMeta(2).getName());
    for (int i = 0; i < 3; i++) {
      assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(i).getType());
    }
    assertEquals(2, emitted.size());
    assertEquals("黄河水网", emitted.get(0)[0], "aliased cut must preserve source");
    assertEquals("12", emitted.get(0)[1], "second rule must cut in-place");
    assertEquals("黄河", emitted.get(0)[2], "first rule must append cut alias");
    assertEquals("长江数字", emitted.get(1)[0]);
    assertEquals("45", emitted.get(1)[1]);
    assertEquals("长江", emitted.get(1)[2]);
    System.out.println("PROD_P0_STRINGCUT_FULL_PIPELINE_ALIAS_INPLACE_JSON_HPL_ROWS=PASS");
  }

  @Test
  void stringCutNegativeOffsetsAndOutOfRangeNullSurviveRealHplDiskReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin cutPlugin = plugins.findPluginWithId(TransformPluginType.class, "StringCut");
    assertNotNull(gridPlugin);
    assertNotNull(cutPlugin);

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
    grid.getClass().getMethod("setDataGridFields", List.class)
        .invoke(grid, List.of(field));
    List<Object> lines = new ArrayList<>();
    for (String value : List.of("黄河水网", "长江数字")) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, List.of(value));
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta cut = plugins.loadClass(cutPlugin, ITransformMeta.class);
    cut.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("StringCut", "cut-node", cut);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("stringcut-native-negative-offsets");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("stringcut-negative-offsets.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("stringcut-negative-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("stringcut-negative-doc",
        new PipelineConfigResource.ReadRequest("cut-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    ArrayNode rules = draft.putObject("fields").putArray("field");
    rules.addObject()
        .put("in_stream_name", "测站<&>")
        .put("out_stream_name", "双负偏移")
        .put("cut_from", "-1")
        .put("cut_to", "-3");
    rules.addObject()
        .put("in_stream_name", "测站<&>")
        .put("out_stream_name", "末尾两字")
        .put("cut_from", "0")
        .put("cut_to", "-2");
    rules.addObject()
        .put("in_stream_name", "测站<&>")
        .put("out_stream_name", "越界为空")
        .put("cut_from", "9")
        .put("cut_to", "12");
    var write = resource.write("stringcut-negative-doc",
        new PipelineConfigResource.WriteRequest("cut-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("cut-node").getTransform(), provider);
    assertEquals(3, wire.at("/fields/field").size());
    assertEquals("-1", wire.at("/fields/field/0/cut_from").asText());
    assertEquals("-3", wire.at("/fields/field/0/cut_to").asText());
    assertEquals("双负偏移", wire.at("/fields/field/0/out_stream_name").asText());
    assertEquals("0", wire.at("/fields/field/1/cut_from").asText());
    assertEquals("-2", wire.at("/fields/field/1/cut_to").asText());
    assertEquals("9", wire.at("/fields/field/2/cut_from").asText());
    assertEquals("12", wire.at("/fields/field/2/cut_to").asText());

    var contract = AutomaticConfigDescriptor.describe(
        reopened.findTransform("cut-node").getTransform().getClass())
        .runtimePreconditions().stream()
        .filter(p -> p.code().equals("STRING_CUT_NEGATIVE_OFFSETS_AND_OUT_OF_RANGE_NULL"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), contract.keyListPointers());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("cut-node", 0);
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
    assertEquals(4, outputMeta.get().size());
    assertEquals(List.of("测站<&>", "双负偏移", "末尾两字", "越界为空"),
        outputMeta.get().getValueMetaList().stream().map(IValueMeta::getName).toList());
    for (int i = 0; i < 4; i++) {
      assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(i).getType());
    }
    assertEquals(2, emitted.size());
    assertArrayEquals(new Object[] {"黄河水网", "河水", "水网", null}, emitted.get(0));
    assertArrayEquals(new Object[] {"长江数字", "江数", "数字", null}, emitted.get(1));
    System.out.println("PROD_P0_STRINGCUT_NEGATIVE_OFFSETS_OUT_OF_RANGE_NULL_JSON_HPL_ROWS=PASS");
  }


  /**
   * StringCut's offsets index Java UTF-16 code units, not Unicode code points.
   * A supplementary symbol occupies two units; test boundaries that preserve
   * the whole symbol while distinguishing the native behavior from code-point
   * slicing. This also proves that T3's ordered LIST survives HPL disk reopen.
   */
  @Test
  void stringCutSupplementaryUnicodeUsesUtf16CodeUnitOffsetsAfterRealHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin cutPlugin = plugins.findPluginWithId(TransformPluginType.class, "StringCut");
    assertNotNull(gridPlugin);
    assertNotNull(cutPlugin);

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
    grid.getClass().getMethod("setDataGridFields", List.class)
        .invoke(grid, List.of(field));
    List<Object> lines = new ArrayList<>();
    for (String value : List.of("水😀网", "河🚀湖")) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, List.of(value));
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta cut = plugins.loadClass(cutPlugin, ITransformMeta.class);
    cut.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("StringCut", "cut-node", cut);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("stringcut-utf16-code-unit-boundaries");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("stringcut-utf16.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("stringcut-utf16-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("stringcut-utf16-doc",
        new PipelineConfigResource.ReadRequest("cut-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    ArrayNode rules = draft.putObject("fields").putArray("field");
    rules.addObject()
        .put("in_stream_name", "测站<&>")
        .put("out_stream_name", "补充字符")
        .put("cut_from", "1")
        .put("cut_to", "3");
    rules.addObject()
        .put("in_stream_name", "测站<&>")
        .put("out_stream_name", "三代码单元前缀")
        .put("cut_from", "0")
        .put("cut_to", "3");
    var write = resource.write("stringcut-utf16-doc",
        new PipelineConfigResource.WriteRequest("cut-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("cut-node").getTransform(), provider);
    assertEquals(2, wire.at("/fields/field").size());
    assertEquals("测站<&>", wire.at("/fields/field/0/in_stream_name").asText());
    assertEquals("1", wire.at("/fields/field/0/cut_from").asText());
    assertEquals("3", wire.at("/fields/field/0/cut_to").asText());
    assertEquals("补充字符", wire.at("/fields/field/0/out_stream_name").asText());
    assertEquals("0", wire.at("/fields/field/1/cut_from").asText());
    assertEquals("3", wire.at("/fields/field/1/cut_to").asText());
    assertEquals("三代码单元前缀", wire.at("/fields/field/1/out_stream_name").asText());
    var contract = AutomaticConfigDescriptor.describe(
        reopened.findTransform("cut-node").getTransform().getClass())
        .runtimePreconditions().stream()
        .filter(p -> p.code().equals("STRING_CUT_NEGATIVE_OFFSETS_AND_OUT_OF_RANGE_NULL"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), contract.keyListPointers());
    assertTrue(contract.explanation().contains("UTF-16"));

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("cut-node", 0);
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
    assertEquals(List.of("测站<&>", "补充字符", "三代码单元前缀"),
        outputMeta.get().getValueMetaList().stream().map(IValueMeta::getName).toList());
    for (int i = 0; i < 3; i++) {
      assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(i).getType());
    }
    assertEquals(2, emitted.size());
    assertArrayEquals(new Object[] {"水😀网", "😀", "水😀"}, emitted.get(0));
    assertArrayEquals(new Object[] {"河🚀湖", "🚀", "河🚀"}, emitted.get(1));
    System.out.println("PROD_P0_STRINGCUT_SUPPLEMENTARY_UTF16_OFFSETS_JSON_HPL_ROWS=PASS");
  }
}
