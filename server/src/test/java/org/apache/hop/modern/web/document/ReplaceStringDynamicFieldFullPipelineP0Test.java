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
 * Production ReplaceString acceptance for dynamic per-row replacement fields.
 * This is distinct from regex rule serialization and static output-schema tests:
 * a real pipeline must consume each incoming row's replacement field rather than
 * the configured replacement literal after a JSON edit and HPL disk reopen.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class ReplaceStringDynamicFieldFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void replaceStringUsesReplacementFieldFromEachRealRowAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin replacePlugin = plugins.findPluginWithId(TransformPluginType.class, "ReplaceString");
    assertNotNull(gridPlugin, "pinned production DataGrid plugin required");
    assertNotNull(replacePlugin, "pinned production ReplaceString plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> fields = new ArrayList<>();
    for (String name : List.of("源文本<&>", "每行替换值")) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      fields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, fields);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("黄河旧站", "一号"),
        List.of("长江旧站", "二号"))) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    ITransformMeta replace = plugins.loadClass(replacePlugin, ITransformMeta.class);
    replace.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("ReplaceString", "replace-node", replace);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("replace-string-dynamic-per-row-field");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("replace-dynamic-field.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("replace-dynamic-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("replace-dynamic-doc",
        new PipelineConfigResource.ReadRequest("replace-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    ArrayNode rules = draft.putObject("fields").putArray("field");
    rules.addObject()
        .put("in_stream_name", "源文本<&>")
        .put("out_stream_name", "替换结果")
        .put("use_regex", false)
        .put("replace_string", "旧")
        .put("replace_by_string", "不应使用此固定值")
        .put("replace_field_by_string", "每行替换值")
        .put("set_empty_string", false)
        .put("whole_word", false)
        .put("case_sensitive", true)
        .put("is_unicode", true);
    var write = resource.write("replace-dynamic-doc",
        new PipelineConfigResource.WriteRequest("replace-node", draft));
    assertEquals(200, write.getStatus(), "native per-row replacement config must be writable");
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("replace-node").getTransform(), provider);
    assertEquals(1, wire.at("/fields/field").size());
    assertEquals("源文本<&>", wire.at("/fields/field/0/in_stream_name").asText());
    assertEquals("旧", wire.at("/fields/field/0/replace_string").asText());
    assertEquals("不应使用此固定值", wire.at("/fields/field/0/replace_by_string").asText());
    assertEquals("每行替换值", wire.at("/fields/field/0/replace_field_by_string").asText());
    assertFalse(wire.at("/fields/field/0/use_regex").asBoolean());
    assertFalse(wire.at("/fields/field/0/set_empty_string").asBoolean());
    assertTrue(wire.at("/fields/field/0/case_sensitive").asBoolean());

    var contract = AutomaticConfigDescriptor.describe(
        reopened.findTransform("replace-node").getTransform().getClass())
        .runtimePreconditions().stream()
        .filter(p -> p.code().equals("REPLACE_STRING_REPLACEMENT_FIELD_RESOLVES_PER_INPUT_ROW"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), contract.keyListPointers());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> outputMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("replace-node", 0);
      assertNotNull(output, "native ReplaceString engine must prepare");
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          outputMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native ReplaceString pipeline must finish");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(outputMeta.get(), "native ReplaceString must emit real rows");
    assertEquals(3, outputMeta.get().size());
    assertEquals(List.of("源文本<&>", "每行替换值", "替换结果"),
        outputMeta.get().getValueMetaList().stream().map(IValueMeta::getName).toList());
    assertEquals(IValueMeta.TYPE_STRING, outputMeta.get().getValueMeta(2).getType());
    assertEquals(2, emitted.size());
    assertEquals("黄河旧站", emitted.get(0)[0]);
    assertEquals("一号", emitted.get(0)[1]);
    assertEquals("黄河一号站", emitted.get(0)[2]);
    assertEquals("长江旧站", emitted.get(1)[0]);
    assertEquals("二号", emitted.get(1)[1]);
    assertEquals("长江二号站", emitted.get(1)[2]);
    System.out.println("PROD_P0_REPLACESTRING_DYNAMIC_REPLACEMENT_FIELD_FULL_PIPELINE=PASS");
  }
}
