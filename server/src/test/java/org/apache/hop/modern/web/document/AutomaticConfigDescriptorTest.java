package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Descriptor;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Property;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Presentation;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Shape;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.TextEditorHint;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.WidgetHint;
import org.apache.hop.metadata.api.HopMetadataProperty;
import org.apache.hop.metadata.api.HopMetadataPropertyType;
import org.apache.hop.metadata.api.IEnumHasCodeAndDescription;
import org.apache.hop.pipeline.transforms.rest.RestMeta;
import org.apache.hop.pipeline.transforms.selectvalues.SelectValuesMeta;
import org.apache.hop.pipeline.transforms.sort.SortRowsMeta;
import org.apache.hop.pipeline.transforms.tableinput.TableInputMeta;
import org.apache.hop.pipeline.transforms.tableoutput.TableOutputMeta;
import org.junit.jupiter.api.Test;

class AutomaticConfigDescriptorTest {
  @Test
  void discoversNestedSelectValuesMetadataWithoutPluginDescriptor() {
    Descriptor descriptor = AutomaticConfigDescriptor.describe(SelectValuesMeta.class);
    Property fields = property(descriptor.properties(), "fields");

    assertEquals(Shape.OBJECT, fields.shape());
    assertEquals(List.of("field", "select_unspecified", "remove", "meta"),
        fields.children().stream().map(Property::key).toList());
    Property selected = property(fields.children(), "field");
    assertEquals(Shape.LIST, selected.shape());
    assertFalse(selected.elementProperties().isEmpty());
    assertEquals(0, fields.layout().order());
    assertEquals(Presentation.FIELD, fields.layout().presentation());
  }

  @Test
  void marksSelectValuesSelectedInputFieldButNotOutputRenameOrLaterStageNames() {
    Descriptor descriptor = AutomaticConfigDescriptor.describe(SelectValuesMeta.class);
    Property fields = property(descriptor.properties(), "fields");
    Property selected = property(fields.children(), "field");

    assertEquals(Shape.LIST, selected.shape());
    assertEquals("org.apache.hop.pipeline.transforms.selectvalues.SelectField",
        selected.elementJavaType());
    assertEquals(WidgetHint.UPSTREAM_FIELD_PICKER,
        property(selected.elementProperties(), "name").widgetHint());
    assertEquals(WidgetHint.NONE,
        property(selected.elementProperties(), "rename").widgetHint());
    assertEquals(Shape.NUMBER,
        property(selected.elementProperties(), "length").shape());

    // Remove and Meta consume the Select tab's output, not necessarily the raw input.
    // A raw upstream picker for these names would hide newly renamed columns.
    assertEquals(WidgetHint.NONE,
        property(property(fields.children(), "remove").elementProperties(), "name").widgetHint());
    assertEquals(WidgetHint.NONE,
        property(property(fields.children(), "meta").elementProperties(), "name").widgetHint());
  }

