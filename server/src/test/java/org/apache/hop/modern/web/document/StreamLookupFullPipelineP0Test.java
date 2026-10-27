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
 * Real three-node StreamLookup acceptance. The two input streams have different
 * schemas: the main stream supplies lookup keys and the info stream supplies
 * typed return values. A missing key must produce typed configured defaults.
 * This extends the existing nested-LIST disk test to native engine output.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class StreamLookupFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  private static ITransformMeta grid(
      PluginRegistry plugins, List<String> names, List<String> types,
      List<List<String>> rows) throws Exception {
    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    assertNotNull(plugin, "pinned DataGrid plugin required");
    ITransformMeta grid = plugins.loadClass(plugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    assertEquals(names.size(), types.size());
    List<Object> fields = new ArrayList<>();
    for (int i = 0; i < names.size(); i++) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, names.get(i));
      fieldType.getMethod("setType", String.class).invoke(field, types.get(i));
      fields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, fields);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : rows) {
      assertEquals(names.size(), values.size());
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);
    return grid;
  }

  @Test
  void streamLookupMatchedAndMissingKeysEmitTypedOrderedValuesAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin lookupPlugin = plugins.findPluginWithId(TransformPluginType.class, "StreamLookup");
    assertNotNull(lookupPlugin, "pinned StreamLookup plugin required");

    ITransformMeta mainGrid = grid(
        plugins, List.of("测站<&>", "批次"), List.of("String", "String"),
        List.of(List.of("黄河", "a"), List.of("长江", "b"), List.of("未收录", "c")));
    ITransformMeta infoGrid = grid(
        plugins, List.of("索引站", "级别", "流量"), List.of("String", "String", "Integer"),
        List.of(List.of("黄河", "重点", "42"), List.of("长江", "一般", "7")));
    ITransformMeta lookup = plugins.loadClass(lookupPlugin, ITransformMeta.class);
    lookup.setDefault();

    TransformMeta main = new TransformMeta("DataGrid", "main-grid", mainGrid);
    TransformMeta info = new TransformMeta("DataGrid", "info-grid", infoGrid);
    TransformMeta target = new TransformMeta("StreamLookup", "lookup-node", lookup);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("streamlookup-native-matched-missing-typed-values");
    pipeline.addTransform(main);
    pipeline.addTransform(info);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(main, target));
    pipeline.addPipelineHop(new PipelineHopMeta(info, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("streamlookup-typed-defaults.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(2, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("streamlookup-full-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("streamlookup-full-doc",
        new PipelineConfigResource.ReadRequest("lookup-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("from", "info-grid");
    draft.put("preserve_memory", true);
    draft.put("sorted_list", false);
    ObjectNode lookupDraft = draft.putObject("lookup");
    lookupDraft.putArray("key").addObject()
        .put("name", "测站<&>").put("field", "索引站");
    lookupDraft.putArray("value")
        .addObject().put("name", "级别").put("rename", "等级<&>")
        .put("default", "未知<&>").put("type", IValueMeta.TYPE_STRING);
    lookupDraft.withArray("value")
        .addObject().put("name", "流量").put("rename", "对应流量")
        .put("default", "-1").put("type", IValueMeta.TYPE_INTEGER);
    var write = resource.write("streamlookup-full-doc",
        new PipelineConfigResource.WriteRequest("lookup-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(3, reopened.nrTransforms());
    assertEquals(2, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("lookup-node").getTransform(), provider);
    assertEquals("info-grid", wire.at("/from").asText());
    assertEquals("测站<&>", wire.at("/lookup/key/0/name").asText());
    assertEquals("索引站", wire.at("/lookup/key/0/field").asText());
    assertEquals(2, wire.at("/lookup/value").size());
    assertEquals("等级<&>", wire.at("/lookup/value/0/rename").asText());
    assertEquals("未知<&>", wire.at("/lookup/value/0/default").asText());
    assertTrue(wire.at("/lookup/value/0/type").isInt());
    assertEquals(IValueMeta.TYPE_STRING, wire.at("/lookup/value/0/type").intValue());
    assertEquals("对应流量", wire.at("/lookup/value/1/rename").asText());
    assertEquals("-1", wire.at("/lookup/value/1/default").asText());
    assertTrue(wire.at("/lookup/value/1/type").isInt());
    assertEquals(IValueMeta.TYPE_INTEGER, wire.at("/lookup/value/1/type").intValue());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("lookup-node", 0);
      assertNotNull(output, "native StreamLookup engine must prepare");
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          outputMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native StreamLookup must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    IRowMeta meta = outputMeta.get();
    assertNotNull(meta, "StreamLookup must emit rows");
    assertEquals(4, meta.size());
    assertEquals("等级<&>", meta.getValueMeta(2).getName());
    assertEquals(IValueMeta.TYPE_STRING, meta.getValueMeta(2).getType());
    assertEquals("对应流量", meta.getValueMeta(3).getName());
    assertEquals(IValueMeta.TYPE_INTEGER, meta.getValueMeta(3).getType());
    assertEquals(3, emitted.size());

    Object[] yellow = emitted.stream().filter(r -> "黄河".equals(r[0])).findFirst().orElseThrow();
    Object[] yangtze = emitted.stream().filter(r -> "长江".equals(r[0])).findFirst().orElseThrow();
    Object[] missing = emitted.stream().filter(r -> "未收录".equals(r[0])).findFirst().orElseThrow();
    assertEquals("重点", yellow[2]);
    assertEquals(42L, ((Number) yellow[3]).longValue());
    assertEquals("一般", yangtze[2]);
    assertEquals(7L, ((Number) yangtze[3]).longValue());
    assertEquals("未知<&>", missing[2]);
    assertEquals(-1L, ((Number) missing[3]).longValue());
    System.out.println("PROD_P0_STREAMLOOKUP_MATCH_MISS_TYPED_DEFAULTS_JSON_HPL_ROWS=PASS");
  }
}
