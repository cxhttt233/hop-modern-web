package org.apache.hop.modern.web.document;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.apache.hop.metadata.api.HopMetadataProperty;

/** Generic T0 descriptor discovery from Hop runtime metadata annotations. */
public final class AutomaticConfigDescriptor {
  private AutomaticConfigDescriptor() {}

  public static Descriptor describe(Class<?> type) {
    List<Property> properties = new ArrayList<>();
    for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        HopMetadataProperty metadata = field.getAnnotation(HopMetadataProperty.class);
        if (metadata == null || metadata.isExcludedFromSerialization()) continue;
        String key = metadata.key().isBlank() ? field.getName() : metadata.key();
        properties.add(new Property(key, field.getName(), shape(field.getType()), field.getType().getName(),
            metadata.groupKey(), metadata.password(), metadata.storeWithName(), metadata.storeWithCode()));
      }
    }
    return new Descriptor(type.getName(), List.copyOf(properties));
  }

  private static Shape shape(Class<?> type) {
    if (type.isArray() || java.util.Collection.class.isAssignableFrom(type)) return Shape.LIST;
    if (type.isEnum()) return Shape.ENUM;
    if (type == boolean.class || type == Boolean.class) return Shape.BOOLEAN;
    if (Number.class.isAssignableFrom(type) || (type.isPrimitive() && type != char.class)) return Shape.NUMBER;
    if (type == String.class || type == char.class || type == Character.class) return Shape.STRING;
    return Shape.OBJECT;
  }

  public enum Shape { STRING, NUMBER, BOOLEAN, ENUM, OBJECT, LIST }
  public record Descriptor(String className, List<Property> properties) {}
  public record Property(String key, String javaField, Shape shape, String javaType, String groupKey,
      boolean sensitive, boolean storeWithName, boolean storeWithCode) {}
}
