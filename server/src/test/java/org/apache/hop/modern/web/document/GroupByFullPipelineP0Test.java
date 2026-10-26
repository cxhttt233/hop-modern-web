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
}
