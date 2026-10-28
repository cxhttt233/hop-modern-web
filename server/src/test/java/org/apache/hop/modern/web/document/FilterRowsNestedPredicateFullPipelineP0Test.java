package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.hop.core.Condition;
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
 * Production FilterRows nested AND/OR predicate acceptance. Unlike the existing native
 * orphan-field check, this proves actual row selection after a nested JSON edit and
 * real .hpl disk reopen. No true/false target routing or Hop Core changes are involved.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class FilterRowsNestedPredicateFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void filterRowsNestedAndOrEvaluatesTypedFieldReferencesAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin filterPlugin = plugins.findPluginWithId(TransformPluginType.class, "FilterRows");
    IPlugin selectPlugin = plugins.findPluginWithId(TransformPluginType.class, "SelectValues");
    assertNotNull(gridPlugin);
    assertNotNull(filterPlugin);
    assertNotNull(selectPlugin);

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<String> names = List.of("站点", "允许站点", "水位", "阈值", "警戒", "触发");
    List<Object> columns = new ArrayList<>();
    for (String name : names) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      columns.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, columns);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("黄河", "黄河", "9", "5", "否", "是"),
        List.of("黄河", "黄河", "2", "5", "是", "是"),
        List.of("黄河", "长江", "9", "5", "是", "是"),
        List.of("黄河", "黄河", "2", "5", "否", "是"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta filter = plugins.loadClass(filterPlugin, ITransformMeta.class);
    filter.setDefault();
    // Start with a deliberately unresolved deep field, then correct it through the
    // authoritative JSON configuration API. The nested expression is:
    // station = allowed AND (level > threshold OR alarm = trigger).
    Condition root = new Condition();
    root.addCondition(new Condition("站点", Condition.Function.EQUAL, "允许站点", null));
    Condition nested = new Condition();
    nested.addCondition(new Condition("水位待编辑", Condition.Function.LARGER, "阈值", null));
    nested.addCondition(new Condition(Condition.Operator.OR,
        "警戒", Condition.Function.EQUAL, "触发", null));
    root.addCondition(nested);
    filter.getClass().getMethod("setCondition", Condition.class).invoke(filter, root);

    ITransformMeta select = plugins.loadClass(selectPlugin, ITransformMeta.class);
    select.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta predicate = new TransformMeta("FilterRows", "filter-node", filter);
    TransformMeta sink = new TransformMeta("SelectValues", "select-node", select);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("filter-rows-nested-and-or");
    pipeline.addTransform(source);
    pipeline.addTransform(predicate);
    pipeline.addTransform(sink);
    pipeline.addPipelineHop(new PipelineHopMeta(source, predicate));
    pipeline.addPipelineHop(new PipelineHopMeta(predicate, sink));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("filter-rows-nested-and-or.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("filter-nested-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("filter-nested-doc",
        new PipelineConfigResource.ReadRequest("filter-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    JsonNode nestedLeaf = draft.at(
        "/compare/condition/conditions/condition/1/conditions/condition/0");
    assertTrue(nestedLeaf.isObject(), "nested Condition must be a real JSON object");
    assertEquals("水位待编辑", nestedLeaf.path("leftvalue").asText());
    ((ObjectNode) nestedLeaf).put("leftvalue", "水位");
    var write = resource.write("filter-nested-doc",
        new PipelineConfigResource.WriteRequest("filter-node", draft));
    assertEquals(200, write.getStatus());

    // SelectValues requires an explicit selection; keep all six source columns
    // in their original order so this test isolates native FilterRows evaluation.
    var selectRead = resource.read("filter-nested-doc",
        new PipelineConfigResource.ReadRequest("select-node"));
    assertEquals(200, selectRead.getStatus());
    ObjectNode selectDraft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) selectRead.getEntity()).config()).deepCopy();
    ObjectNode selectFields = (ObjectNode) selectDraft.path("fields");
    assertTrue(selectFields.isObject());
    selectFields.put("select_unspecified", false);
    var selected = selectFields.putArray("field");
    for (String name : names) {
      selected.addObject().put("name", name).put("rename", name);
    }
    selectFields.putArray("remove");
    selectFields.putArray("meta");
    var selectWrite = resource.write("filter-nested-doc",
        new PipelineConfigResource.WriteRequest("select-node", selectDraft));
    assertEquals(200, selectWrite.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(3, reopened.nrTransforms());
    assertEquals(2, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("filter-node").getTransform(), provider);
    assertEquals("水位", wire.at(
        "/compare/condition/conditions/condition/1/conditions/condition/0/leftvalue").asText());
    assertEquals("警戒", wire.at(
        "/compare/condition/conditions/condition/1/conditions/condition/1/leftvalue").asText());
    assertEquals("OR", wire.at(
        "/compare/condition/conditions/condition/1/conditions/condition/1/operator").asText());

    List<Object[]> outputRows = new CopyOnWriteArrayList<>();
    List<IRowMeta> outputMetas = new CopyOnWriteArrayList<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("select-node", 0);
      assertNotNull(output);
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          outputMetas.add(rowMeta.clone());
          outputRows.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native nested predicate must execute");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertEquals(2, outputRows.size(),
        "nested OR admits either high level or active alarm, AND requires matching station");
    assertEquals(names, outputMetas.get(0).getValueMetaList().stream()
        .map(IValueMeta::getName).toList());
    assertTrue(outputMetas.get(0).getValueMetaList().stream()
        .allMatch(meta -> meta.getType() == IValueMeta.TYPE_STRING));
    assertEquals(List.of("黄河", "黄河", "9", "5", "否", "是"),
        List.of(outputRows.get(0)));
    assertEquals(List.of("黄河", "黄河", "2", "5", "是", "是"),
        List.of(outputRows.get(1)));
    System.out.println("PROD_P0_FILTERROWS_NESTED_AND_OR_JSON_HPL_ROWS=PASS");
  }
}
