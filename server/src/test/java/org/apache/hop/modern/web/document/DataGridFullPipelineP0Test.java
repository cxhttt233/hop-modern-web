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
 * Native DataGrid acceptance for set_empty_string versus a configured cell literal.
 * Unlike field-metadata-only checks, this executes a disk-reopened pipeline and
 * checks both typed INTEGER conversion and the actual empty STRING output.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class DataGridFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void dataGridExplicitEmptyStringOverridesCellWhileIntegerConvertsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    assertNotNull(gridPlugin, "pinned production DataGrid plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");

    List<Object> gridFields = new ArrayList<>();
    for (String[] definition : new String[][] {
        {"流量<&>", "Integer"}, {"强制空串", "String"}, {"测站", "String"}}) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, definition[0]);
      fieldType.getMethod("setType", String.class).invoke(field, definition[1]);
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);

    List<Object> gridLines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("42", "discard-first", "黄河"),
        List.of("7", "discard-second", "长江"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      gridLines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, gridLines);

    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("datagrid-explicit-empty-string-full-pipeline");
    pipeline.addTransform(new TransformMeta("DataGrid", "grid-node", grid));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("datagrid-empty-string.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("datagrid-full-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("datagrid-full-doc",
        new PipelineConfigResource.ReadRequest("grid-node"));
    assertEquals(200, read.getStatus());
    var response = (PipelineConfigResource.ConfigResponse) read.getEntity();
    var precondition = response.descriptor().runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "DATA_GRID_EXPLICIT_EMPTY_STRING_OVERRIDES_CONFIGURED_CELL"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field", "/data/line"), precondition.keyListPointers());

    ObjectNode draft = ((ObjectNode) response.config()).deepCopy();
    JsonNode declared = draft.at("/fields/field");
    assertTrue(declared.isArray());
    assertEquals(3, declared.size());
    assertTrue(declared.get(0).path("field").isObject(), "nested Hop groupKey is a wire container");
    assertEquals("Integer", declared.get(0).path("field").path("type").asText());
    assertTrue(declared.get(1).path("field").path("set_empty_string").isBoolean());
    ((ObjectNode) declared.get(1).path("field")).put("set_empty_string", true);
    assertEquals("discard-first", draft.at("/data/line/0/item/1").asText());

    var write = resource.write("datagrid-full-doc",
        new PipelineConfigResource.WriteRequest("grid-node", draft));
    assertEquals(200, write.getStatus(), "typed DataGrid JSON edit must be accepted");
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(1, reopened.nrTransforms());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("grid-node").getTransform(), provider);
    assertEquals("流量<&>", wire.at("/fields/field/0/field/name").asText());
    assertEquals("Integer", wire.at("/fields/field/0/field/type").asText());
    assertTrue(wire.at("/fields/field/1/field/set_empty_string").isBoolean());
    assertTrue(wire.at("/fields/field/1/field/set_empty_string").booleanValue());
    assertEquals("discard-first", wire.at("/data/line/0/item/1").asText());
    assertEquals("discard-second", wire.at("/data/line/1/item/1").asText());

    List<Object[]> rows = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("grid-node", 0);
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
      assertEquals(0, engine.getErrors(), "native DataGrid must execute");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get());
    assertEquals(3, outputMeta.get().size());
    assertEquals("流量<&>", outputMeta.get().getValueMeta(0).getName());
    assertEquals(IValueMeta.TYPE_INTEGER, outputMeta.get().getValueMeta(0).getType());
    assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(1).getType());
    assertEquals("强制空串", outputMeta.get().getValueMeta(1).getName());
    assertEquals(2, rows.size());
    assertEquals(42L, ((Number) rows.get(0)[0]).longValue());
    assertEquals("", rows.get(0)[1], "true must override non-empty cell literal");
    assertEquals("黄河", rows.get(0)[2]);
    assertEquals(7L, ((Number) rows.get(1)[0]).longValue());
    assertEquals("", rows.get(1)[1], "true must override second literal too");
    assertEquals("长江", rows.get(1)[2]);
    System.out.println("PROD_P0_DATAGRID_FULL_PIPELINE_EXPLICIT_EMPTY_STRING_TYPED_ROWS=PASS");
  }
}