  @Test
  void discoversSortRowsListElementAndScalarMetadata() {
    Descriptor descriptor = AutomaticConfigDescriptor.describe(SortRowsMeta.class);
    Property sortFields = property(descriptor.properties(), "field");

    assertEquals(Shape.LIST, sortFields.shape());
    assertEquals("fields", sortFields.groupKey());
    assertEquals("fields", sortFields.layout().group());
    assertEquals(Presentation.TABLE, sortFields.layout().presentation());
    assertEquals("org.apache.hop.pipeline.transforms.sort.SortRowsField", sortFields.elementJavaType());
    assertTrue(sortFields.elementProperties().stream().anyMatch(p -> p.key().equals("ascending") && p.shape() == Shape.BOOLEAN));
    assertEquals(Shape.STRING, property(descriptor.properties(), "directory").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor.properties(), "compress").shape());
  }

  @Test
  void sortRowsNativeSpillDirectoryExposesLocalDirectoryPickerInsteadOfHopVfs()
      throws Exception {
    // Pinned SortRowsMeta.check() resolves this value and validates it using
    // java.io.File.isDirectory(), rather than HopVfs. A remote VFS URL is invalid.
    var field = SortRowsMeta.class.getDeclaredField("directory");
    var annotation = field.getAnnotation(HopMetadataProperty.class);
    assertEquals("directory", annotation.key());
    assertFalse(annotation.hopMetadataPropertyType() == HopMetadataPropertyType.FILE_PATH
        || annotation.hopMetadataPropertyType() == HopMetadataPropertyType.FILE_REFERENCE);

    Descriptor descriptor = AutomaticConfigDescriptor.describe(SortRowsMeta.class);
    Property directory = property(descriptor.properties(), "directory");
    assertEquals(Shape.STRING, directory.shape());
    assertEquals(WidgetHint.LOCAL_DIRECTORY_PICKER, directory.widgetHint());
    assertNull(directory.metadataClassName());
    assertEquals(WidgetHint.NONE,
        property(descriptor.properties(), "sort_prefix").widgetHint());
    assertEquals("LOCAL_DIRECTORY_PICKER",
        new com.fasterxml.jackson.databind.ObjectMapper()
            .valueToTree(directory).path("widgetHint").asText());
  }

  @Test
  void publishesCanonicalIndexedWireListsForNativeSelectValues() {
    Descriptor selectValues = AutomaticConfigDescriptor.describe(SelectValuesMeta.class);
    assertEquals(List.of("fields.field", "fields.remove", "fields.meta"),
        selectValues.wireLists().stream().map(
            AutomaticConfigDescriptor.WireListContract::descriptorPath).toList());
    assertEquals(List.of("/fields/field", "/fields/remove", "/fields/meta"),
        selectValues.wireLists().stream().map(
            AutomaticConfigDescriptor.WireListContract::listPointer).toList());
    assertEquals("/fields/field/0", selectValues.wireLists().get(0).itemPointer(0));
    assertEquals("/fields/meta/2", selectValues.wireLists().get(2).itemPointer(2));
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> selectValues.wireLists().get(0).itemPointer(-1));
    assertEquals(Shape.LIST,
        property(property(selectValues.properties(), "fields").children(), "meta").shape());

    var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(selectValues);
    assertEquals("/fields/remove", json.at("/wireLists/1/listPointer").asText());
    assertEquals("/fields/remove/{index}",
        json.at("/wireLists/1/itemPointerTemplate").asText());
    assertEquals("/fields/field",
        AutomaticConfigDescriptor.describe(SortRowsMeta.class).wireLists().get(0).listPointer());
  }

  @Test
  void publishesNativeSortRowsIndexedWirePointerWithOrderedTypedItems() throws Exception {
    Descriptor descriptor = AutomaticConfigDescriptor.describe(SortRowsMeta.class);
    assertEquals(1, descriptor.wireLists().size());
    var contract = descriptor.wireLists().get(0);
    assertEquals("field", contract.descriptorPath());
    assertEquals("/fields/field", contract.listPointer());
    assertEquals("/fields/field/{index}", contract.itemPointerTemplate());
    assertEquals(Shape.LIST, property(descriptor.properties(), contract.descriptorPath()).shape());

    SortRowsMeta meta = new SortRowsMeta();
    meta.setDefault();
    meta.setSortFields(List.of(
        new org.apache.hop.pipeline.transforms.sort.SortRowsField(
            "站点<&雪>", false, true, true, 2, false),
        new org.apache.hop.pipeline.transforms.sort.SortRowsField(
            "流量", true, false, false, 3, true)));
    var wire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        meta, new org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider());
    assertTrue(wire.at(contract.listPointer()).isArray());
    assertEquals(2, wire.at(contract.listPointer()).size());
    assertEquals("站点<&雪>", wire.at(contract.itemPointer(0) + "/name").asText());
    assertEquals("流量", wire.at(contract.itemPointer(1) + "/name").asText());
    assertTrue(wire.at(contract.itemPointer(0) + "/ascending").isBoolean());
    assertFalse(wire.at(contract.itemPointer(0) + "/ascending").booleanValue());
    assertTrue(wire.at(contract.itemPointer(0) + "/collator_strength").isNumber());
    assertEquals(2, wire.at(contract.itemPointer(0) + "/collator_strength").intValue());
    assertEquals("/fields/field",
        new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(descriptor)
            .at("/wireLists/0/listPointer").asText());
  }

  @Test
  void tableInputSpecifiedOutputFieldsPublishNativeCanonicalIndexedWireList()
      throws Exception {
    Descriptor descriptor = AutomaticConfigDescriptor.describe(TableInputMeta.class);
    assertEquals(1, descriptor.wireLists().size());
    var contract = descriptor.wireLists().get(0);
    assertEquals("field", contract.descriptorPath());
    assertEquals("/fields/field", contract.listPointer());
    assertEquals("/fields/field/{index}", contract.itemPointerTemplate());
    assertEquals("/fields/field/1", contract.itemPointer(1));

    Property outputFields = property(descriptor.properties(), "field");
    assertEquals(Shape.LIST, outputFields.shape());
    assertEquals("fields", outputFields.groupKey());
    assertEquals(WidgetHint.NONE,
        property(outputFields.elementProperties(), "name").widgetHint());
    assertEquals(Shape.NUMBER,
        property(outputFields.elementProperties(), "length").shape());
    assertEquals(Shape.NUMBER,
        property(outputFields.elementProperties(), "precision").shape());

    var first = new org.apache.hop.pipeline.transforms.tableinput.TableInputField();
    first.setName("水位<&雪>");
    first.setType(org.apache.hop.core.row.IValueMeta.TYPE_NUMBER);
    first.setLength(18);
    first.setPrecision(3);
    var second = new org.apache.hop.pipeline.transforms.tableinput.TableInputField();
    second.setName("站点");
    second.setType(org.apache.hop.core.row.IValueMeta.TYPE_STRING);
    second.setLength(64);

    TableInputMeta meta = new TableInputMeta();
    meta.setDefault();
    meta.setSpecifyFields(true);
    meta.setValidateSpecifiedFields(false);
    meta.setFields(List.of(first, second));
    var wire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        meta, new org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider());
    assertTrue(wire.at(contract.listPointer()).isArray());
    assertEquals(2, wire.at(contract.listPointer()).size());
    assertEquals("水位<&雪>", wire.at(contract.itemPointer(0) + "/name").asText());
    assertEquals("站点", wire.at(contract.itemPointer(1) + "/name").asText());
    assertTrue(wire.at(contract.itemPointer(0) + "/length").isNumber());
    assertEquals(18, wire.at(contract.itemPointer(0) + "/length").intValue());
    assertTrue(wire.at(contract.itemPointer(0) + "/precision").isNumber());
    assertEquals(3, wire.at(contract.itemPointer(0) + "/precision").intValue());
    assertTrue(wire.at("/specify_fields").isBoolean());
    assertTrue(wire.at("/specify_fields").booleanValue());
    assertTrue(wire.at("/validate_specified_fields").isBoolean());
    assertFalse(wire.at("/validate_specified_fields").booleanValue());

    var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(descriptor);
    assertEquals("/fields/field", json.at("/wireLists/0/listPointer").asText());
    assertEquals("/fields/field/{index}",
        json.at("/wireLists/0/itemPointerTemplate").asText());
  }

  @Test
  void tableOutputStreamToDatabaseMappingPublishesNativeIndexedWireList()
      throws Exception {
    Descriptor descriptor = AutomaticConfigDescriptor.describe(TableOutputMeta.class);
    assertEquals(1, descriptor.wireLists().size());
    var contract = descriptor.wireLists().get(0);
    assertEquals("field", contract.descriptorPath());
    assertEquals("/fields/field", contract.listPointer());
    assertEquals("/fields/field/{index}", contract.itemPointerTemplate());
    assertEquals("/fields/field/1", contract.itemPointer(1));

    Property mappings = property(descriptor.properties(), "field");
    assertEquals(Shape.LIST, mappings.shape());
    assertEquals("fields", mappings.groupKey());
    assertEquals(WidgetHint.UPSTREAM_FIELD_PICKER,
        property(mappings.elementProperties(), "stream_name").widgetHint());
    assertEquals(WidgetHint.NONE,
        property(mappings.elementProperties(), "column_name").widgetHint());

    var meta = new org.apache.hop.pipeline.transforms.tableoutput.TableOutputMeta();
    meta.setDefault();
    meta.setSpecifyFields(true);
    meta.setFields(List.of(
        new org.apache.hop.pipeline.transforms.tableoutput.TableOutputField(
            "db_水位<&雪>", "stream_水位<&雪>"),
        new org.apache.hop.pipeline.transforms.tableoutput.TableOutputField(
            "db_流量", "stream_流量")));
    var wire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        meta, new org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider());
    assertTrue(wire.at(contract.listPointer()).isArray());
    assertEquals(2, wire.at(contract.listPointer()).size());
    assertEquals("stream_水位<&雪>",
        wire.at(contract.itemPointer(0) + "/stream_name").asText());
    assertEquals("db_水位<&雪>",
        wire.at(contract.itemPointer(0) + "/column_name").asText());
    assertEquals("stream_流量",
        wire.at(contract.itemPointer(1) + "/stream_name").asText());
    assertEquals("db_流量",
        wire.at(contract.itemPointer(1) + "/column_name").asText());
    assertTrue(wire.at("/specify_fields").isBoolean());
    assertTrue(wire.at("/specify_fields").booleanValue());

    var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(descriptor);
    assertEquals("/fields/field", json.at("/wireLists/0/listPointer").asText());
    assertEquals("/fields/field/{index}",
        json.at("/wireLists/0/itemPointerTemplate").asText());
  }

  @Test
  void discoversTableInputTextBooleansAndTableShape() {
    Descriptor descriptor = AutomaticConfigDescriptor.describe(TableInputMeta.class);

    assertEquals(Shape.STRING, property(descriptor.properties(), "sql").shape());
    assertEquals(TextEditorHint.SQL, property(descriptor.properties(), "sql").textEditorHint());
    assertEquals(Shape.BOOLEAN, property(descriptor.properties(), "execute_each_row").shape());
    Property fields = property(descriptor.properties(), "field");
    assertEquals(Shape.LIST, fields.shape());
    assertEquals("fields", fields.groupKey());
    assertEquals(Presentation.TABLE, fields.layout().presentation());
    assertFalse(fields.elementProperties().isEmpty());
  }

  @Test
  void exposesProviderAwareMetadataAndVfsEditorHintsWithoutGuessingUnannotatedFields() {
    Descriptor table = AutomaticConfigDescriptor.describe(TableInputMeta.class);
    Property connection = property(table.properties(), "connection");
    assertEquals(Shape.STRING, connection.shape());
    assertEquals(WidgetHint.METADATA_PICKER, connection.widgetHint());
    assertEquals("org.apache.hop.core.database.DatabaseMeta", connection.metadataClassName());

    Property sqlFile = property(table.properties(), "sql_from_file");
    assertEquals(WidgetHint.VFS_PICKER, sqlFile.widgetHint());
    assertNull(sqlFile.metadataClassName());
    assertEquals(WidgetHint.NONE, property(table.properties(), "sql").widgetHint());
    assertEquals(TextEditorHint.SQL, property(table.properties(), "sql").textEditorHint());

    Descriptor rest = AutomaticConfigDescriptor.describe(RestMeta.class);
    Property restConnection = property(rest.properties(), "connection_name");
    assertEquals(WidgetHint.METADATA_PICKER, restConnection.widgetHint());
    assertEquals("org.apache.hop.metadata.rest.RestConnection", restConnection.metadataClassName());
    assertEquals(WidgetHint.NONE, property(rest.properties(), "url").widgetHint());
    assertEquals(WidgetHint.NONE, property(rest.properties(), "httpPassword").widgetHint());

    Descriptor fixture = AutomaticConfigDescriptor.describe(WidgetHintFixture.class);
    assertEquals(WidgetHint.VFS_PICKER, property(fixture.properties(), "path").widgetHint());
    assertEquals(WidgetHint.VFS_PICKER, property(fixture.properties(), "reference").widgetHint());
    assertEquals(WidgetHint.NONE, property(fixture.properties(), "connection").widgetHint());
    assertEquals(WidgetHint.NONE, property(fixture.properties(), "numericConnection").widgetHint());
    assertNull(property(fixture.properties(), "numericConnection").metadataClassName());
    assertEquals(WidgetHint.UPSTREAM_FIELD_PICKER,
        property(fixture.properties(), "incomingField").widgetHint());
    assertEquals(WidgetHint.NONE,
        property(fixture.properties(), "targetColumn").widgetHint());
  }

  @Test
  void distinguishesRestNativeLocalTrustStoreFromVfsAndUnrelatedStringFields() {
    // Pinned Rest.java opens trustStoreFile with java.io.FileInputStream,
    // so the descriptor must not offer remote HopVfs locations.
    Descriptor rest = AutomaticConfigDescriptor.describe(RestMeta.class);
    Property trustStore = property(rest.properties(), "trustStoreFile");
    assertEquals(Shape.STRING, trustStore.shape());
    assertEquals(WidgetHint.LOCAL_FILE_PICKER, trustStore.widgetHint());
    assertNull(trustStore.metadataClassName());

    assertEquals(WidgetHint.NONE, property(rest.properties(), "trustStorePassword").widgetHint());
    assertEquals(WidgetHint.NONE, property(rest.properties(), "url").widgetHint());
    assertEquals(WidgetHint.METADATA_PICKER,
        property(rest.properties(), "connection_name").widgetHint());

    // TableInput's existing file reference retains its VFS semantics.
    Descriptor table = AutomaticConfigDescriptor.describe(TableInputMeta.class);
    assertEquals(WidgetHint.VFS_PICKER,
        property(table.properties(), "sql_from_file").widgetHint());
  }

  private static final class WidgetHintFixture {
    @HopMetadataProperty(hopMetadataPropertyType = HopMetadataPropertyType.FILE_PATH)
    private String path;
    @HopMetadataProperty(hopMetadataPropertyType = HopMetadataPropertyType.FILE_REFERENCE)
    private String reference;
    @HopMetadataProperty private String connection;
    @HopMetadataProperty(hopMetadataPropertyType = HopMetadataPropertyType.STREAM_FIELD)
    private String incomingField;
    @HopMetadataProperty(hopMetadataPropertyType = HopMetadataPropertyType.RDBMS_COLUMN)
    private String targetColumn;
    @HopMetadataProperty(hopMetadataPropertyType = HopMetadataPropertyType.RDBMS_CONNECTION)
    private int numericConnection;
  }

  @Test
  void emitsPluginAgnosticTextEditorHintsFromSemanticKeys() {
    Descriptor descriptor = AutomaticConfigDescriptor.describe(TextHintFixture.class);

    assertEquals(TextEditorHint.SQL, property(descriptor.properties(), "querySql").textEditorHint());
    assertEquals(TextEditorHint.SCRIPT, property(descriptor.properties(), "script").textEditorHint());
    assertEquals(TextEditorHint.TEMPLATE, property(descriptor.properties(), "bodyTemplate").textEditorHint());
    assertEquals(TextEditorHint.NONE, property(descriptor.properties(), "errorDescription").textEditorHint());
  }

  private static final class TextHintFixture {
    @HopMetadataProperty private String querySql;
    @HopMetadataProperty private String script;
    @HopMetadataProperty private String bodyTemplate;
    @HopMetadataProperty private String errorDescription;
  }

  @Test
  void usesHopSqlSemanticAnnotationWhenFieldNameIsNotSql() {
    Descriptor descriptor = AutomaticConfigDescriptor.describe(SqlSemanticFixture.class);
    assertEquals(TextEditorHint.SQL, property(descriptor.properties(), "statement").textEditorHint());
    assertEquals(TextEditorHint.SQL, property(descriptor.properties(), "selectStatement").textEditorHint());
    assertEquals(TextEditorHint.NONE, property(descriptor.properties(), "statementPlain").textEditorHint());
    assertEquals(TextEditorHint.NONE, property(descriptor.properties(), "numericStatement").textEditorHint());
  }

  private static final class SqlSemanticFixture {
    @HopMetadataProperty(hopMetadataPropertyType = HopMetadataPropertyType.RDBMS_SQL)
    private String statement;
    @HopMetadataProperty(hopMetadataPropertyType = HopMetadataPropertyType.RDBMS_SQL_SELECT)
    private String selectStatement;
    @HopMetadataProperty private String statementPlain;
    @HopMetadataProperty(hopMetadataPropertyType = HopMetadataPropertyType.RDBMS_SQL)
    private int numericStatement;
  }

  @Test
  void exposesEnumOptionsFromMetadataWithoutPluginDescriptor() {
    Property mode = property(AutomaticConfigDescriptor.describe(EnumFixture.class).properties(), "mode");

    assertEquals(Shape.ENUM, mode.shape());
    assertEquals(List.of("ALPHA", "BETA"), mode.options().stream().map(o -> o.label()).toList());
    assertEquals(List.of("a", "b"), mode.options().stream().map(o -> o.value()).toList());
    assertEquals(Presentation.FIELD, mode.layout().presentation());
  }

  private static final class EnumFixture {
    @HopMetadataProperty(storeWithCode = true)
    private Mode mode;
  }

  private enum Mode implements IEnumHasCodeAndDescription {
    ALPHA("a"), BETA("b");

    private final String code;

    Mode(String code) {
      this.code = code;
    }

    @Override
    public String getCode() {
      return code;
    }

    @Override
    public String getDescription() {
      return name();
    }
  }

  private static Property property(List<Property> properties, String key) {
    return properties.stream().filter(p -> p.key().equals(key)).findFirst()
        .orElseThrow(() -> new AssertionError("Missing automatic property: " + key));
  }
}
