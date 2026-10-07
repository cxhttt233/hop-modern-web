package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Descriptor;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Property;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Presentation;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Shape;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.TextEditorHint;
import org.apache.hop.metadata.api.HopMetadataProperty;
import org.apache.hop.metadata.api.IEnumHasCodeAndDescription;
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
