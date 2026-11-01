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
 * Negative native-engine acceptance for the pinned headless ScriptValueMod classpath.
 * JSON/HPL editing succeeds, but Rhino function registration cannot link HopGui.
 * This test does not claim JavaScript row output or scope semantics were executed.
 * No Hop Core or consumer React code is modified.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class ScriptValueModHeadlessRuntimeP0Test {
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
  void headlessScriptValueModFailsAtNativeRhinoRegistrationAfterHplEdit(
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
            "SCRIPT_VALUE_MOD_HEADLESS_GUI_LINKAGE_BLOCKS_ENGINE"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/jsScripts/jsScript", "/fields/field"),
        diagnostic.keyListPointers());
    assertTrue(diagnostic.explanation().contains("HopGui"));

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

    // Native runtime classpath gate: the pinned JavaScript helper has a synthetic
    // lambda method whose signature requires the absent desktop HopGui class.
    assertThrows(ClassNotFoundException.class,
        () -> Class.forName("org.apache.hop.ui.hopgui.HopGui"));
    Class<?> nativeFunctions = Class.forName(
        "org.apache.hop.pipeline.transforms.javascript.ScriptValuesAddedFunctions");
    NoClassDefFoundError linkage = assertThrows(
        NoClassDefFoundError.class, () -> nativeFunctions.getDeclaredMethods());
    assertTrue(linkage.getMessage().contains("org/apache/hop/ui/hopgui/HopGui"));

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
      assertTrue(engine.getErrors() > 0, "headless native engine must report the GUI linkage blocker");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertTrue(emitted.isEmpty(),
        "ScriptValueMod must not claim native output rows when GUI linkage fails");
    assertTrue(outputMetas.isEmpty());
    System.out.println(
        "PROD_P0_SCRIPTVALUE_HEADLESS_GUI_LINKAGE_NEGATIVE_ENGINE_GATE=PASS");
  }
}
