package org.apache.hop.modern.web.document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Descriptor-driven transport rules for configuration values. */
public final class SensitiveConfigTransport {
  private SensitiveConfigTransport() {}

  public static Map<String, Object> forRead(
      Map<String, Object> values, AutomaticConfigDescriptor.Descriptor descriptor) {
    return filterRead(values, descriptor.properties());
  }

  public static Map<String, Object> applyPatch(
      Map<String, Object> existing,
      Map<String, Object> patch,
      AutomaticConfigDescriptor.Descriptor descriptor) {
    Map<String, Object> merged = new LinkedHashMap<>(existing);
    for (AutomaticConfigDescriptor.Property property : descriptor.properties()) {
      if (!patch.containsKey(property.key())) {
        continue;
      }
      Object incoming = patch.get(property.key());
      if (property.sensitive() && isBlank(incoming)) {
        continue;
      }
      if (property.shape() == AutomaticConfigDescriptor.Shape.OBJECT
          && incoming instanceof Map<?, ?> incomingMap
          && existing.get(property.key()) instanceof Map<?, ?> existingMap) {
        merged.put(
            property.key(),
            mergeObject(
                cast(existingMap),
                cast(incomingMap),
                property.children()));
      } else {
        merged.put(property.key(), incoming);
      }
    }
    return Map.copyOf(merged);
  }

  private static Map<String, Object> filterRead(
      Map<String, Object> values, List<AutomaticConfigDescriptor.Property> properties) {
    Map<String, Object> filtered = new LinkedHashMap<>();
    for (AutomaticConfigDescriptor.Property property : properties) {
      if (property.sensitive() || !values.containsKey(property.key())) {
        continue;
      }
      Object value = values.get(property.key());
      if (property.shape() == AutomaticConfigDescriptor.Shape.OBJECT
          && value instanceof Map<?, ?> nested) {
        filtered.put(property.key(), filterRead(cast(nested), property.children()));
      } else {
        filtered.put(property.key(), value);
      }
    }
    return Map.copyOf(filtered);
  }

  private static Map<String, Object> mergeObject(
      Map<String, Object> existing,
      Map<String, Object> patch,
      List<AutomaticConfigDescriptor.Property> properties) {
    Map<String, Object> merged = new LinkedHashMap<>(existing);
    for (AutomaticConfigDescriptor.Property property : properties) {
      if (!patch.containsKey(property.key())) {
        continue;
      }
      Object incoming = patch.get(property.key());
      if (property.sensitive() && isBlank(incoming)) {
        continue;
      }
      merged.put(property.key(), incoming);
    }
    return Map.copyOf(merged);
  }

  private static boolean isBlank(Object value) {
    return value == null || (value instanceof String text && text.isBlank());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Map<?, ?> value) {
    return (Map<String, Object>) value;
  }
}
