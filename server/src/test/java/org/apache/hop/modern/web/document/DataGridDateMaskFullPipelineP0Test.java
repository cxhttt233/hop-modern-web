package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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

/** P0 native Date conversion using a date mask edited through the T3 JSON wire. */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class DataGridDateMaskFullPipelineP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void dataGridDateFormatMaskConvertsLeapDayRowsAfterRealHplDiskReopen(
      @TempDir Path tempDir) throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "DataGrid");
    assertNotNull(plugin, "production DataGrid plugin required");
    ITransformMeta grid = registry.loadClass(plugin, ITransformMeta.class);
    grid.setDefault();
    ClassLoader loader = grid.getClass().getClassLoader();
    Class<?> fieldClass = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Class<?> lineClass = loader.loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridDataMeta");

    List<Object> fields = new ArrayList<>();
    for (String[] definition : new String[][] {
        {"观测日期<&>雪", "Date"}, {"测站", "String"}}) {
      Object field = fieldClass.getConstructor().newInstance();
      fieldClass.getMethod("setName", String.class).invoke(field, definition[0]);
      fieldClass.getMethod("setType", String.class).invoke(field, definition[1]);
      if ("Date".equals(definition[1])) {
        fieldClass.getMethod("setFormat", String.class).invoke(field, "yyyy/MM/dd");
      }
      fields.add(field);
    }
    grid.getClass().getMethod("setDataGridFields", List.class).invoke(grid, fields);
    List<Object> lines = new ArrayList<>();
    for (List<String> values : List.of(
        List.of("2024-02-29", "黄河<&>"), List.of("2026-10-28", "长江_雪"))) {
      Object line = lineClass.getConstructor().newInstance();
      lineClass.getMethod("setDatalines", List.class).invoke(line, values);
      lines.add(line);
    }
    grid.getClass().getMethod("setDataLines", List.class).invoke(grid, lines);

    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("datagrid-date-mask-native-rows");
    pipeline.addTransform(new TransformMeta("DataGrid", "grid-node", grid));
    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("datagrid-date-mask.hpl");
    store.save(hpl, pipeline);
    PipelineMeta loaded = store.open(hpl);

    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("datagrid-date-mask-doc", hpl, loaded));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("datagrid-date-mask-doc",
        new PipelineConfigResource.ReadRequest("grid-node"));
    assertEquals(200, read.getStatus());
    ObjectNode draft = ((ObjectNode)
        ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
    assertEquals("Date", draft.at("/fields/field/0/field/type").asText());
    assertEquals("yyyy/MM/dd", draft.at("/fields/field/0/field/format").asText());
    ((ObjectNode) draft.at("/fields/field/0/field")).put("format", "yyyy-MM-dd");
    assertEquals(200, resource.write("datagrid-date-mask-doc",
        new PipelineConfigResource.WriteRequest("grid-node", draft)).getStatus());
    store.save(hpl, loaded);

    PipelineMeta reopened = store.open(hpl);
    JsonNode wire = ConfigJsonSerializer.toJson(
        reopened.findTransform("grid-node").getTransform(), provider);
    assertEquals("观测日期<&>雪", wire.at("/fields/field/0/field/name").asText());
    assertEquals("Date", wire.at("/fields/field/0/field/type").asText());
    assertEquals("yyyy-MM-dd", wire.at("/fields/field/0/field/format").asText());
    assertEquals("2024-02-29", wire.at("/data/line/0/item/0").asText());
    assertEquals("2026-10-28", wire.at("/data/line/1/item/0").asText());

    List<Object[]> rows = new CopyOnWriteArrayList<>();
    AtomicReference<IRowMeta> rowMeta = new AtomicReference<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(reopened);
    engine.setMetadataProvider(provider);
    try {
      engine.prepareExecution();
      ITransform output = engine.getRunThread("grid-node", 0);
      assertNotNull(output);
      output.addRowListener(new RowAdapter() {
        @Override
        public void rowWrittenEvent(IRowMeta meta, Object[] row) {
          rowMeta.set(meta.clone());
          rows.add(row.clone());
        }
      });
      engine.startThreads();
      engine.waitUntilFinished();
      assertEquals(0, engine.getErrors(), "native Date conversion must execute");
    } finally {
      if (!engine.isFinishedOrStopped()) engine.stopAll();
      engine.cleanup();
    }

    assertNotNull(rowMeta.get());
    assertEquals(2, rowMeta.get().size());
    assertEquals(IValueMeta.TYPE_DATE, rowMeta.get().getValueMeta(0).getType());
    assertEquals("yyyy-MM-dd", rowMeta.get().getValueMeta(0).getConversionMask());
    assertEquals(IValueMeta.TYPE_STRING, rowMeta.get().getValueMeta(1).getType());
    assertEquals(2, rows.size());
    SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
    assertInstanceOf(Date.class, rows.get(0)[0]);
    assertInstanceOf(Date.class, rows.get(1)[0]);
    assertEquals("2024-02-29", formatter.format((Date) rows.get(0)[0]));
    assertEquals("2026-10-28", formatter.format((Date) rows.get(1)[0]));
    assertEquals("黄河<&>", rows.get(0)[1]);
    assertEquals("长江_雪", rows.get(1)[1]);
    System.out.println("PROD_P0_DATAGRID_DATE_MASK_LEAP_DAY_JSON_HPL_ROWS=PASS");
  }
}
