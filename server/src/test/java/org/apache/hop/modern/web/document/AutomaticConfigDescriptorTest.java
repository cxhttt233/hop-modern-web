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
