package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.core.row.value.ValueMetaFactory;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.json.ConfigJsonSerializer;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.selectvalues.DeleteField;
import org.apache.hop.pipeline.transforms.selectvalues.SelectField;
import org.apache.hop.pipeline.transforms.selectvalues.SelectMetadataChange;
import org.apache.hop.pipeline.transforms.selectvalues.SelectOptions;
import org.apache.hop.pipeline.transforms.selectvalues.SelectValuesMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Native config producer + real HPL serializer round-trip; no React-only fixture or mock. */
class SelectValuesNativeListRoundTripTest {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void threeIndependentListsKeepSemanticsAcrossConfigWriteAndHplDiskReopen(@TempDir Path dir)
      throws Exception {
    var descriptor = AutomaticConfigDescriptor.describe(SelectValuesMeta.class);
    assertEquals(3, descriptor.wireLists().size());
    assertEquals("/fields/field", descriptor.wireLists().get(0).listPointer());
    assertEquals("/fields/remove", descriptor.wireLists().get(1).listPointer());
    assertEquals("/fields/meta", descriptor.wireLists().get(2).listPointer());
    var fields = descriptor.properties().stream().filter(p -> p.key().equals("fields"))
        .findFirst().orElseThrow();
    var selected = fields.children().stream().filter(p -> p.key().equals("field"))
        .findFirst().orElseThrow();
    var metadata = fields.children().stream().filter(p -> p.key().equals("meta"))
        .findFirst().orElseThrow();
    assertEquals(-2, selected.elementProperties().stream().filter(p -> p.key().equals("length"))
        .findFirst().orElseThrow().nativeNumberDefault());
    assertEquals(-1, metadata.elementProperties().stream().filter(p -> p.key().equals("length"))
        .findFirst().orElseThrow().nativeNumberDefault());
    assertEquals(ValueMetaFactory.getValueMetaName(IValueMeta.TYPE_NONE),
        metadata.elementProperties().stream().filter(p -> p.key().equals("type"))
            .findFirst().orElseThrow().nativeStringDefault());

    var selectedRow = new SelectField();
    selectedRow.setName("上游流量");
    selectedRow.setRename("flow_before");
    var removeRow = new DeleteField();
    removeRow.setName("deprecated");
    var metaRow = new SelectMetadataChange();
    metaRow.setName("flow_before");
    metaRow.setType(ValueMetaFactory.getValueMetaName(IValueMeta.TYPE_NUMBER));

    var options = new SelectOptions();
    options.getSelectFields().add(selectedRow);
    options.getDeleteName().add(removeRow);
    options.getMeta().add(metaRow);
    var nativeMeta = new SelectValuesMeta();
    nativeMeta.setSelectOption(options);
    var pipeline = new PipelineMeta();
    pipeline.setName("selectvalues-native-three-lists");
    pipeline.addTransform(new TransformMeta("SelectValues", "selector", nativeMeta));
    var provider = new MemoryMetadataProvider();
    var store = new PipelineDocumentStore(provider, new Variables());
    Path file = dir.resolve("native-select-values.hpl");
    store.save(file, pipeline);
    var reopened = store.open(file);
    var registry = new PipelineDocumentRegistry();
    registry.put(new PipelineDocument("select-doc", file, reopened));
    var resource = new PipelineConfigResource(registry, provider);

    var initial = (PipelineConfigResource.ConfigResponse) resource.read(
        "select-doc", new PipelineConfigResource.ReadRequest("selector")).getEntity();
    assertEquals("上游流量", initial.config().at("/fields/field/0/name").asText());
    assertEquals("deprecated", initial.config().at("/fields/remove/0/name").asText());
    assertEquals("flow_before", initial.config().at("/fields/meta/0/name").asText());

    ObjectNode draft = ((ObjectNode) initial.config()).deepCopy();
    ((ObjectNode) draft.at("/fields/field/0")).put("rename", "黄河流量");
    ((ObjectNode) draft.at("/fields/remove/0")).put("name", "移除旧值");
    ((ObjectNode) draft.at("/fields/meta/0")).put("name", "黄河流量");
    ((ObjectNode) draft.at("/fields/meta/0")).put("length", 12);
    ((ObjectNode) draft.path("fields")).put("select_unspecified", true);
    var result = resource.write("select-doc",
        new PipelineConfigResource.WriteRequest("selector", draft));
    assertEquals(200, result.getStatus(), "native Java decoder must accept all three independent lists");

    store.save(file, reopened);
    var diskReopened = store.open(file);
    var select = (SelectValuesMeta) diskReopened.findTransform("selector").getTransform();
    var json = ConfigJsonSerializer.toJson(select, provider);
    assertEquals("上游流量", json.at("/fields/field/0/name").asText());
    assertEquals("黄河流量", json.at("/fields/field/0/rename").asText());
    assertEquals(-2, json.at("/fields/field/0/length").intValue());
    assertEquals("移除旧值", json.at("/fields/remove/0/name").asText());
    assertEquals("黄河流量", json.at("/fields/meta/0/name").asText());
    assertEquals(ValueMetaFactory.getValueMetaName(IValueMeta.TYPE_NUMBER),
        json.at("/fields/meta/0/type").asText());
    assertEquals(12, json.at("/fields/meta/0/length").intValue());
    assertEquals(-1, json.at("/fields/meta/0/precision").intValue());
    assertTrue(json.at("/fields/select_unspecified").booleanValue());
    assertFalse(json.has("field"), "nested list cannot appear at the JSON root");
    assertFalse(json.has("remove"), "native delete list cannot appear at the JSON root");
    assertFalse(json.has("meta"), "native metadata list cannot appear at the JSON root");
  }
}
