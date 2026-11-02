package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.core.row.value.ValueMetaFactory;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.json.ConfigJsonSerializer;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.tableinput.TableInputField;
import org.apache.hop.pipeline.transforms.tableinput.TableInputMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * T3 producer acceptance: TableInput fields.field[*].type is a numeric JSON
 * wire value despite its native Hop XML intCodeConverter. Unlike SelectValues
 * fields.meta[*].type, the editor must not write a type-name STRING here.
 */
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class TableInputNativeValueMetaTypeP0Test {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void nativeNumericTypePickerSurvivesConfigWriteAndRealHplDiskReopen(
      @TempDir Path tempDir) throws Exception {
    var descriptor = AutomaticConfigDescriptor.describe(TableInputMeta.class);
    assertEquals("/fields/field", descriptor.wireLists().get(0).listPointer());
    var fieldList = descriptor.properties().stream()
        .filter(p -> p.key().equals("field")).findFirst().orElseThrow();
    var type = fieldList.elementProperties().stream()
        .filter(p -> p.key().equals("type")).findFirst().orElseThrow();
    assertEquals(AutomaticConfigDescriptor.Shape.NUMBER, type.shape());
    assertEquals("int", type.javaType());
    assertEquals(AutomaticConfigDescriptor.WidgetHint.VALUE_META_CODE_PICKER, type.widgetHint());
    assertTrue(type.options().stream().anyMatch(o ->
        o.label().equals(ValueMetaFactory.getValueMetaName(IValueMeta.TYPE_NONE))
            && o.value().equals(IValueMeta.TYPE_NONE)));
    assertTrue(type.options().stream().anyMatch(o ->
        o.label().equals(ValueMetaFactory.getValueMetaName(IValueMeta.TYPE_INTEGER))
            && o.value().equals(IValueMeta.TYPE_INTEGER)));
    assertTrue(type.options().stream().anyMatch(o ->
        o.label().equals(ValueMetaFactory.getValueMetaName(IValueMeta.TYPE_BIGNUMBER))
            && o.value().equals(IValueMeta.TYPE_BIGNUMBER)));
    assertTrue(type.options().stream().allMatch(o -> o.value() instanceof Integer));
    var published = new ObjectMapper().valueToTree(type);
    assertEquals("NUMBER", published.path("shape").asText());
    assertEquals("VALUE_META_CODE_PICKER", published.path("widgetHint").asText());
    assertTrue(published.path("options").isArray());
    assertTrue(published.path("options").get(0).path("value").isIntegralNumber());

    var first = new TableInputField();
    first.setName("流量<&>甲");
    first.setType(IValueMeta.TYPE_NUMBER);
    first.setFormat("0.00 '初始<&>'");
    first.setLength(18);
    first.setPrecision(3);
    var second = new TableInputField();
    second.setName("站点乙");
    second.setType(IValueMeta.TYPE_STRING);
    second.setFormat("yyyy-MM-dd");
    second.setLength(64);
    var nativeMeta = new TableInputMeta();
    nativeMeta.setDefault();
    nativeMeta.setConnection("Warehouse");
    nativeMeta.setSql("SELECT 1 AS 流量");
    nativeMeta.setSpecifyFields(true);
    nativeMeta.setFields(List.of(first, second));

    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    var pipeline = new PipelineMeta();
    pipeline.setName("tableinput-native-numeric-value-meta-type");
    pipeline.addTransform(new TransformMeta("TableInput", "table-node", nativeMeta));
    Path hpl = tempDir.resolve("tableinput-native-type.hpl");
    store.save(hpl, pipeline);
    var loaded = store.open(hpl);

    var registry = new PipelineDocumentRegistry();
    registry.put(new PipelineDocument("table-type-doc", hpl, loaded));
    var resource = new PipelineConfigResource(registry, provider);
    var read = resource.read("table-type-doc",
        new PipelineConfigResource.ReadRequest("table-node"));
    assertEquals(200, read.getStatus());
    var original = (PipelineConfigResource.ConfigResponse) read.getEntity();
    ObjectNode draft = ((ObjectNode) original.config()).deepCopy();
    assertTrue(draft.at("/fields/field/0/type").isIntegralNumber());
    assertEquals(IValueMeta.TYPE_NUMBER, draft.at("/fields/field/0/type").intValue());
    assertEquals(IValueMeta.TYPE_STRING, draft.at("/fields/field/1/type").intValue());
    assertEquals("0.00 '初始<&>'", draft.at("/fields/field/0/format").asText());
    assertEquals("yyyy-MM-dd", draft.at("/fields/field/1/format").asText());
    ((ObjectNode) draft.at("/fields/field/0")).put("type", IValueMeta.TYPE_INTEGER);
    ((ObjectNode) draft.at("/fields/field/1")).put("type", IValueMeta.TYPE_BIGNUMBER);
    ((ObjectNode) draft.at("/fields/field/0")).put("format", "0.000 '黄河<&>'");
    ((ObjectNode) draft.at("/fields/field/1")).put("format", "yyyy/MM/dd HH:mm:ss");

    var write = resource.write("table-type-doc",
        new PipelineConfigResource.WriteRequest("table-node", draft));
    assertEquals(200, write.getStatus(), "numeric type codes must be accepted by config/write");
    store.save(hpl, loaded);
    var reopened = store.open(hpl);
    var reopenedMeta = (TableInputMeta) reopened.findTransform("table-node").getTransform();
    var wire = ConfigJsonSerializer.toJson(reopenedMeta, provider);
    assertEquals(2, wire.at("/fields/field").size());
    assertTrue(wire.at("/fields/field/0/type").isIntegralNumber());
    assertTrue(wire.at("/fields/field/1/type").isIntegralNumber());
    assertEquals(IValueMeta.TYPE_INTEGER, wire.at("/fields/field/0/type").intValue());
    assertEquals(IValueMeta.TYPE_BIGNUMBER, wire.at("/fields/field/1/type").intValue());
    assertEquals(IValueMeta.TYPE_INTEGER, reopenedMeta.getFields().get(0).getType());
    assertEquals(IValueMeta.TYPE_BIGNUMBER, reopenedMeta.getFields().get(1).getType());
    assertEquals("流量<&>甲", wire.at("/fields/field/0/name").asText());
    assertEquals("站点乙", wire.at("/fields/field/1/name").asText());
    assertEquals("0.000 '黄河<&>'", wire.at("/fields/field/0/format").asText());
    assertEquals("yyyy/MM/dd HH:mm:ss", wire.at("/fields/field/1/format").asText());
    assertEquals("0.000 '黄河<&>'", reopenedMeta.getFields().get(0).getFormat());
    assertEquals("yyyy/MM/dd HH:mm:ss", reopenedMeta.getFields().get(1).getFormat());
    assertEquals("0.000 '黄河<&>'",
        reopenedMeta.getFields().get(0).toValueMeta("table-node", new Variables()).getConversionMask());
    assertEquals("yyyy/MM/dd HH:mm:ss",
        reopenedMeta.getFields().get(1).toValueMeta("table-node", new Variables()).getConversionMask());
    assertEquals(18, wire.at("/fields/field/0/length").intValue());
    assertEquals(3, wire.at("/fields/field/0/precision").intValue());
    System.out.println("PROD_P0_TABLEINPUT_NUMERIC_VALUE_META_PICKER_JSON_HPL=PASS");
    System.out.println("PROD_P0_TABLEINPUT_FORMAT_MASK_JSON_HPL=PASS");
  }
  @Test
  void nativeInfoStreamLookupRetargetsAcrossConfigWriteAndHplDiskReopen(
      @TempDir Path tempDir) throws Exception {
    var descriptor = AutomaticConfigDescriptor.describe(TableInputMeta.class);
    var lookupProperty = descriptor.properties().stream()
        .filter(p -> p.key().equals("lookup")).findFirst().orElseThrow();
    assertEquals(AutomaticConfigDescriptor.Shape.STRING, lookupProperty.shape());
    assertEquals(AutomaticConfigDescriptor.WidgetHint.INFO_STREAM_TRANSFORM_PICKER,
        lookupProperty.widgetHint());
    assertNull(lookupProperty.metadataClassName(),
        "INFO-stream transform references must not be confused with database metadata");

    String originalSourceName = "参数源甲<&>";
    String selectedSourceName = "参数源乙<&雪>";
    var sourceA = new TableInputMeta();
    sourceA.setDefault();
    sourceA.setConnection("Warehouse");
    sourceA.setSql("SELECT 1");
    var sourceB = new TableInputMeta();
    sourceB.setDefault();
    sourceB.setConnection("Warehouse");
    sourceB.setSql("SELECT 2");
    var target = new TableInputMeta();
    target.setDefault();
    target.setConnection("Warehouse");
    target.setSql("SELECT ? AS level_value");
    target.setExecuteEachInputRow(true);
    target.setLookup(originalSourceName);

    var pipeline = new PipelineMeta();
    pipeline.setName("tableinput-info-source-lookup");
    pipeline.addTransform(new TransformMeta("TableInput", originalSourceName, sourceA));
    pipeline.addTransform(new TransformMeta("TableInput", selectedSourceName, sourceB));
    pipeline.addTransform(new TransformMeta("TableInput", "query-node", target));
    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path hpl = tempDir.resolve("tableinput-info-lookup.hpl");
    store.save(hpl, pipeline);
    var loaded = store.open(hpl);
    var registry = new PipelineDocumentRegistry();
    registry.put(new PipelineDocument("table-info-doc", hpl, loaded));
    var resource = new PipelineConfigResource(registry, provider);
    var read = resource.read("table-info-doc",
        new PipelineConfigResource.ReadRequest("query-node"));
    assertEquals(200, read.getStatus());
    var initial = (PipelineConfigResource.ConfigResponse) read.getEntity();
    assertEquals(originalSourceName, initial.config().path("lookup").asText());
    assertTrue(initial.config().path("execute_each_row").booleanValue());

    ObjectNode edited = ((ObjectNode) initial.config()).deepCopy();
    edited.put("lookup", selectedSourceName);
    var write = resource.write("table-info-doc",
        new PipelineConfigResource.WriteRequest("query-node", edited));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    var reopened = store.open(hpl);
    var reopenedTarget = (TableInputMeta) reopened.findTransform("query-node").getTransform();
    var wire = ConfigJsonSerializer.toJson(reopenedTarget, provider);
    assertEquals(selectedSourceName, wire.path("lookup").asText());
    assertEquals(selectedSourceName, reopenedTarget.getLookup());
    assertEquals("SELECT ? AS level_value", reopenedTarget.getSql());
    assertTrue(reopenedTarget.isExecuteEachInputRow());
    var infoStream = reopenedTarget.getTransformIOMeta().getInfoStreams().get(0);
    assertEquals(selectedSourceName, infoStream.getSubject());
    reopenedTarget.searchInfoAndTargetTransforms(reopened.getTransforms());
    assertNotNull(infoStream.getTransformMeta(),
        "native INFO-stream lookup must resolve the renamed source in the reopened graph");
    assertEquals(selectedSourceName, infoStream.getTransformMeta().getName());
    System.out.println("PROD_P0_TABLEINPUT_INFO_STREAM_LOOKUP_JSON_HPL_GRAPH=PASS");
  }

}
