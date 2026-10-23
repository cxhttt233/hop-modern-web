package org.apache.hop.modern.web.document;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.hop.metadata.api.HopMetadataProperty;
import org.apache.hop.metadata.api.IEnumHasCodeAndDescription;

/** Generic T0 descriptor discovery from Hop runtime metadata annotations. */
public final class AutomaticConfigDescriptor {
  private static final int MAX_DEPTH = 8;
  private AutomaticConfigDescriptor() {}

  public static Descriptor describe(Class<?> type) {
    return describe(type, 0, new HashSet<>());
  }

  private static Descriptor describe(Class<?> type, int depth, Set<Class<?>> ancestors) {
    if (depth > MAX_DEPTH || !ancestors.add(type)) return new Descriptor(type.getName(), List.of(), List.of());
    List<Property> properties = new ArrayList<>();
    int order = 0;
    for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        HopMetadataProperty metadata = field.getAnnotation(HopMetadataProperty.class);
        if (metadata == null || metadata.isExcludedFromSerialization()) continue;
        String key = metadata.key().isBlank() ? field.getName() : metadata.key();
        Shape shape = shape(field.getType());
        Class<?> itemType = shape == Shape.LIST ? itemType(field, metadata) : null;
        List<Property> children = shape == Shape.OBJECT
            ? describe(field.getType(), depth + 1, new HashSet<>(ancestors)).properties() : List.of();
        List<Property> itemProperties = itemType != null && shape(itemType) == Shape.OBJECT
            ? describe(itemType, depth + 1, new HashSet<>(ancestors)).properties() : List.of();
        List<Option> options = shape == Shape.ENUM ? enumOptions(field.getType(), metadata.storeWithCode()) : List.of();
        properties.add(new Property(key, field.getName(), shape, field.getType().getName(),
            metadata.groupKey(), metadata.password(), metadata.storeWithName(), metadata.storeWithCode(),
            metadata.defaultBoolean(), metadata.enumNameWhenNotFound(),
            itemType == null ? null : itemType.getName(), children, itemProperties, options,
            textEditorHint(key, shape, metadata),
            new LayoutHint(order++, metadata.groupKey(), shape == Shape.LIST ? Presentation.TABLE : Presentation.FIELD)));
      }
    }
    return new Descriptor(type.getName(), List.copyOf(properties), runtimePreconditions(type));
  }

  private static Class<?> itemType(Field field, HopMetadataProperty metadata) {
    if (field.getType().isArray()) return field.getType().getComponentType();
    if (!Collection.class.isAssignableFrom(field.getType())) return null;
    if (metadata.listItemClass() != Object.class) return metadata.listItemClass();
    Type generic = field.getGenericType();
    if (generic instanceof ParameterizedType parameterized) {
      Type item = parameterized.getActualTypeArguments()[0];
      if (item instanceof Class<?> itemClass) return itemClass;
      if (item instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> raw) return raw;
    }
    return Object.class;
  }

  private static List<Option> enumOptions(Class<?> type, boolean storeWithCode) {
    Object[] constants = type.getEnumConstants();
    if (constants == null) return List.of();
    List<Option> options = new ArrayList<>(constants.length);
    for (Object constant : constants) {
      Enum<?> value = (Enum<?>) constant;
      options.add(new Option(value.name(), storeWithCode ? enumCode(value) : value.name()));
    }
    return List.copyOf(options);
  }

  private static String enumCode(Enum<?> value) {
    if (value instanceof IEnumHasCodeAndDescription coded) {
      return coded.getCode() == null ? value.name() : coded.getCode();
    }
    try {
      Object code = value.getClass().getMethod("getCode").invoke(value);
      return code == null ? value.name() : String.valueOf(code);
    } catch (ReflectiveOperationException ignored) {
      return value.name();
    }
  }

  private static TextEditorHint textEditorHint(
      String key, Shape shape, HopMetadataProperty metadata) {
    if (shape != Shape.STRING) return TextEditorHint.NONE;
    switch (metadata.hopMetadataPropertyType()) {
      case RDBMS_SQL, RDBMS_SQL_SELECT, RDBMS_SQL_INSERT, RDBMS_SQL_UPDATE,
          RDBMS_SQL_DELETE, RDBMS_SQL_BULK -> {
        return TextEditorHint.SQL;
      }
      default -> {
        // Fall back to the semantic field name for untyped properties.
      }
    }
    String normalized = key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    String[] tokens = normalized.split("[^a-z0-9]+");
    String terminal = tokens.length == 0 ? "" : tokens[tokens.length - 1];
    return switch (terminal) {
      case "sql" -> TextEditorHint.SQL;
      case "script" -> TextEditorHint.SCRIPT;
      case "template" -> TextEditorHint.TEMPLATE;
      default -> TextEditorHint.NONE;
    };
  }

  private static Shape shape(Class<?> type) {
    if (type.isArray() || Collection.class.isAssignableFrom(type)) return Shape.LIST;
    if (type.isEnum()) return Shape.ENUM;
    if (type == boolean.class || type == Boolean.class) return Shape.BOOLEAN;
    if (Number.class.isAssignableFrom(type) || (type.isPrimitive() && type != char.class && type != boolean.class)) return Shape.NUMBER;
    if (type == String.class || type == char.class || type == Character.class) return Shape.STRING;
    return Shape.OBJECT;
  }

  /**
   * Pipeline-level input requirements that cannot be inferred from the fields' Java types.
   * They are diagnostic contracts, not save-time validators: an upstream source may already
   * satisfy the requirement without an explicit SortRows transform.
   */
  private static List<RuntimePrecondition> runtimePreconditions(Class<?> type) {
    return switch (type.getName()) {
      case "org.apache.hop.pipeline.transforms.checksum.CheckSumMeta" ->
          List.of(
              new RuntimePrecondition(
                  "CHECKSUM_RESULT_FIELD_REQUIRED",
                  List.of("/resultfieldName"),
                  "resultfieldName must be non-empty; the native CheckSum check reports ERROR when the result field name is missing."),
              new RuntimePrecondition(
                  "CHECKSUM_SELECTED_FIELDS_REQUIRE_UPSTREAM_FIELDS",
                  List.of("/fields/field"),
                  "Each fields.field[*].name must exist in incoming row metadata. Missing selected fields are errors; an empty selection is a warning, not an unknown-field error."));
      case "org.apache.hop.pipeline.transforms.groupby.GroupByMeta" ->
          List.of(
              new RuntimePrecondition(
                  "GROUP_KEYS_CONTIGUOUS",
                  List.of("/group/field"),
                  "Rows with equal group keys must be contiguous; ascending order is not required."),
              new RuntimePrecondition(
                  "GROUPBY_AGGREGATE_SUBJECT_REQUIRES_INPUT_FIELD",
                  List.of("/fields/field"),
                  "For every aggregation except COUNT_ANY, fields.field[*].subject must name an incoming row field; COUNT_ANY does not require a subject. Check against upstream row metadata."));
      case "org.apache.hop.pipeline.transforms.mergejoin.MergeJoinMeta" ->
          List.of(new RuntimePrecondition(
              "BOTH_INPUTS_ASCENDING_BY_JOIN_KEYS",
              List.of("/keys_1/key", "/keys_2/key"),
              "Both input streams must be sorted ascending by their respective join keys."));
      case "org.apache.hop.pipeline.transforms.selectvalues.SelectValuesMeta" ->
          List.of(new RuntimePrecondition(
              "SELECT_VALUES_STAGE_SPECIFIC_FIELD_RESOLUTION",
              List.of("/fields/field", "/fields/remove", "/fields/meta"),
              "Select & Alter field[*].name resolves against incoming row fields; Remove remove[*].name resolves against the row after selection and renaming; Metadata meta[*].name resolves after removal. A renamed field must be referenced by its new name in later stages. Diagnose each stage against its own row metadata, not only the original input."));
      case "org.apache.hop.pipeline.transforms.tableinput.TableInputMeta" ->
          List.of(new RuntimePrecondition(
              "TABLE_INPUT_SPECIFY_FIELDS_REQUIRES_NAMED_OUTPUT_FIELD",
              List.of("/fields/field"),
              "When specify_fields is true, fields.field must contain at least one output field with a non-empty name; Hop skips unnamed entries. validate_specified_fields controls JDBC result column/type checks and does not waive this requirement."));
      case "org.apache.hop.pipeline.transforms.tableoutput.TableOutputMeta" ->
          List.of(new RuntimePrecondition(
              "TABLE_OUTPUT_SPECIFY_FIELDS_REQUIRES_INPUT_STREAM_FIELDS",
              List.of("/fields/field"),
              "When specify_fields is true, each fields.field[*].stream_name must exist in the incoming row; column_name is the database target. Check against upstream row metadata."));
      default -> List.of();
    };
  }

  public enum Shape { STRING, NUMBER, BOOLEAN, ENUM, OBJECT, LIST }
  public enum TextEditorHint { NONE, SQL, SCRIPT, TEMPLATE }
  public enum Presentation { FIELD, TABLE }
  public record Descriptor(
      String className, List<Property> properties, List<RuntimePrecondition> runtimePreconditions) {}
  public record RuntimePrecondition(String code, List<String> keyListPointers, String explanation) {}
  public record Option(String label, String value) {}
  public record LayoutHint(int order, String group, Presentation presentation) {}
  public record Property(String key, String javaField, Shape shape, String javaType, String groupKey,
      boolean sensitive, boolean storeWithName, boolean storeWithCode, boolean defaultBoolean,
      String enumNameWhenNotFound, String elementJavaType, List<Property> children,
      List<Property> elementProperties, List<Option> options, TextEditorHint textEditorHint,
      LayoutHint layout) {}
}
