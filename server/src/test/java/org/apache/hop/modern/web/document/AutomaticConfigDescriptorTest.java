package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Descriptor;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Property;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Shape;
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
  }

  @Test
  void discoversSortRowsListElementAndScalarMetadata() {
    Descriptor descriptor = AutomaticConfigDescriptor.describe(SortRowsMeta.class);
    Property sortFields = property(descriptor.properties(), "field");

    assertEquals(Shape.LIST, sortFields.shape());
    assertEquals("fields", sortFields.groupKey());
    assertEquals("org.apache.hop.pipeline.transforms.sort.SortRowsField", sortFields.elementJavaType());
    assertTrue(sortFields.elementProperties().stream().anyMatch(p -> p.key().equals("ascending") && p.shape() == Shape.BOOLEAN));
    assertEquals(Shape.STRING, property(descriptor.properties(), "directory").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor.properties(), "compress").shape());
  }

  @Test
  void discoversTableInputTextBooleansAndTableShape() {
    Descriptor descriptor = AutomaticConfigDescriptor.describe(TableInputMeta.class);

    assertEquals(Shape.STRING, property(descriptor.properties(), "sql").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor.properties(), "execute_each_row").shape());
    Property fields = property(descriptor.properties(), "field");
    assertEquals(Shape.LIST, fields.shape());
    assertEquals("fields", fields.groupKey());
    assertFalse(fields.elementProperties().isEmpty());
  }

  private static Property property(List<Property> properties, String key) {
    return properties.stream().filter(p -> p.key().equals(key)).findFirst()
        .orElseThrow(() -> new AssertionError("Missing automatic property: " + key));
  }
}
