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
}
