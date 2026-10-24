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
      case "org.apache.hop.pipeline.transforms.concatfields.ConcatFieldsMeta" ->
          List.of(
              new RuntimePrecondition(
                  "CONCAT_FIELDS_TARGET_NAME_REQUIRED_LENGTH_WARNING",
                  List.of("/ConcatFields/targetFieldName", "/ConcatFields/targetFieldLength"),
                  "ConcatFields requires a non-empty ConcatFields.targetFieldName: native check reports ERROR and getFields fails when it is empty. A targetFieldLength of zero or less is only a native WARNING for fast data dump, not a save-time error."),
              new RuntimePrecondition(
                  "CONCAT_FIELDS_SELECTED_SOURCE_FIELDS_REQUIRE_UPSTREAM_FIELDS",
                  List.of("/fields/field"),
                  "When fields.field contains selected source names, each fields.field[*].name must exist in incoming row metadata. An empty selection means concatenate all incoming fields and is allowed; native check does not require an explicit selection."),
              new RuntimePrecondition(
                  "CONCAT_FIELDS_REMOVE_SELECTED_FIELDS_CHANGES_OUTPUT_SCHEMA",
                  List.of("/ConcatFields/removeSelectedFields", "/fields/field",
                      "/ConcatFields/targetFieldName"),
                  "When ConcatFields.removeSelectedFields is false, ConcatFieldsMeta.getFields() preserves incoming columns and appends targetFieldName as STRING. When true with selected fields, it removes each selected fields.field[*].name from the output metadata before appending the target; when true with an empty fields.field list, it clears every incoming output field before appending only the target. This is an output-row-schema compatibility diagnostic, not a save-time validator."));
      case "org.apache.hop.pipeline.transforms.checksum.CheckSumMeta" ->
          List.of(
              new RuntimePrecondition(
                  "CHECKSUM_RESULT_FIELD_REQUIRED",
                  List.of("/resultfieldName"),
                  "resultfieldName must be non-empty; the native CheckSum check reports ERROR when the result field name is missing."),
              new RuntimePrecondition(
                  "CHECKSUM_SELECTED_FIELDS_REQUIRE_UPSTREAM_FIELDS",
                  List.of("/fields/field"),
                  "Each fields.field[*].name must exist in incoming row metadata. Missing selected fields are errors; an empty selection is a warning, not an unknown-field error."),
              new RuntimePrecondition(
                  "CHECKSUM_RESULT_ROW_TYPE_DEPENDS_ON_ALGORITHM_AND_ENCODING",
                  List.of("/checksumtype", "/resultType", "/resultfieldName"),
                  "CheckSumMeta.getFields() emits INTEGER for CRC32 and ADLER32 regardless of resultType. Other algorithms emit BINARY only when resultType is BINARY, otherwise STRING (including HEXADECIMAL). Binary MD5 has length 16. An empty resultfieldName adds no output column, and non-empty names resolve variables. Diagnose downstream row metadata from both checksumtype and resultType; this is not a save-time validator."));
      case "org.apache.hop.pipeline.transforms.datagrid.DataGridMeta" ->
          List.of(new RuntimePrecondition(
              "DATA_GRID_UNKNOWN_FIELD_TYPES_FALLBACK_TO_STRING",
              List.of("/fields/field"),
              "For each non-empty fields.field[*].name, DataGridMeta.getFields() resolves the declared fields.field[*].type through ValueMetaFactory. An unknown or null type resolves to TYPE_NONE and silently falls back to STRING; entries with an empty name are omitted from output metadata. Diagnose an unrecognized declared type instead of assuming that Hop will honor it. This is not a save-time validator."));
      case "org.apache.hop.pipeline.transforms.http.HttpMeta" ->
          List.of(
              new RuntimePrecondition(
                  "HTTP_DYNAMIC_URL_NATIVE_CHECK_FALSE_POSITIVE",
                  List.of("/urlInField", "/urlField", "/url"),
                  "When urlInField is true, urlField must be non-empty and resolve to an incoming row field. Native HttpMeta.check() reports ERROR with UrlfieldOk even when the URL field name is present; this is a native-check false positive, not grounds to reject an otherwise valid dynamic URL configuration. At runtime the resolved URL field must exist in upstream row metadata. When urlInField is false, url must be non-empty."),
              new RuntimePrecondition(
                  "HTTP_RESULT_FIELD_NAMES_MIX_LITERAL_AND_RESOLVED_VARIABLES",
                  List.of("/result/name", "/result/code", "/result/response_time", "/result/response_header"),
                  "HttpMeta.getFields() appends result.name as STRING without variable resolution, while result.code and result.response_time are variable-resolved INTEGER fields and result.response_header is a variable-resolved STRING field. Empty result field names are omitted. Preserve the distinct name-resolution behavior when deriving downstream row metadata; this is a diagnostic, not a save-time validator."));
      case "org.apache.hop.pipeline.transforms.jsoninput.JsonInputMeta" ->
          List.of(
              new RuntimePrecondition(
                  "JSON_INPUT_OUTPUT_FIELDS_REQUIRED",
                  List.of("/fields/field"),
                  "JsonInput requires at least one fields.field output JsonPath definition; native check reports ERROR when the list is empty."),
              new RuntimePrecondition(
                  "JSON_INPUT_FIELD_SOURCE_REQUIRES_INCOMING_HOP_AND_NAME",
                  List.of("/IsInFields", "/valueField"),
                  "When IsInFields is true, an incoming hop and non-empty valueField are required. Resolve the source field against upstream row metadata at runtime; native check only checks the name is non-empty. When IsInFields is false, source files must resolve through the file input configuration."));
      case "org.apache.hop.pipeline.transforms.sql.ExecSqlMeta" ->
          List.of(new RuntimePrecondition(
              "EXEC_SQL_INPUT_MODE_REQUIRES_MATCHING_HOP_TOPOLOGY",
              List.of("/execute_each_row"),
              "When execute_each_row is true, ExecSql consumes the main input and its native check requires at least one incoming hop. When false, it can start without input and the native check reports an ERROR if an incoming hop is connected, because SQL is executed only once. Diagnose input topology independently from SQL text and database connection availability; this is not a save-time validator."));
      case "org.apache.hop.pipeline.transforms.filterrows.FilterRowsMeta" ->
          List.of(new RuntimePrecondition(
              "FILTER_ROWS_NESTED_CONDITION_FIELDS_REQUIRE_UPSTREAM_FIELDS",
              List.of("/compare/condition"),
              "FilterRows requires a non-empty compare.condition and incoming rows. Native FilterRowsMeta.check() recursively collects both leftvalue and rightvalue field references from nested conditions and reports missing upstream fields as ERROR. Literal right-side values are not upstream field references; true/false target transforms are separate graph references, not condition fields. The canonical pointer anchors the condition object; recursive child pointers are not exposed by the current descriptor."));
      case "org.apache.hop.pipeline.transforms.groupby.GroupByMeta" ->
          List.of(
              new RuntimePrecondition(
                  "GROUP_KEYS_CONTIGUOUS",
                  List.of("/group/field"),
                  "Rows with equal group keys must be contiguous; ascending order is not required."),
              new RuntimePrecondition(
                  "GROUPBY_GROUPING_KEYS_REQUIRE_UPSTREAM_FIELDS",
                  List.of("/group/field"),
                  "Each group.field[*].name must resolve to an incoming row field. GroupByMeta.check() only checks whether an input hop exists, not whether grouping keys resolve; with all_rows=false, getFields() silently omits missing grouping fields from output metadata, while GroupBy.processRow() resolves grouping indices against the input row. Diagnose missing keys against upstream row metadata before execution; this is not a save-time validator."),
              new RuntimePrecondition(
                  "GROUPBY_AGGREGATE_SUBJECT_REQUIRES_INPUT_FIELD",
                  List.of("/fields/field"),
                  "For every aggregation except COUNT_ANY, fields.field[*].subject must name an incoming row field; COUNT_ANY does not require a subject. Check against upstream row metadata."),
              new RuntimePrecondition(
                  "GROUPBY_LINE_NUMBER_REQUIRES_PASS_ALL_ROWS_AND_FIELD_NAME",
                  List.of("/all_rows", "/add_linenr", "/linenr_fieldname"),
                  "GroupByMeta.getFields() appends an INTEGER group line-number output field only when all_rows, add_linenr, and a non-empty linenr_fieldname are all present. With all_rows=false or add_linenr=false, the line-number field is absent even if its name is configured; with all_rows=true and an empty name, no field is appended. Diagnose downstream row metadata from all three values without rejecting draft saves."));
      case "org.apache.hop.pipeline.transforms.mergejoin.MergeJoinMeta" ->
          List.of(
              new RuntimePrecondition(
                  "BOTH_INPUTS_ASCENDING_BY_JOIN_KEYS",
                  List.of("/keys_1/key", "/keys_2/key"),
                  "Both input streams must be sorted ascending by their respective join keys."),
              new RuntimePrecondition(
                  "MERGE_JOIN_NATIVE_CHECK_DOES_NOT_VALIDATE_JOIN_TYPE_OR_KEY_ARITY",
                  List.of("/join_type", "/keys_1/key", "/keys_2/key", "/transform1", "/transform2"),
                  "Native MergeJoinMeta.check() always returns a not-verified WARNING, even for an unsupported join_type, unequal keys_1.key and keys_2.key counts, or missing stream references. The runtime accepts only INNER, LEFT OUTER, RIGHT OUTER, and FULL OUTER; it needs two resolved info streams and equal-length ordered key lists whose fields exist in the respective input rows. Diagnose these conditions before execution without treating the native warning as a successful validation or blocking draft saves."));
      case "org.apache.hop.pipeline.transforms.rest.RestMeta" ->
          List.of(
              new RuntimePrecondition(
                  "REST_DYNAMIC_METHOD_NATIVE_CHECK_FALSE_POSITIVE",
                  List.of("/dynamicMethod", "/methodFieldName", "/method"),
                  "When dynamicMethod is true, methodFieldName must be non-empty and resolve to an incoming row field. The native RestMeta.check() incorrectly reports ERROR even when the field name is present (MethodFieldOk); this is a native-check false positive, not a reason to reject an otherwise valid dynamic-method configuration. Runtime also validates each incoming HTTP method token. When dynamicMethod is false, method must be a valid non-empty HTTP method token."),
              new RuntimePrecondition(
                  "REST_BINARY_RESULT_FIELD_CHANGES_OUTPUT_ROW_TYPE",
                  List.of("/result/name", "/result/binary", "/result/code",
                      "/result/response_time", "/result/response_header"),
                  "RestMeta.getFields() emits result.name as BINARY when result.binary is true, otherwise STRING; no result body column is added when result.name is empty. Result code and response_time columns are INTEGER, and response_header is STRING. These names are output columns, not incoming fields; preserve their types when deriving downstream row metadata. This is an output-schema compatibility diagnostic, not a save-time validator."));
      case "org.apache.hop.pipeline.transforms.replacestring.ReplaceStringMeta" ->
          List.of(
              new RuntimePrecondition(
                  "REPLACE_STRING_SOURCE_FIELDS_MUST_BE_DISTINCT_STRINGS",
                  List.of("/fields/field"),
                  "Each fields.field[*].in_stream_name must be non-empty, distinct and present in incoming row metadata as STRING. The native ReplaceString check reports errors for missing, non-string or repeated source fields. out_stream_name is an optional output alias, not an upstream field."),
              new RuntimePrecondition(
                  "REPLACE_STRING_OUTPUT_ALIAS_APPENDS_VARIABLE_RESOLVED_STRING_FIELD",
                  List.of("/fields/field"),
                  "For each fields.field[*], a non-empty out_stream_name is resolved through Hop variables and appended as a new STRING output field while the original in_stream_name column remains. A blank out_stream_name changes the original field in place without appending a column. When an alias collides with an existing output field name, Hop RowMeta automatically disambiguates the appended field (for example, name becomes name_1); do not assume the requested alias is the final downstream name. Diagnose the resulting row schema before execution, without blocking draft saves."));
      case "org.apache.hop.pipeline.transforms.selectvalues.SelectValuesMeta" ->
          List.of(
              new RuntimePrecondition(
                  "SELECT_VALUES_STAGE_SPECIFIC_FIELD_RESOLUTION",
                  List.of("/fields/field", "/fields/remove", "/fields/meta"),
                  "Select & Alter field[*].name resolves against incoming row fields; Remove remove[*].name resolves against the row after selection and renaming; Metadata meta[*].name resolves after removal. A renamed field must be referenced by its new name in later stages. Diagnose each stage against its own row metadata, not only the original input."),
              new RuntimePrecondition(
                  "SELECT_VALUES_UNSPECIFIED_FIELDS_SORT_AFTER_EXPLICIT_SELECTION",
                  List.of("/fields/select_unspecified", "/fields/field"),
                  "When fields.field contains an explicit selection, SelectValuesMeta.getFields() emits selected fields in configured list order (using their output rename), then, only if fields.select_unspecified is true, appends all unselected incoming fields sorted by original field name rather than input order. When the flag is false, unselected fields are omitted. With an empty fields.field list, native selection is skipped and the original input order remains unchanged regardless of select_unspecified. Diagnose downstream column order from both settings; do not reject draft saves."));
      case "org.apache.hop.pipeline.transforms.sort.SortRowsMeta" ->
          List.of(new RuntimePrecondition(
              "SORT_ROWS_KEYS_AND_TEMP_DIRECTORY_NATIVE_CHECK",
              List.of("/fields/field", "/directory"),
              "Native SortRowsMeta.check() requires a non-empty fields.field list, with each fields.field[*].name present in incoming row metadata. The resolved directory must exist and be a directory for temporary sort files; missing upstream fields or input hops are separate native errors. Diagnose against upstream metadata and resolved variables, not just the JSON field types."));
      case "org.apache.hop.pipeline.transforms.streamlookup.StreamLookupMeta" ->
          List.of(new RuntimePrecondition(
              "STREAM_LOOKUP_TWO_INPUTS_AND_STAGE_SPECIFIC_FIELDS",
              List.of("/from", "/lookup/key", "/lookup/value"),
              "StreamLookup needs a selected lookup-source transform and at least two incoming hops: the main stream and the lookup/info stream. Each lookup.key[*].name must resolve in the main incoming row, whereas lookup.key[*].field and lookup.value[*].name must resolve in lookup/info row metadata. Native StreamLookupMeta.check() checks these separately, including missing source and missing input hops. A return value's rename is an output alias, not a lookup input field. Diagnose graph and row-metadata prerequisites without rejecting draft saves."));
      case "org.apache.hop.pipeline.transforms.stringcut.StringCutMeta" ->
          List.of(
              new RuntimePrecondition(
                  "STRING_CUT_SOURCE_FIELDS_REQUIRE_UPSTREAM_STRINGS",
                  List.of("/fields/field"),
                  "Each fields.field[*].in_stream_name must be non-empty and exist in the incoming row as STRING. The native StringCut check rejects missing or non-string source fields, but unlike ReplaceString does not reject repeated source names. out_stream_name is an optional output alias, not an upstream field."),
              new RuntimePrecondition(
                  "STRING_CUT_ALIAS_APPENDS_VARIABLE_RESOLVED_FIXED_LENGTH_STRING",
                  List.of("/fields/field"),
                  "For each fields.field[*], StringCutMeta.getFields() preserves incoming columns and appends a STRING field of length 100 only when out_stream_name is non-empty. The appended name resolves Hop variables; a blank alias updates the original field in place without appending a column. Multiple rules can reference the same input field, and their non-empty output aliases append in rule order. Diagnose downstream field names, order, and length without blocking draft saves."));
      case "org.apache.hop.pipeline.transforms.tableinput.TableInputMeta" ->
          List.of(
              new RuntimePrecondition(
                  "TABLE_INPUT_SPECIFY_FIELDS_REQUIRES_NAMED_OUTPUT_FIELD",
                  List.of("/fields/field"),
                  "When specify_fields is true, fields.field must contain at least one output field with a non-empty name; Hop skips unnamed entries. validate_specified_fields controls JDBC result column/type checks and does not waive this requirement."),
              new RuntimePrecondition(
                  "TABLE_INPUT_NAMED_SQL_PARAMETERS_REQUIRE_UPSTREAM_FIELDS",
                  List.of("/use_named_parameters", "/sql", "/sql_from_file", "/lookup"),
                  "When use_named_parameters is true, each {fieldName} placeholder in the effective SQL (inline sql or sql_from_file) must resolve to a field in the incoming parameter row, preferring the lookup info stream when present. Named placeholders require incoming rows; Hop ${variable} references and quoted or commented braces are not named parameters. With use_named_parameters false, braces remain literal SQL. Diagnose before execution without treating an unrelated missing database connection as a parameter error."));
      case "org.apache.hop.pipeline.transforms.tableoutput.TableOutputMeta" ->
          List.of(
              new RuntimePrecondition(
                  "TABLE_OUTPUT_SPECIFY_FIELDS_REQUIRES_INPUT_STREAM_FIELDS",
                  List.of("/fields/field"),
                  "When specify_fields is true, each fields.field[*].stream_name must exist in the incoming row; column_name is the database target. Check against upstream row metadata."),
              new RuntimePrecondition(
                  "TABLE_OUTPUT_RETURN_KEYS_REQUIRES_RETURN_FIELD_FOR_ROW_SCHEMA",
                  List.of("/return_keys", "/return_field"),
                  "When return_keys is true, return_field must resolve to a non-empty output field name. Native TableOutputMeta.getFields() adds an INTEGER field only when both conditions hold, but TableOutput may append the generated key to successful output rows whenever return_keys is true. An empty return_field therefore risks row-data/metadata width mismatch. The output field name is variable-resolved; diagnose this before execution without blocking draft saves."));
      case "org.apache.hop.pipeline.transforms.uniquerowsbyhashset.UniqueRowsByHashSetMeta" ->
          List.of(new RuntimePrecondition(
              "UNIQUE_ROWS_HASHSET_COMPARE_FIELDS_REQUIRE_INPUT_ROW_FIELDS",
              List.of("/fields/field"),
              "When fields.field specifies comparison columns, each fields.field[*].name must exist in the incoming row metadata. UniqueRowsByHashSetMeta.check() checks only that an input hop exists, not whether the named comparison columns resolve. On the first input row, UniqueRowsByHashSet.processRow() resolves each name with indexOfValue() and stops the pipeline with an error when a name is missing. Diagnose selected columns against the actual incoming row schema before execution; an empty comparison list has no selected-column requirement. This is not a save-time validator."));
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
