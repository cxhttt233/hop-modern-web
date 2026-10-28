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

    var descriptor = AutomaticConfigDescriptor.describe(
        reopened.findTransform("lookup-node").getTransform().getClass());
    var missingMatchContract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("STREAM_LOOKUP_MISSING_MATCH_USES_TYPED_DEFAULT_VALUES"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/lookup/key", "/lookup/value"),
        missingMatchContract.keyListPointers());
    assertTrue(missingMatchContract.explanation().contains("numeric IValueMeta type code"));


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

  /**
   * Native StreamLookup is a one-value lookup, not a one-to-many join.
   * All three cache implementations overwrite earlier duplicate info keys.
   */
  @Test
  void streamLookupDuplicateInfoKeysOverwriteEarlierValuesAcrossCacheModesAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin lookupPlugin = plugins.findPluginWithId(TransformPluginType.class, "StreamLookup");
    assertNotNull(lookupPlugin);

    // Ordinary map, memory-preserving hash index, and memory-preserving sorted list.
    for (boolean[] cacheMode : new boolean[][] {
        {false, false}, {true, false}, {true, true}}) {
      boolean preserveMemory = cacheMode[0];
      boolean sortedList = cacheMode[1];
      String mode = preserveMemory + "-" + sortedList;

      ITransformMeta mainGrid = grid(
          plugins, List.of("测站<&>", "批次"), List.of("String", "String"),
          List.of(List.of("黄河", "a"), List.of("长江", "b"), List.of("未收录", "c")));
      ITransformMeta infoGrid = grid(
          plugins, List.of("索引站", "级别", "流量"),
          List.of("String", "String", "Integer"),
          List.of(List.of("黄河", "旧记录", "1"),
              List.of("长江", "正常", "7"),
              List.of("黄河", "最新记录", "42")));
      ITransformMeta lookup = plugins.loadClass(lookupPlugin, ITransformMeta.class);
      lookup.setDefault();

      TransformMeta main = new TransformMeta("DataGrid", "main-grid", mainGrid);
      TransformMeta info = new TransformMeta("DataGrid", "info-grid", infoGrid);
      TransformMeta target = new TransformMeta("StreamLookup", "lookup-node", lookup);
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("streamlookup-duplicate-last-wins-" + mode);
      pipeline.addTransform(main);
      pipeline.addTransform(info);
      pipeline.addTransform(target);
      pipeline.addPipelineHop(new PipelineHopMeta(main, target));
      pipeline.addPipelineHop(new PipelineHopMeta(info, target));

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("streamlookup-duplicate-" + mode + ".hpl");
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      String documentId = "streamlookup-duplicate-" + mode;
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument(documentId, hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read(documentId,
          new PipelineConfigResource.ReadRequest("lookup-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("from", "info-grid");
      draft.put("preserve_memory", preserveMemory);
      draft.put("sorted_list", sortedList);
      ObjectNode lookupDraft = draft.putObject("lookup");
      lookupDraft.putArray("key").addObject()
          .put("name", "测站<&>").put("field", "索引站");
      lookupDraft.putArray("value").addObject()
          .put("name", "级别").put("rename", "等级<&>")
          .put("default", "未命中").put("type", IValueMeta.TYPE_STRING);
      lookupDraft.withArray("value").addObject()
          .put("name", "流量").put("rename", "对应流量")
          .put("default", "-1").put("type", IValueMeta.TYPE_INTEGER);
      var write = resource.write(documentId,
          new PipelineConfigResource.WriteRequest("lookup-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      assertEquals(3, reopened.nrTransforms());
      assertEquals(2, reopened.nrPipelineHops());
      JsonNode wire = ConfigJsonSerializer.toJson(
          reopened.findTransform("lookup-node").getTransform(), provider);
      assertEquals("info-grid", wire.at("/from").asText());
      assertTrue(wire.at("/preserve_memory").isBoolean());
      assertEquals(preserveMemory, wire.at("/preserve_memory").booleanValue());
      assertTrue(wire.at("/sorted_list").isBoolean());
      assertEquals(sortedList, wire.at("/sorted_list").booleanValue());
      assertEquals("测站<&>", wire.at("/lookup/key/0/name").asText());
      assertEquals("索引站", wire.at("/lookup/key/0/field").asText());
      assertEquals(2, wire.at("/lookup/value").size());
      assertEquals("等级<&>", wire.at("/lookup/value/0/rename").asText());
      assertEquals("对应流量", wire.at("/lookup/value/1/rename").asText());
      assertTrue(wire.at("/lookup/value/1/type").isInt());
      assertEquals(IValueMeta.TYPE_INTEGER, wire.at("/lookup/value/1/type").intValue());

      var duplicateKeyContract = AutomaticConfigDescriptor.describe(
          reopened.findTransform("lookup-node").getTransform().getClass())
          .runtimePreconditions().stream()
          .filter(p -> p.code().equals("STREAM_LOOKUP_DUPLICATE_INFO_KEYS_LAST_VALUE_WINS"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/from", "/preserve_memory", "/sorted_list",
          "/lookup/key", "/lookup/value"), duplicateKeyContract.keyListPointers());
      assertTrue(duplicateKeyContract.explanation().contains("later value"));

      List<Object[]> emitted = new CopyOnWriteArrayList<>();
      AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
      LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
      engine.setMetadataProvider(provider);
      try {
        engine.prepareExecution();
        ITransform output = engine.getRunThread("lookup-node", 0);
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
        assertEquals(0, engine.getErrors(), "native duplicate-key lookup must finish: " + mode);
      } finally {
        if (!engine.isFinishedOrStopped()) engine.stopAll();
        engine.cleanup();
      }

      IRowMeta meta = outputMeta.get();
      assertNotNull(meta);
      assertEquals(4, meta.size());
      assertEquals("等级<&>", meta.getValueMeta(2).getName());
      assertEquals(IValueMeta.TYPE_STRING, meta.getValueMeta(2).getType());
      assertEquals("对应流量", meta.getValueMeta(3).getName());
      assertEquals(IValueMeta.TYPE_INTEGER, meta.getValueMeta(3).getType());
      assertEquals(3, emitted.size());
      Object[] yellow = emitted.stream()
          .filter(row -> "黄河".equals(row[0])).findFirst().orElseThrow();
      Object[] yangtze = emitted.stream()
          .filter(row -> "长江".equals(row[0])).findFirst().orElseThrow();
      Object[] missing = emitted.stream()
          .filter(row -> "未收录".equals(row[0])).findFirst().orElseThrow();
      assertEquals("最新记录", yellow[2], "last duplicate info row wins: " + mode);
      assertEquals(42L, ((Number) yellow[3]).longValue());
      assertEquals("正常", yangtze[2]);
      assertEquals(7L, ((Number) yangtze[3]).longValue());
      assertEquals("未命中", missing[2]);
      assertEquals(-1L, ((Number) missing[3]).longValue());
    }
    System.out.println("PROD_P0_STREAMLOOKUP_DUPLICATE_LAST_WINS_ALL_CACHE_MODES_JSON_HPL_ROWS=PASS");
  }

  /** Exact-case key matching must survive JSON editing and real HPL disk reopen. */
  @Test
  void streamLookupStringKeyCaseVariantsUseTypedDefaultsAcrossCacheModesAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin lookupPlugin = plugins.findPluginWithId(TransformPluginType.class, "StreamLookup");
    assertNotNull(lookupPlugin);

    // Ordinary map, memory-preserving hash index, and memory-preserving sorted list.
    for (boolean[] cacheMode : new boolean[][] {
        {false, false}, {true, false}, {true, true}}) {
      boolean preserveMemory = cacheMode[0];
      boolean sortedList = cacheMode[1];
      String mode = preserveMemory + "-" + sortedList;

      ITransformMeta mainGrid = grid(
          plugins, List.of("测站<&>", "批次"), List.of("String", "String"),
          List.of(List.of("Station_A", "exact"), List.of("station_a", "lower"),
              List.of("STATION_A", "upper"), List.of("Station_B", "other")));
      ITransformMeta infoGrid = grid(
          plugins, List.of("索引站", "级别", "流量"),
          List.of("String", "String", "Integer"),
          List.of(List.of("Station_A", "exact match", "11"),
              List.of("Station_B", "other match", "22")));
      ITransformMeta lookup = plugins.loadClass(lookupPlugin, ITransformMeta.class);
      lookup.setDefault();

      TransformMeta main = new TransformMeta("DataGrid", "main-grid", mainGrid);
      TransformMeta info = new TransformMeta("DataGrid", "info-grid", infoGrid);
      TransformMeta target = new TransformMeta("StreamLookup", "lookup-node", lookup);
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("streamlookup-case-sensitive-" + mode);
      pipeline.addTransform(main);
      pipeline.addTransform(info);
      pipeline.addTransform(target);
      pipeline.addPipelineHop(new PipelineHopMeta(main, target));
      pipeline.addPipelineHop(new PipelineHopMeta(info, target));

      var provider = new MemoryMetadataProvider();
      var store = new PipelineDocumentStore(provider, new Variables());
      Path hpl = tempDir.resolve("streamlookup-case-sensitive-" + mode + ".hpl");
      store.save(hpl, pipeline);
      PipelineMeta loaded = store.open(hpl);
      String documentId = "streamlookup-case-sensitive-" + mode;
      var documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument(documentId, hpl, loaded));
      var resource = new PipelineConfigResource(documents, provider);
      var read = resource.read(documentId,
          new PipelineConfigResource.ReadRequest("lookup-node"));
      assertEquals(200, read.getStatus());
      ObjectNode draft = ((ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      draft.put("from", "info-grid");
      draft.put("preserve_memory", preserveMemory);
      draft.put("sorted_list", sortedList);
      ObjectNode lookupDraft = draft.putObject("lookup");
      lookupDraft.putArray("key").addObject()
          .put("name", "测站<&>").put("field", "索引站");
      lookupDraft.putArray("value").addObject()
          .put("name", "级别").put("rename", "等级<&>")
          .put("default", "未命中").put("type", IValueMeta.TYPE_STRING);
      lookupDraft.withArray("value").addObject()
          .put("name", "流量").put("rename", "对应流量")
          .put("default", "-1").put("type", IValueMeta.TYPE_INTEGER);
      var write = resource.write(documentId,
          new PipelineConfigResource.WriteRequest("lookup-node", draft));
      assertEquals(200, write.getStatus());
      store.save(hpl, loaded);

      PipelineMeta reopened = store.open(hpl);
      assertEquals(3, reopened.nrTransforms());
      assertEquals(2, reopened.nrPipelineHops());
      JsonNode wire = ConfigJsonSerializer.toJson(
          reopened.findTransform("lookup-node").getTransform(), provider);
      assertEquals("info-grid", wire.at("/from").asText());
      assertTrue(wire.at("/preserve_memory").isBoolean());
      assertEquals(preserveMemory, wire.at("/preserve_memory").booleanValue());
      assertTrue(wire.at("/sorted_list").isBoolean());
      assertEquals(sortedList, wire.at("/sorted_list").booleanValue());
      assertEquals("测站<&>", wire.at("/lookup/key/0/name").asText());
      assertEquals("索引站", wire.at("/lookup/key/0/field").asText());
      assertEquals(2, wire.at("/lookup/value").size());
      assertEquals("等级<&>", wire.at("/lookup/value/0/rename").asText());
      assertEquals("对应流量", wire.at("/lookup/value/1/rename").asText());
      assertTrue(wire.at("/lookup/value/1/type").isInt());
      assertEquals(IValueMeta.TYPE_INTEGER, wire.at("/lookup/value/1/type").intValue());

      var caseSensitiveContract = AutomaticConfigDescriptor.describe(
          reopened.findTransform("lookup-node").getTransform().getClass())
          .runtimePreconditions().stream()
          .filter(p -> p.code().equals("STREAM_LOOKUP_STRING_KEYS_CASE_SENSITIVE_ACROSS_CACHE_MODES"))
          .findFirst().orElseThrow();
      assertEquals(List.of("/from", "/lookup/key", "/lookup/value",
          "/preserve_memory", "/sorted_list"), caseSensitiveContract.keyListPointers());
      assertTrue(caseSensitiveContract.explanation().contains("case-only mismatch"));

      List<Object[]> emitted = new CopyOnWriteArrayList<>();
      AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
      LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
      engine.setMetadataProvider(provider);
      try {
        engine.prepareExecution();
        ITransform output = engine.getRunThread("lookup-node", 0);
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
        assertEquals(0, engine.getErrors(), "native case-sensitive lookup must finish: " + mode);
      } finally {
        if (!engine.isFinishedOrStopped()) engine.stopAll();
        engine.cleanup();
      }

      IRowMeta meta = outputMeta.get();
      assertNotNull(meta);
      assertEquals(4, meta.size());
      assertEquals("等级<&>", meta.getValueMeta(2).getName());
      assertEquals(IValueMeta.TYPE_STRING, meta.getValueMeta(2).getType());
      assertEquals("对应流量", meta.getValueMeta(3).getName());
      assertEquals(IValueMeta.TYPE_INTEGER, meta.getValueMeta(3).getType());
      assertEquals(4, emitted.size());
      Object[] exact = emitted.stream()
          .filter(row -> "Station_A".equals(row[0])).findFirst().orElseThrow();
      Object[] lower = emitted.stream()
          .filter(row -> "station_a".equals(row[0])).findFirst().orElseThrow();
      Object[] upper = emitted.stream()
          .filter(row -> "STATION_A".equals(row[0])).findFirst().orElseThrow();
      Object[] other = emitted.stream()
          .filter(row -> "Station_B".equals(row[0])).findFirst().orElseThrow();
      assertEquals("exact match", exact[2], "exact-case key must match: " + mode);
      assertEquals(11L, ((Number) exact[3]).longValue());
      for (Object[] row : List.of(lower, upper)) {
        assertEquals("未命中", row[2], "case-only mismatch must use STRING default: " + mode);
        assertEquals(-1L, ((Number) row[3]).longValue(),
            "case-only mismatch must use INTEGER default: " + mode);
      }
      assertEquals("other match", other[2]);
      assertEquals(22L, ((Number) other[3]).longValue());
    }
    System.out.println("PROD_P0_STREAMLOOKUP_CASE_SENSITIVE_KEYS_ALL_CACHE_MODES_JSON_HPL_ROWS=PASS");
  }


  /**
   * Two ordered lookup keys must disambiguate repeated station names across
   * reporting years. This exercises native multi-key lookup execution, not
   * only the nested LIST serializer's persistence of two key entries.
   */
  @Test
  void streamLookupCompositeStationYearKeysMatchOnlyBothComponentsAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin lookupPlugin = plugins.findPluginWithId(TransformPluginType.class, "StreamLookup");
    assertNotNull(lookupPlugin, "pinned StreamLookup plugin required");

    ITransformMeta mainGrid = grid(
        plugins, List.of("测站<&>", "年份", "批次"),
        List.of("String", "String", "String"),
        List.of(List.of("黄河", "2025", "old"),
            List.of("黄河", "2026", "new"),
            List.of("长江", "2026", "other"),
            List.of("黄河", "2024", "missing")));
    ITransformMeta infoGrid = grid(
        plugins, List.of("索引站", "统计年", "等级", "流量"),
        List.of("String", "String", "String", "Integer"),
        List.of(List.of("黄河", "2025", "历史", "11"),
            List.of("黄河", "2026", "最新", "42"),
            List.of("长江", "2026", "一般", "7")));
    ITransformMeta lookup = plugins.loadClass(lookupPlugin, ITransformMeta.class);
    lookup.setDefault();

    TransformMeta main = new TransformMeta("DataGrid", "main-grid", mainGrid);
    TransformMeta info = new TransformMeta("DataGrid", "info-grid", infoGrid);
    TransformMeta target = new TransformMeta("StreamLookup", "lookup-node", lookup);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("streamlookup-composite-station-year");
    pipeline.addTransform(main);
    pipeline.addTransform(info);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(main, target));
    pipeline.addPipelineHop(new PipelineHopMeta(info, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("streamlookup-composite-station-year.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(2, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    String documentId = "streamlookup-composite-station-year";
    documents.put(new PipelineDocument(documentId, hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read(documentId,
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
    lookupDraft.withArray("key").addObject()
        .put("name", "年份").put("field", "统计年");
    lookupDraft.putArray("value").addObject()
        .put("name", "等级").put("rename", "匹配等级")
        .put("default", "无记录").put("type", IValueMeta.TYPE_STRING);
    lookupDraft.withArray("value").addObject()
        .put("name", "流量").put("rename", "匹配流量")
        .put("default", "-1").put("type", IValueMeta.TYPE_INTEGER);
    var write = resource.write(documentId,
        new PipelineConfigResource.WriteRequest("lookup-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(3, reopened.nrTransforms());
    assertEquals(2, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("lookup-node").getTransform(), provider);
    assertEquals("info-grid", wire.at("/from").asText());
    assertEquals(2, wire.at("/lookup/key").size());
    assertEquals("测站<&>", wire.at("/lookup/key/0/name").asText());
    assertEquals("索引站", wire.at("/lookup/key/0/field").asText());
    assertEquals("年份", wire.at("/lookup/key/1/name").asText());
    assertEquals("统计年", wire.at("/lookup/key/1/field").asText());
    assertEquals(2, wire.at("/lookup/value").size());
    assertEquals("匹配等级", wire.at("/lookup/value/0/rename").asText());
    assertEquals("匹配流量", wire.at("/lookup/value/1/rename").asText());
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
      assertEquals(0, engine.getErrors(), "composite-key lookup must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    IRowMeta meta = outputMeta.get();
    assertNotNull(meta, "composite-key StreamLookup must emit rows");
    assertEquals(5, meta.size());
    assertEquals("匹配等级", meta.getValueMeta(3).getName());
    assertEquals(IValueMeta.TYPE_STRING, meta.getValueMeta(3).getType());
    assertEquals("匹配流量", meta.getValueMeta(4).getName());
    assertEquals(IValueMeta.TYPE_INTEGER, meta.getValueMeta(4).getType());
    assertEquals(4, emitted.size());

    Object[] old = emitted.stream()
        .filter(row -> "黄河".equals(row[0]) && "2025".equals(row[1]))
        .findFirst().orElseThrow();
    Object[] current = emitted.stream()
        .filter(row -> "黄河".equals(row[0]) && "2026".equals(row[1]))
        .findFirst().orElseThrow();
    Object[] other = emitted.stream()
        .filter(row -> "长江".equals(row[0]) && "2026".equals(row[1]))
        .findFirst().orElseThrow();
    Object[] missing = emitted.stream()
        .filter(row -> "黄河".equals(row[0]) && "2024".equals(row[1]))
        .findFirst().orElseThrow();
    assertEquals("历史", old[3]);
    assertEquals(11L, ((Number) old[4]).longValue());
    assertEquals("最新", current[3]);
    assertEquals(42L, ((Number) current[4]).longValue());
    assertEquals("一般", other[3]);
    assertEquals(7L, ((Number) other[4]).longValue());
    assertEquals("无记录", missing[3]);
    assertEquals(-1L, ((Number) missing[4]).longValue());
    System.out.println("PROD_P0_STREAMLOOKUP_COMPOSITE_STATION_YEAR_JSON_HPL_ROWS=PASS");
  }


  /**
   * Native StreamLookup converts a STRING key from the main stream to the
   * INTEGER key type of the lookup stream before comparing cached keys.
   * A leading-zero input must match without changing the main row's type.
   */
  @Test
  void streamLookupConvertsStringMainKeyToIntegerInfoKeyAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin lookupPlugin = plugins.findPluginWithId(TransformPluginType.class, "StreamLookup");
    assertNotNull(lookupPlugin, "pinned StreamLookup plugin required");

    ITransformMeta mainGrid = grid(
        plugins, List.of("年份文本<&>", "批次"),
        List.of("String", "String"),
        List.of(List.of("2025", "常规"), List.of("002026", "前导零"),
            List.of("2024", "未命中")));
    ITransformMeta infoGrid = grid(
        plugins, List.of("统计年<&>", "流量"),
        List.of("Integer", "Integer"),
        List.of(List.of("2025", "11"), List.of("2026", "42")));
    ITransformMeta lookup = plugins.loadClass(lookupPlugin, ITransformMeta.class);
    lookup.setDefault();

    TransformMeta main = new TransformMeta("DataGrid", "main-grid", mainGrid);
    TransformMeta info = new TransformMeta("DataGrid", "info-grid", infoGrid);
    TransformMeta target = new TransformMeta("StreamLookup", "lookup-node", lookup);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("streamlookup-string-integer-key-coercion");
    pipeline.addTransform(main);
    pipeline.addTransform(info);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(main, target));
    pipeline.addPipelineHop(new PipelineHopMeta(info, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("streamlookup-string-integer-key-coercion.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(2, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    String documentId = "streamlookup-string-integer-key-coercion";
    documents.put(new PipelineDocument(documentId, hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read(documentId,
        new PipelineConfigResource.ReadRequest("lookup-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("from", "info-grid");
    draft.put("preserve_memory", false);
    ObjectNode lookupDraft = draft.putObject("lookup");
    lookupDraft.putArray("key").addObject()
        .put("name", "年份文本<&>").put("field", "统计年<&>");
    lookupDraft.putArray("value").addObject()
        .put("name", "流量").put("rename", "匹配流量")
        .put("default", "-1").put("type", IValueMeta.TYPE_INTEGER);
    var write = resource.write(documentId,
        new PipelineConfigResource.WriteRequest("lookup-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(3, reopened.nrTransforms());
    assertEquals(2, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("lookup-node").getTransform(), provider);
    assertEquals("info-grid", wire.at("/from").asText());
    assertEquals("年份文本<&>", wire.at("/lookup/key/0/name").asText());
    assertEquals("统计年<&>", wire.at("/lookup/key/0/field").asText());
    assertTrue(wire.at("/lookup/value/0/type").isInt());
    assertEquals(IValueMeta.TYPE_INTEGER, wire.at("/lookup/value/0/type").intValue());

    var contract = AutomaticConfigDescriptor.describe(
        reopened.findTransform("lookup-node").getTransform().getClass())
        .runtimePreconditions().stream()
        .filter(p -> p.code().equals("STREAM_LOOKUP_INPUT_KEYS_CONVERT_TO_INFO_STREAM_TYPES"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/from", "/lookup/key"), contract.keyListPointers());
    assertTrue(contract.explanation().contains("convertDataCompatible()"));

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
      assertEquals(0, engine.getErrors(), "cross-type lookup must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    IRowMeta meta = outputMeta.get();
    assertNotNull(meta, "cross-type lookup must emit rows");
    assertEquals(3, meta.size());
    assertEquals("年份文本<&>", meta.getValueMeta(0).getName());
    assertEquals(IValueMeta.TYPE_STRING, meta.getValueMeta(0).getType());
    assertEquals("匹配流量", meta.getValueMeta(2).getName());
    assertEquals(IValueMeta.TYPE_INTEGER, meta.getValueMeta(2).getType());
    assertEquals(3, emitted.size());
    Object[] regular = emitted.stream()
        .filter(row -> "2025".equals(row[0])).findFirst().orElseThrow();
    Object[] leadingZero = emitted.stream()
        .filter(row -> "002026".equals(row[0])).findFirst().orElseThrow();
    Object[] missing = emitted.stream()
        .filter(row -> "2024".equals(row[0])).findFirst().orElseThrow();
    assertEquals(11L, ((Number) regular[2]).longValue());
    assertEquals(42L, ((Number) leadingZero[2]).longValue());
    assertEquals(-1L, ((Number) missing[2]).longValue());
    System.out.println("PROD_P0_STREAMLOOKUP_STRING_TO_INTEGER_KEY_JSON_HPL_ROWS=PASS");
  }

}
