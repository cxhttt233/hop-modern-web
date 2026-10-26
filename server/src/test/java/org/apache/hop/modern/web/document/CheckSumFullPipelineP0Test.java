package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
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
 * Production engine acceptance: execute a real DataGrid -> CheckSum pipeline
 * after editing the nested field list and coded enums through the T3 JSON API,
 * saving native Hop XML, and reopening the .hpl from disk. This checks emitted
 * SHA-256 bytes, not just metadata or serializer round trips.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class CheckSumFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void checkSumOrderedFieldsAndBinarySha256ExecuteFullPipelineAfterHplReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin gridPlugin = plugins.findPluginWithId(TransformPluginType.class, "DataGrid");
    IPlugin checksumPlugin = plugins.findPluginWithId(TransformPluginType.class, "CheckSum");
    assertNotNull(gridPlugin, "pinned DataGrid plugin required");
    assertNotNull(checksumPlugin, "pinned CheckSum plugin required");

    ITransformMeta grid = plugins.loadClass(gridPlugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader gridLoader = grid.getClass().getClassLoader();
    Class<?> fieldType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineType = gridLoader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");
    List<Object> gridFields = new ArrayList<>();
    for (String name : List.of("河流<&>甲", "工程_乙")) {
      Object field = fieldType.getConstructor().newInstance();
      fieldType.getMethod("setName", String.class).invoke(field, name);
      fieldType.getMethod("setType", String.class).invoke(field, "String");
      gridFields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, gridFields);
    List<Object> gridLines = new ArrayList<>();
    List<List<String>> sourceRows = List.of(
        List.of("黄河", "水网"),
        List.of("长江", "数字孪生"));
    for (List<String> values : sourceRows) {
      Object line = lineType.getConstructor().newInstance();
      lineType.getMethod("setDatalines", List.class).invoke(line, values);
      gridLines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, gridLines);

    ITransformMeta checksum = plugins.loadClass(checksumPlugin, ITransformMeta.class);
    checksum.setDefault();
    TransformMeta source = new TransformMeta("DataGrid", "grid-node", grid);
    TransformMeta target = new TransformMeta("CheckSum", "checksum-node", checksum);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("checksum-binary-full-pipeline");
    pipeline.addTransform(source);
    pipeline.addTransform(target);
    pipeline.addPipelineHop(new PipelineHopMeta(source, target));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("checksum-binary-full-pipeline.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);
    assertEquals(1, loaded.nrPipelineHops());

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("checksum-full-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("checksum-full-doc",
        new PipelineConfigResource.ReadRequest("checksum-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    draft.put("checksumtype", "SHA-256");
    draft.put("resultType", "binary");
    draft.put("resultfieldName", "摘要<&>值");
    draft.put("separator", "|");
    draft.put("prefix", "前缀：");
    draft.put("suffix", "<尾>");
    ArrayNode selected = draft.putObject("fields").putArray("field");
    selected.addObject().put("name", "河流<&>甲");
    selected.addObject().put("name", "工程_乙");
    var write = resource.write("checksum-full-doc",
        new PipelineConfigResource.WriteRequest("checksum-node", draft));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    assertEquals(2, reopened.nrTransforms());
    assertEquals(1, reopened.nrPipelineHops());
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("checksum-node").getTransform(), provider);
    assertEquals("SHA-256", wire.at("/checksumtype").asText());
    assertEquals("binary", wire.at("/resultType").asText());
    assertEquals("摘要<&>值", wire.at("/resultfieldName").asText());
    assertEquals("河流<&>甲", wire.at("/fields/field/0/name").asText());
    assertEquals("工程_乙", wire.at("/fields/field/1/name").asText());
    assertEquals("|", wire.at("/separator").asText());
    assertEquals("前缀：", wire.at("/prefix").asText());
    assertEquals("<尾>", wire.at("/suffix").asText());

    List<Object[]> emitted = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> emittedMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("checksum-node", 0);
      assertNotNull(output, "real engine must prepare CheckSum");
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) {
          emittedMeta.set(rowMeta.clone());
          emitted.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native full pipeline must finish without errors");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(emittedMeta.get(), "CheckSum must emit native rows");
    assertEquals(3, emittedMeta.get().size());
    assertEquals("河流<&>甲", emittedMeta.get().getValueMeta(0).getName());
    assertEquals("工程_乙", emittedMeta.get().getValueMeta(1).getName());
    assertEquals("摘要<&>值", emittedMeta.get().getValueMeta(2).getName());
    assertEquals(IValueMeta.TYPE_BINARY, emittedMeta.get().getValueMeta(2).getType());
    assertEquals(2, emitted.size());
    for (int i = 0; i < sourceRows.size(); i++) {
      assertEquals(sourceRows.get(i).get(0), emitted.get(i)[0]);
      assertEquals(sourceRows.get(i).get(1), emitted.get(i)[1]);
      byte[] expected = MessageDigest.getInstance("SHA-256").digest(
          ("前缀：" + sourceRows.get(i).get(0) + "|" + sourceRows.get(i).get(1) + "<尾>")
              .getBytes(StandardCharsets.UTF_8));
      assertInstanceOf(byte[].class, emitted.get(i)[2]);
      assertArrayEquals(expected, (byte[]) emitted.get(i)[2]);
    }
    System.out.println("PROD_P0_CHECKSUM_FULL_PIPELINE_BINARY_SHA256_JSON_HPL_ROWS=PASS");
  }
}
