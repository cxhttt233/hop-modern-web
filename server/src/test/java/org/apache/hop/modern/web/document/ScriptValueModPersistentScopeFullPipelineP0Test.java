package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * Native engine acceptance for the producer-owned JavaScript scope lifetime contract.
 * An output never assigned is null, but one conditionally assigned on a prior row
 * remains in the reused Rhino scope. Also proves replace-by-rename fallback.
 * No Hop Core or consumer React code is modified.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class ScriptValueModPersistentScopeFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  private static ITransformMeta grid(PluginRegistry plugins) throws Exception {
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
        {"station", "String"}, {"flow", "Integer"}}) {
      Object field = fieldClass.getConstructor().newInstance();
      fieldClass.getMethod("setName", String.class).invoke(field, spec[0]);
      fieldClass.getMethod("setType", String.class).invoke(field, spec[1]);
      fields.add(field);
    }
    meta.getClass().getMethod("setDataGridFields", List.class).invoke(meta, fields);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("黄河<&雪>", "3"),
        List.of("长江<&雪>", "7"),
        List.of("珠江", "2"))) {
      Object line = lineClass.getConstructor().newInstance();
      lineClass.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    meta.getClass().getMethod("setDataLines", List.class).invoke(meta, lines);
    return meta;
  }

  @Test
  void conditionalScriptResultRetainsPreviousRowWhileNeverAssignedResultIsNull(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin scriptPlugin = plugins.findPluginWithId(TransformPluginType.class, "ScriptValueMod");
    assertNotNull(scriptPlugin, "pinned ScriptValueMod plugin required");
    ITransformMeta scriptMeta = plugins.loadClass(scriptPlugin, ITransformMeta.class);
    scriptMeta.setDefault();

    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid(plugins));
    TransformMeta target = new TransformMeta("ScriptValueMod", "script-node", scriptMeta);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("script-persistent-scope-native");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("script-persistent-scope-雪.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(2, loaded.nrTransforms());
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("script-scope-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var response = resource.read("script-scope-doc",
        new PipelineConfigResource.ReadRequest("script-node"));
    assertEquals(200, response.getStatus());
    var payload = (PipelineConfigResource.ConfigResponse) response.getEntity();
    var diagnostic = payload.descriptor().runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "SCRIPT_VALUE_MOD_CONDITIONAL_OUTPUT_RETAINS_PRIOR_ROW_VALUE"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/jsScripts/jsScript", "/fields/field"),
        diagnostic.keyListPointers());
    assertTrue(diagnostic.explanation().contains("scope"));

    ObjectNode draft = ((ObjectNode) payload.config()).deepCopy();
    JsonNode scriptNodes = draft.at("/jsScripts/jsScript");
    assertTrue(scriptNodes instanceof ArrayNode && !scriptNodes.isEmpty(),
        "native default script must be serialized in its canonical groupKey container");
    ((ObjectNode) scriptNodes.get(0)).put("jsScript_script",
        "var adjusted = flow * 2;\n"
            + "if (flow > 5) { carry = station + ':' + adjusted; }\n");
    ArrayNode outputFields = draft.putObject("fields").putArray("field");
    ObjectNode replacement = outputFields.addObject();
    replacement.put("name", "adjusted");
    replacement.put("rename", "flow");
    replacement.put("type", IValueMeta.TYPE_INTEGER);
    replacement.put("length", 12);
    replacement.put("precision", 0);
    replacement.put("replace", true);
    ObjectNode carry = outputFields.addObject();
    carry.put("name", "carry");
    carry.put("rename", "状态<&雪>");
    carry.put("type", IValueMeta.TYPE_STRING);
    carry.put("length", 120);
    carry.put("precision", 0);
    carry.put("replace", false);

    var written = resource.write("script-scope-doc",
        new PipelineConfigResource.WriteRequest("script-node", draft));
    assertEquals(200, written.getStatus(), String.valueOf(written.getEntity()));
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("script-node").getTransform(), provider);
    assertEquals(2, wire.at("/fields/field").size());
    assertEquals("adjusted", wire.at("/fields/field/0/name").asText());
    assertEquals("flow", wire.at("/fields/field/0/rename").asText());
    assertTrue(wire.at("/fields/field/0/replace").booleanValue());
    assertEquals("状态<&雪>", wire.at("/fields/field/1/rename").asText());
    assertTrue(wire.at("/jsScripts/jsScript/0/jsScript_script").asText()
        .contains("if (flow > 5)"));

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    List<IRowMeta> outputMetas = new CopyOnWriteArrayList<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("script-node", 0);
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
      assertEquals(0, engine.getErrors(), "native ScriptValueMod must complete");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertEquals(3, emitted.size());
    assertFalse(outputMetas.isEmpty());
    IRowMeta resultMeta = outputMetas.get(0);
    assertEquals(3, resultMeta.size());
    assertEquals(List.of("station", "flow", "状态<&雪>"),
        resultMeta.getValueMetaList().stream().map(v -> v.getName()).toList());
    assertEquals(IValueMeta.TYPE_INTEGER, resultMeta.getValueMeta(1).getType());
    assertEquals(IValueMeta.TYPE_STRING, resultMeta.getValueMeta(2).getType());
    assertEquals("黄河<&雪>", emitted.get(0)[0]);
    assertEquals(6L, ((Number) emitted.get(0)[1]).longValue());
    assertNull(emitted.get(0)[2], "never-assigned script result must be null");
    assertEquals("长江<&雪>", emitted.get(1)[0]);
    assertEquals(14L, ((Number) emitted.get(1)[1]).longValue());
    assertEquals("长江<&雪>:14", emitted.get(1)[2]);
    assertEquals("珠江", emitted.get(2)[0]);
    assertEquals(4L, ((Number) emitted.get(2)[1]).longValue());
    assertEquals("长江<&雪>:14", emitted.get(2)[2],
        "Rhino scope persists; skipped assignment reuses the previous row's value");
    System.out.println(
        "PROD_P0_SCRIPTVALUE_PERSISTENT_SCOPE_CONDITIONAL_OUTPUT_JSON_HPL_ROWS=PASS");
  }
}
