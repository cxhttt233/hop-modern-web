package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
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
 * Engine-level evidence for the existing MergeJoin NULL-key descriptor contract.
 * Unlike metadata-only RowMeta.compare assertions, this checks the emitted
 * Cartesian product of duplicate NULL keys in the pinned native MergeJoin.
 * It neither changes Hop Core nor validates the graph-reference editor.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class MergeJoinNullCartesianFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  private static ITransformMeta grid(
      PluginRegistry plugins, String keyName, String payloadName,
      List<List<String>> rows) throws Exception {
    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    assertNotNull(plugin, "pinned DataGrid plugin required");
    ITransformMeta meta = plugins.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    ClassLoader loader = meta.getClass().getClassLoader();
    Class<?> fieldClass = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineClass = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> fields = new ArrayList<>();
    for (String[] spec : new String[][] {
        {keyName, "Integer"}, {payloadName, "String"}}) {
      Object field = fieldClass.getConstructor().newInstance();
      fieldClass.getMethod("setName", String.class).invoke(field, spec[0]);
      fieldClass.getMethod("setType", String.class).invoke(field, spec[1]);
      fields.add(field);
    }
    meta.getClass().getMethod("setDataGridFields", List.class).invoke(meta, fields);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : rows) {
      assertEquals(2, values.size());
      Object line = lineClass.getConstructor().newInstance();
      lineClass.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    meta.getClass().getMethod("setDataLines", List.class).invoke(meta, lines);
    return meta;
  }

  @Test
  void duplicateNullIntegerKeysProduceFourCartesianPairsAndOneNonNullMatchAfterHplEdit(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin mergePlugin = plugins.findPluginWithId(TransformPluginType.class, "MergeJoin");
    assertNotNull(mergePlugin, "pinned MergeJoin plugin required");

    // Integer empty cells become actual Java nulls in DataGrid's native conversion.
    // Both streams are sorted null, null, positive-key, unmatched-positive-key.
    ITransformMeta leftGrid = grid(plugins, "左键<&雪>", "左记录",
        List.of(List.of("", "左一<&雪>"), List.of("", "左二"),
            List.of("7", "左七"), List.of("9", "左未匹配")));
    ITransformMeta rightGrid = grid(plugins, "右键<&雪>", "右记录",
        List.of(List.of("", "右一"), List.of("", "右二<&雪>"),
            List.of("7", "右七"), List.of("8", "右未匹配")));
    ITransformMeta merge = plugins.loadClass(mergePlugin, ITransformMeta.class);
    merge.setDefault();
    merge.getClass().getMethod("setJoinType", String.class).invoke(merge, "INNER");
    merge.getClass().getMethod("setLeftTransformName", String.class)
        .invoke(merge, "left-grid");
    merge.getClass().getMethod("setRightTransformName", String.class)
        .invoke(merge, "right-grid");
    merge.getClass().getMethod("setKeyFields1", List.class)
        .invoke(merge, List.of("left-placeholder"));
    merge.getClass().getMethod("setKeyFields2", List.class)
        .invoke(merge, List.of("right-placeholder"));

    TransformMeta left = new TransformMeta("DataGrid", "left-grid", leftGrid);
    TransformMeta right = new TransformMeta("DataGrid", "right-grid", rightGrid);
    TransformMeta target = new TransformMeta("MergeJoin", "merge-node", merge);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("mergejoin-native-null-cartesian");
    pipeline.addTransform(left);
    pipeline.addTransform(right);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(left, target));
    pipeline.addPipelineHop(new PipelineHopMeta(right, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("mergejoin-null-cartesian.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(3, loaded.nrTransforms());
    assertEquals(2, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("merge-null-engine", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var response = resource.read("merge-null-engine",
        new PipelineConfigResource.ReadRequest("merge-node"));
    assertEquals(200, response.getStatus());
    var payload = (PipelineConfigResource.ConfigResponse) response.getEntity();
    var diagnostic = payload.descriptor().runtimePreconditions().stream()
        .filter(p -> p.code().equals("MERGE_JOIN_NULL_KEYS_MATCH_UNLIKE_SQL_EQUALITY"))
        .findFirst().orElseThrow();
    assertTrue(diagnostic.explanation().contains("Cartesian-product"));

    ObjectNode draft = ((ObjectNode) payload.config()).deepCopy();
    draft.putObject("keys_1").putArray("key").add("左键<&雪>");
    draft.putObject("keys_2").putArray("key").add("右键<&雪>");
    assertEquals(200, resource.write("merge-null-engine",
        new PipelineConfigResource.WriteRequest("merge-node", draft)).getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(3, reopened.nrTransforms());
    assertEquals(2, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("merge-node").getTransform(), provider);
    assertEquals("INNER", wire.at("/join_type").asText());
    assertEquals("左键<&雪>", wire.at("/keys_1/key/0").asText());
    assertEquals("右键<&雪>", wire.at("/keys_2/key/0").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    List<IRowMeta> outputMetas = new CopyOnWriteArrayList<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("merge-node", 0);
      assertNotNull(output);
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          outputMetas.add(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native MergeJoin must complete");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertEquals(5, emitted.size(),
        "two null keys on each side produce 2x2 pairs plus the key-7 match");
    assertFalse(outputMetas.isEmpty());
    IRowMeta rowMeta = outputMetas.get(0);
    assertEquals(4, rowMeta.size());
    assertEquals(IValueMeta.TYPE_INTEGER, rowMeta.getValueMeta(0).getType());
    assertEquals(IValueMeta.TYPE_INTEGER, rowMeta.getValueMeta(2).getType());
    List<Object[]> nullMatches = emitted.stream()
        .filter(row -> row[0] == null && row[2] == null).toList();
    assertEquals(4, nullMatches.size(), "must join actual null/null keys");
    Set<String> pairs = nullMatches.stream()
        .map(row -> row[1] + "|" + row[3]).collect(Collectors.toSet());
    assertEquals(Set.of(
        "左一<&雪>|右一", "左一<&雪>|右二<&雪>",
        "左二|右一", "左二|右二<&雪>"), pairs);
    Object[] nonNull = emitted.stream()
        .filter(row -> row[0] != null).findFirst().orElseThrow();
    assertEquals(7L, ((Number) nonNull[0]).longValue());
    assertEquals(7L, ((Number) nonNull[2]).longValue());
    assertEquals("左七", nonNull[1]);
    assertEquals("右七", nonNull[3]);
    assertTrue(emitted.stream().noneMatch(row ->
        "左未匹配".equals(row[1]) || "右未匹配".equals(row[3])));
    System.out.println("PROD_P0_MERGEJOIN_NULL_CARTESIAN_NATIVE_ENGINE_JSON_HPL_ROWS=PASS");
  }
}
