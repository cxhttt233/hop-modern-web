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
 * Production engine acceptance: a real DataGrid -> ConcatFields hop executes the
 * T3 JSON-edited nested configuration after saving and reopening the HPL on disk.
 * This deliberately checks emitted rows, not only XML/JSON metadata equivalence.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class ConcatFieldsFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void concatFieldsGroupedListAndBooleanExecuteFullPipelineAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin concatPlugin = plugins.findPluginWithId(TransformPluginType.class, "ConcatFields");
    assertNotNull(gridPlugin, "pinned DataGrid plugin required");
    assertNotNull(concatPlugin, "pinned ConcatFields plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> fieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("源<&>甲", "源_乙")) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> gridLines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("黄河", "水网"), List.of("长江", "数字孪生"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      gridLines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, gridLines);

    ITransformMeta concat = plugins.loadClass(concatPlugin, ITransformMeta.class);
    concat.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("ConcatFields", "concat-node", concat);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("concat-full-pipeline");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("concat-full-pipeline.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("concat-full-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("concat-full-doc",
        new PipelineConfigResource.ReadRequest("concat-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("separator", "|");
    draft.put("force_enclosure", false);
    ObjectNode extra = (ObjectNode) draft.path("ConcatFields");
    assertTrue(extra.isObject());
    extra.put("targetFieldName", "拼接<&>雪");
    extra.put("targetFieldLength", 128);
    extra.put("removeSelectedFields", true);
    ArrayNode selected = draft.putObject("fields").putArray("field");
    for (String name : List.of("源<&>甲", "源_乙")) {
      selected.addObject().put("name", name).put("type", "String")
          .put("length", -1).put("precision", -1);
    }
    var write = resource.write("concat-full-doc",
        new PipelineConfigResource.WriteRequest("concat-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("concat-node").getTransform(), provider);
    assertEquals("源<&>甲", wire.at("/fields/field/0/name").asText());
    assertEquals("源_乙", wire.at("/fields/field/1/name").asText());
    assertTrue(wire.at("/fields/field/0/length").isIntegralNumber());
    assertEquals(-1, wire.at("/fields/field/0/length").intValue());
    assertTrue(wire.at("/ConcatFields/removeSelectedFields").isBoolean());
    assertTrue(wire.at("/ConcatFields/removeSelectedFields").booleanValue());
    assertEquals("拼接<&>雪", wire.at("/ConcatFields/targetFieldName").asText());
    assertEquals("|", wire.at("/separator").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> emittedMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("concat-node", 0);
      assertNotNull(output, "real engine must prepare ConcatFields");
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
    assertNotNull(emittedMeta.get(), "ConcatFields must emit native rows");
    assertEquals(1, emittedMeta.get().size(),
        "removeSelectedFields must remove both source columns from actual rows");
    assertEquals("拼接<&>雪", emittedMeta.get().getValueMeta(0).getName());
    assertEquals(2, emitted.size());
    // Hop RowDataUtil over-allocates backing Object[] capacity; only the
    // declared output schema width is semantically meaningful.
    assertEquals("黄河|水网", emitted.get(0)[0]);
    assertEquals("长江|数字孪生", emitted.get(1)[0]);
    System.out.println("PROD_P0_CONCATFIELDS_FULL_PIPELINE_JSON_HPL_ROWS=PASS");
  }

  @Test
  void concatFieldsVariableEnclosureRemainsLiteralAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin concatPlugin = plugins.findPluginWithId(TransformPluginType.class, "ConcatFields");
    assertNotNull(gridPlugin, "pinned DataGrid plugin required");
    assertNotNull(concatPlugin, "pinned ConcatFields plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> fieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("源<&>甲", "源_乙")) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> gridLines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("黄河", "水网"), List.of("长江", "数字孪生"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      gridLines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, gridLines);

    ITransformMeta concat = plugins.loadClass(concatPlugin, ITransformMeta.class);
    concat.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("ConcatFields", "concat-node", concat);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("concat-variable-enclosure-pipeline");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("concat-variable-enclosure-pipeline.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("concat-enclosure-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("concat-enclosure-doc",
        new PipelineConfigResource.ReadRequest("concat-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("separator", "${SEP}");
    draft.put("enclosure", "${ENC}");
    draft.put("force_enclosure", true);
    ObjectNode extra = (ObjectNode) draft.path("ConcatFields");
    assertTrue(extra.isObject());
    extra.put("targetFieldName", "拼接<&>雪");
    extra.put("targetFieldLength", 128);
    extra.put("removeSelectedFields", true);
    ArrayNode selected = draft.putObject("fields").putArray("field");
    for (String name : List.of("源<&>甲", "源_乙")) {
      selected.addObject().put("name", name).put("type", "String")
          .put("length", -1).put("precision", -1);
    }
    var write = resource.write("concat-enclosure-doc",
        new PipelineConfigResource.WriteRequest("concat-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("concat-node").getTransform(), provider);
    assertEquals("源<&>甲", wire.at("/fields/field/0/name").asText());
    assertEquals("源_乙", wire.at("/fields/field/1/name").asText());
    assertTrue(wire.at("/fields/field/0/length").isIntegralNumber());
    assertEquals(-1, wire.at("/fields/field/0/length").intValue());
    assertTrue(wire.at("/ConcatFields/removeSelectedFields").isBoolean());
    assertTrue(wire.at("/ConcatFields/removeSelectedFields").booleanValue());
    assertEquals("拼接<&>雪", wire.at("/ConcatFields/targetFieldName").asText());
    assertEquals("${SEP}", wire.at("/separator").asText());
    assertEquals("${ENC}", wire.at("/enclosure").asText());
    assertTrue(wire.at("/force_enclosure").booleanValue());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> emittedMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setVariable("SEP", "|");
    engine.setVariable("ENC", "\"");
    assertEquals("|", engine.resolve("${SEP}"));
    assertEquals("\"", engine.resolve("${ENC}"));
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("concat-node", 0);
      assertNotNull(output, "real engine must prepare ConcatFields");
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
    assertNotNull(emittedMeta.get(), "ConcatFields must emit native rows");
    assertEquals(1, emittedMeta.get().size(),
        "removeSelectedFields must remove both source columns from actual rows");
    assertEquals("拼接<&>雪", emittedMeta.get().getValueMeta(0).getName());
    assertEquals(2, emitted.size());
    // Hop RowDataUtil over-allocates backing Object[] capacity; only the
    // declared output schema width is semantically meaningful.
    assertEquals("${ENC}黄河${ENC}|${ENC}水网${ENC}", emitted.get(0)[0]);
    assertEquals("${ENC}长江${ENC}|${ENC}数字孪生${ENC}", emitted.get(1)[0]);
    System.out.println("PROD_P0_CONCATFIELDS_VARIABLE_ENCLOSURE_LITERAL_JSON_HPL_ROWS=PASS");
  }
}
