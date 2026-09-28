package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Descriptor;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Property;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Presentation;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Shape;
import org.apache.hop.pipeline.transforms.selectvalues.SelectValuesMeta;
import org.apache.hop.pipeline.transforms.sort.SortRowsMeta;
import org.apache.hop.pipeline.transforms.tableinput.TableInputMeta;
import org.junit.jupiter.api.Test;

/** Representative coverage is organized by metadata shape, not plugin count. */
class RepresentativeTransformCoverageTest {
  @Test
  void existingTransformsCoverSevenRepresentativeStructuresWithoutPluginBranches() {
    Descriptor sort = AutomaticConfigDescriptor.describe(SortRowsMeta.class);
    Descriptor select = AutomaticConfigDescriptor.describe(SelectValuesMeta.class);
    Descriptor tableInput = AutomaticConfigDescriptor.describe(TableInputMeta.class);

    // simple scalar + boolean + variable list/table
    assertEquals(Shape.STRING, property(sort.properties(), "directory").shape());
    assertEquals(Shape.BOOLEAN, property(sort.properties(), "compress").shape());
    Property sortFields = property(sort.properties(), "field");
    assertEquals(Shape.LIST, sortFields.shape());
    assertEquals(Presentation.TABLE, sortFields.layout().presentation());

    // nested object + field mapping
    Property fields = property(select.properties(), "fields");
    assertEquals(Shape.OBJECT, fields.shape());
    Property selected = property(fields.children(), "field");
    assertEquals(Shape.LIST, selected.shape());
    assertTrue(selected.elementProperties().stream().anyMatch(p -> p.key().equals("name")));
    assertTrue(selected.elementProperties().stream().anyMatch(p -> p.key().equals("rename")));

    // named reference contract + multiline SQL/script contract are both generic strings today.
    Property connection = property(tableInput.properties(), "connection");
    Property sql = property(tableInput.properties(), "sql");
    assertEquals(Shape.STRING, connection.shape());
    assertEquals(Shape.STRING, sql.shape());
    assertFalse(sql.sensitive());
  }

  private static Property property(List<Property> properties, String key) {
    return properties.stream().filter(p -> p.key().equals(key)).findFirst()
        .orElseThrow(() -> new AssertionError("Missing automatic property: " + key));
  }
}
