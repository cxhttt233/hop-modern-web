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
          List.of(
              new RuntimePrecondition(
                  "DATA_GRID_UNKNOWN_FIELD_TYPES_FALLBACK_TO_STRING",
                  List.of("/fields/field"),
                  "For each non-empty fields.field[*].name, DataGridMeta.getFields() resolves the declared fields.field[*].type through ValueMetaFactory. An unknown or null type resolves to TYPE_NONE and silently falls back to STRING; entries with an empty name are omitted from output metadata. Diagnose an unrecognized declared type instead of assuming that Hop will honor it. This is not a save-time validator."),
              new RuntimePrecondition(
                  "DATA_GRID_DECLARED_FIELD_FORMATS_PROPAGATE_TO_OUTPUT_METADATA",
                  List.of("/fields/field"),
                  "DataGridMeta.getFields() appends non-empty fields.field[*].name entries in declaration order, using their configured name literally (without variable resolution). For each output field it propagates length, precision, format as conversion mask, currency, group as grouping symbol, and decimal as decimal symbol; the origin is the DataGrid transform name. Downstream schema consumers must use these declared field attributes rather than infer formatting or precision from data rows. This is an output-metadata compatibility diagnostic, not a save-time validator."));
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
                  "When IsInFields is true, an incoming hop and non-empty valueField are required. Resolve the source field against upstream row metadata at runtime; native check only checks the name is non-empty. When IsInFields is false, source files must resolve through the file input configuration."),
              new RuntimePrecondition(
                  "JSON_INPUT_SOURCE_REMOVAL_AND_AUXILIARY_OUTPUT_COLUMNS",
                  List.of("/IsInFields", "/removeSourceField", "/valueField", "/fields/field", "/include", "/include_field", "/rownum", "/rownum_field"),
                  "JsonInputMeta.getFields() removes the incoming valueField column only when IsInFields and removeSourceField are true and that field exists. It then appends fields.field[*] output columns in declaration order using JsonInputField.toValueMeta(), which resolves output names and propagates declared type, length, precision and formatting. If include is true it appends a variable-resolved STRING include_field of length 250; if rownum is true it appends a variable-resolved INTEGER rownum_field. Auxiliary fields follow the JSONPath fields. This describes the native downstream row schema and does not validate or reject draft saves."));
      case "org.apache.hop.pipeline.transforms.javascript.ScriptValuesMeta" ->
          List.of(new RuntimePrecondition(
              "SCRIPT_VALUE_MOD_REPLACE_TARGET_FALLS_BACK_TO_RENAME",
              List.of("/fields/field"),
              "ScriptValueMod fields.field[*] describes a JavaScript result column. When replace is false, ScriptValuesMeta.getFields() appends a new column named rename when non-empty, otherwise name, using the configured type, length and precision. When replace is true, it first searches the incoming row for name; only when name is absent does it try rename. It changes the matched column's type, length and precision in place without renaming it or appending a column. If neither name nor rename matches an incoming field, getFields() throws HopTransformException. Diagnose output row schema using the ordered field list and this name-first fallback, not the display alias alone; do not reject draft saves."));
      case "org.apache.hop.pipeline.transforms.sql.ExecSqlMeta" ->
          List.of(new RuntimePrecondition(
              "EXEC_SQL_INPUT_MODE_REQUIRES_MATCHING_HOP_TOPOLOGY",
              List.of("/execute_each_row"),
              "When execute_each_row is true, ExecSql consumes the main input and its native check requires at least one incoming hop. When false, it can start without input and the native check reports an ERROR if an incoming hop is connected, because SQL is executed only once. Diagnose input topology independently from SQL text and database connection availability; this is not a save-time validator."),
              new RuntimePrecondition(
                  "EXEC_SQL_RESULT_COUNTER_FIELDS_APPEND_FIXED_ORDER_INTEGERS",
                  List.of("/update_field", "/insert_field", "/delete_field", "/read_field"),
                  "ExecSqlMeta.getFields() appends one INTEGER output column for each non-empty configured update_field, insert_field, delete_field, and read_field, always in that order (update, insert, delete, read), regardless of the annotation declaration order. ExecSql.getResultRow() uses the configured counter names literally, without resolving Hop variables; the columns have default integer length. In execute_each_row mode these counters follow the input row, while once-only execution emits only counter fields. Derive downstream output metadata from the four names and execution mode; this is a diagnostic, not a save-time validator."));
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
                  "GroupByMeta.getFields() appends an INTEGER group line-number output field only when all_rows, add_linenr, and a non-empty linenr_fieldname are all present. With all_rows=false or add_linenr=false, the line-number field is absent even if its name is configured; with all_rows=true and an empty name, no field is appended. Diagnose downstream row metadata from all three values without rejecting draft saves."),
              new RuntimePrecondition(
                  "GROUPBY_AGGREGATE_OUTPUT_TYPES_FOLLOW_OPERATION_AND_SUBJECT",
                  List.of("/fields/field", "/group/field", "/all_rows"),
                  "GroupByMeta.getFields() retains only group.field keys when all_rows=false, otherwise passes all incoming fields, then appends fields.field aggregations in configured order. COUNT_ANY emits INTEGER without a subject, COUNT_ALL and COUNT_DISTINCT emit INTEGER for existing subjects; SUM on a nonnumeric subject becomes NUMBER, CUM_AVG on an INTEGER subject becomes NUMBER, statistical operations emit NUMBER, CONCAT operations emit STRING, and FIRST/LAST/MIN/MAX inherit the subject type. Except for COUNT_ANY, aggregates whose subject is missing from the incoming row are omitted from output metadata. Derived columns preserve the subject conversion mask. This is a downstream row-schema diagnostic, not a save-time validator."));
      case "org.apache.hop.pipeline.transforms.mergejoin.MergeJoinMeta" ->
          List.of(
              new RuntimePrecondition(
                  "BOTH_INPUTS_ASCENDING_BY_JOIN_KEYS",
                  List.of("/keys_1/key", "/keys_2/key"),
                  "Both input streams must be sorted ascending by their respective join keys."),
              new RuntimePrecondition(
                  "MERGE_JOIN_NATIVE_CHECK_DOES_NOT_VALIDATE_JOIN_TYPE_OR_KEY_ARITY",
                  List.of("/join_type", "/keys_1/key", "/keys_2/key", "/transform1", "/transform2"),
                  "Native MergeJoinMeta.check() always returns a not-verified WARNING, even for an unsupported join_type, unequal keys_1.key and keys_2.key counts, or missing stream references. The runtime accepts only INNER, LEFT OUTER, RIGHT OUTER, and FULL OUTER; it needs two resolved info streams and equal-length ordered key lists whose fields exist in the respective input rows. Diagnose these conditions before execution without treating the native warning as a successful validation or blocking draft saves."),
              new RuntimePrecondition(
                  "MERGE_JOIN_INFO_ROWS_APPEND_FIELDS_AND_RENAME_DUPLICATE_NAMES",
                  List.of("/transform1", "/transform2"),
                  "MergeJoinMeta.getFields() merges resolved info-row metadata in info[] order into the output row. Repeated names are disambiguated using the first available _1, _2, etc. suffix, including collisions with already suffixed fields. A renamed field retains its source type, gains the MergeJoin transform as its origin, and does not rename the source info-row field. Null info-row entries are skipped. Derive downstream output field order and names from both resolved streams; this is a diagnostic, not a save-time validator."));
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
                  "For each fields.field[*], a non-empty out_stream_name is resolved through Hop variables and appended as a new STRING output field while the original in_stream_name column remains. A blank out_stream_name changes the original field in place without appending a column. When an alias collides with an existing output field name, Hop RowMeta automatically disambiguates the appended field (for example, name becomes name_1); do not assume the requested alias is the final downstream name. Diagnose the resulting row schema before execution, without blocking draft saves."),
              new RuntimePrecondition(
                  "REPLACE_STRING_OUTPUT_ENCODING_FOLLOWS_SOURCE_AND_INPLACE_NORMALIZES_STORAGE",
                  List.of("/fields/field"),
                  "ReplaceStringMeta.getFields() copies the source field's string encoding onto a newly appended STRING field when fields.field[*].out_stream_name is non-empty and the source field exists. With an empty out_stream_name, it does not append a field; it normalizes the matched input field's storage type to STORAGE_TYPE_NORMAL, leaving unrelated fields unchanged. A missing source is silently skipped in this metadata projection for in-place rules, although native check reports an error; do not treat getFields() success as validation. This is a downstream field-metadata compatibility diagnostic, not a save-time validator."));
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
          List.of(
              new RuntimePrecondition(
                  "SORT_ROWS_KEYS_AND_TEMP_DIRECTORY_NATIVE_CHECK",
                  List.of("/fields/field", "/directory"),
                  "Native SortRowsMeta.check() requires a non-empty fields.field list, with each fields.field[*].name present in incoming row metadata. The resolved directory must exist and be a directory for temporary sort files; missing upstream fields or input hops are separate native errors. Diagnose against upstream metadata and resolved variables, not just the JSON field types."),
              new RuntimePrecondition(
                  "SORT_ROWS_GETFIELDS_PROPAGATES_SORT_FLAGS_AND_NORMALIZES_STORAGE",
                  List.of("/fields/field"),
                  "SortRowsMeta.getFields() preserves incoming field names, types and positions but annotates each resolved fields.field[*].name with sortedDescending = !ascending, caseInsensitive = !case_sensitive, collatorDisabled = !collator_enabled and collatorStrength from collator_strength. Selected keys are also normalized to STORAGE_TYPE_NORMAL with null storage metadata; unselected or unknown fields are left untouched. The presorted flag does not change this getFields metadata propagation. This is a downstream row-metadata compatibility diagnostic, not a save-time validator."));
      case "org.apache.hop.pipeline.transforms.streamlookup.StreamLookupMeta" ->
          List.of(
              new RuntimePrecondition(
                  "STREAM_LOOKUP_TWO_INPUTS_AND_STAGE_SPECIFIC_FIELDS",
                  List.of("/from", "/lookup/key", "/lookup/value"),
                  "StreamLookup needs a selected lookup-source transform and at least two incoming hops: the main stream and the lookup/info stream. Each lookup.key[*].name must resolve in the main incoming row, whereas lookup.key[*].field and lookup.value[*].name must resolve in lookup/info row metadata. Native StreamLookupMeta.check() checks these separately, including missing source and missing input hops. A return value's rename is an output alias, not a lookup input field. Diagnose graph and row-metadata prerequisites without rejecting draft saves."),
              new RuntimePrecondition(
                  "STREAM_LOOKUP_RETURN_TYPES_FOLLOW_INFO_ROW_OR_DEFAULT_TYPE",
                  List.of("/lookup/value"),
                  "StreamLookupMeta.getFields() appends lookup.value[*] in configured order. With exactly one non-null lookup/info row metadata entry, each lookup.value[*].name must exist in that info row; the output uses its actual source type, not lookup.value[*].type, and is renamed to lookup.value[*].rename. Native getFields() renames the found info-row IValueMeta in place, so callers deriving schemas must isolate shared row metadata. With no usable single info-row metadata entry, getFields() instead creates output fields using each configured default type. Missing source fields with a usable info row throw HopTransformException. Diagnose the output type from info-row availability, without rejecting draft saves."));
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
                  "When use_named_parameters is true, each {fieldName} placeholder in the effective SQL (inline sql or sql_from_file) must resolve to a field in the incoming parameter row, preferring the lookup info stream when present. Named placeholders require incoming rows; Hop ${variable} references and quoted or commented braces are not named parameters. With use_named_parameters false, braces remain literal SQL. Diagnose before execution without treating an unrelated missing database connection as a parameter error."),
               new RuntimePrecondition(
                   "TABLE_INPUT_SPECIFIED_FIELDS_MAP_JDBC_COLUMNS_BY_RESOLVED_NAME",
                   List.of("/specify_fields", "/validate_specified_fields", "/fields/field"),
                   "With specify_fields true, TableInputMeta.getFields() clears the incoming row metadata and emits the non-empty fields.field entries in configured order, resolving variables in each field name and preserving the declared Hop type, length and precision. TableInputMeta.createSpecifiedMapping() then matches each declared output name to JDBC columns by name, not JDBC position. Missing JDBC columns fail regardless of validate_specified_fields; JDBC type mismatches fail only when validate_specified_fields is true (otherwise values are converted). Diagnose this mapping before execution without blocking draft saves."));
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
