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
                  "When ConcatFields.removeSelectedFields is false, ConcatFieldsMeta.getFields() preserves incoming columns and appends targetFieldName as STRING. When true with selected fields, it removes each selected fields.field[*].name from the output metadata before appending the target; when true with an empty fields.field list, it clears every incoming output field before appending only the target. This is an output-row-schema compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "CONCAT_FIELDS_LITERAL_TARGET_NAME_AUTO_SUFFIXES_COLLISIONS",
                  List.of("/ConcatFields/targetFieldName", "/ConcatFields/targetFieldLength"),
                  "Native ConcatFieldsMeta.getFields() appends a fresh STRING ValueMeta using the literal targetFieldName (without resolving Hop variables) and the configured targetFieldLength. RowMeta.addValueMeta() automatically renames the appended field to targetFieldName_1 (or the next unused numeric suffix) when the literal target already exists, preserving the original input field. Diagnose the resulting renamed output field and variable-literal naming for downstream consumers without rejecting draft saves."),
              new RuntimePrecondition(
                  "CONCAT_FIELDS_SKIP_EMPTY_BEFORE_FORCED_ENCLOSURE",
                  List.of("/fields/field", "/skip_value_empty", "/force_enclosure",
                      "/enclosure", "/separator"),
                  "With skip_value_empty=true, native ConcatFieldHelper first converts each selected field to a STRING and filters empty converted values before joining. With force_enclosure=true, each surviving value is wrapped with the configured enclosure and the remaining values are joined using separator. Empty selected values contribute neither enclosure nor an extra separator; a non-empty value containing the separator is wrapped. The helper does not escape embedded enclosure characters, so this behavior alone does not guarantee RFC-compliant CSV quoting. Preserve the selected field order and root-level BOOLEAN/STRING settings across JSON editing and HPL disk reopen; this is a runtime compatibility diagnostic, not a draft-save validator."),
              new RuntimePrecondition(
                  "CONCAT_FIELDS_ENCLOSURE_VARIABLE_STAYS_LITERAL_IN_ROW_OUTPUT",
                  List.of("/enclosure", "/separator", "/force_enclosure"),
                  "Native ConcatFields.initStringDataFields() resolves both separator and enclosure into runtime data, but ConcatFieldHelper.concat() joins with the resolved separator while wrapping each surviving value using meta.getEnclosure() (the raw configuration string). With force_enclosure=true, an enclosure expression such as ${ENC} is emitted literally even when the pipeline variable ENC resolves to a quote. Preserve the raw string across JSON editing and HPL disk reopen; diagnose this native CSV-like output hazard rather than assuming both variables are expanded. This is a runtime compatibility diagnostic, not a save-time validator."));
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
                  "CHECKSUM_RESULT_NAME_COLLISION_AND_RESOLVED_EMPTY",
                  List.of("/resultfieldName"),
                  "CheckSumMeta.getFields() tests the configured resultfieldName for emptiness before resolving Hop variables. A non-empty expression that resolves to an empty string still appends a field with an empty name; an empty configured name appends no field. The resolved name is added through RowMeta.addValueMeta(), which preserves upstream columns and suffixes collisions (including case-insensitive collisions) with the next available numeric suffix. Diagnose the actual downstream output name after variable resolution and RowMeta collision handling; do not reject draft saves."),
              new RuntimePrecondition(
                  "CHECKSUM_RESULT_ROW_TYPE_DEPENDS_ON_ALGORITHM_AND_ENCODING",
                  List.of("/checksumtype", "/resultType", "/resultfieldName", "/fields/field"),
                  "CheckSumMeta.getFields() emits INTEGER for CRC32 and ADLER32 regardless of resultType. Other algorithms emit BINARY only when resultType is binary, otherwise STRING (including hexadecimal). For SHA-256, native CheckSum writes lowercase hexadecimal digest bytes when resultType=hexadecimal, but resultType=string uses unsigned decimal byte values joined with hyphens (not Base64 or hex); resultType=binary preserves the raw digest bytes. The selected fields.field[*].name order determines the digest input, and both textual encodings produce STRING metadata. Binary MD5 has length 16. An empty resultfieldName adds no output column, and non-empty names resolve variables. Diagnose downstream row metadata from checksumtype, resultType, and selected fields; this is not a save-time validator."));
      case "org.apache.hop.pipeline.transforms.datagrid.DataGridMeta" ->
          List.of(
              new RuntimePrecondition(
                  "DATA_GRID_NONSTRING_FORCED_EMPTY_STRING_MISMATCHES_DECLARED_TYPE",
                  List.of("/fields/field", "/data/line"),
                  "For an INTEGER or NUMBER field whose canonical "
                      + "/fields/field/<index>/field/set_empty_string is true, native "
                      + "DataGrid.processRow() bypasses typed conversion and writes "
                      + "a Java empty String, even though DataGridMeta.getFields() "
                      + "still declares the output column INTEGER or NUMBER. The "
                      + "configured /data/line/<index>/item value is ignored for that "
                      + "column. This can surprise downstream consumers that assume "
                      + "the runtime Java value always matches IRowMeta's numeric "
                      + "type. Preserve the BOOLEAN flag, ordered fields and data "
                      + "rows through JSON/HPL editing; this is a native runtime "
                      + "compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "DATA_GRID_UNKNOWN_FIELD_TYPES_FALLBACK_TO_STRING",
                  List.of("/fields/field"),
                  "For each non-empty fields.field[*].name, DataGridMeta.getFields() resolves the declared fields.field[*].type through ValueMetaFactory. An unknown or null type resolves to TYPE_NONE and silently falls back to STRING; entries with an empty name are omitted from output metadata. Diagnose an unrecognized declared type instead of assuming that Hop will honor it. This is not a save-time validator."),
              new RuntimePrecondition(
                  "DATA_GRID_DECLARED_FIELD_FORMATS_PROPAGATE_TO_OUTPUT_METADATA",
                  List.of("/fields/field"),
                  "DataGridMeta.getFields() appends non-empty fields.field[*].name entries in declaration order, using their configured name literally (without variable resolution). For each output field it propagates length, precision, format as conversion mask, currency, group as grouping symbol, and decimal as decimal symbol; the origin is the DataGrid transform name. Downstream schema consumers must use these declared field attributes rather than infer formatting or precision from data rows. This is an output-metadata compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "DATA_GRID_EXPLICIT_EMPTY_STRING_OVERRIDES_CONFIGURED_CELL",
                  List.of("/fields/field", "/data/line"),
                  "For a STRING output field whose canonical "
                      + "/fields/field/<index>/field/set_empty_string is true, "
                      + "native DataGrid.processRow() emits an actual empty Java String "
                      + "for that column, ignoring the corresponding non-empty "
                      + "/data/line/<index>/item cell literal. When false, the configured "
                      + "cell string is converted using the declared field type "
                      + "(for example, Integer becomes a Java Long). Preserve "
                      + "the BOOLEAN flag and both ordered LISTs across JSON editing "
                      + "and Hop XML disk reopen; do not interpret a forced empty "
                      + "string as a null cell. This is a runtime compatibility "
                      + "diagnostic, not a save-time validator."));
      case "org.apache.hop.pipeline.transforms.http.HttpMeta" ->
          List.of(
              new RuntimePrecondition(
                  "HTTP_DYNAMIC_URL_NATIVE_CHECK_FALSE_POSITIVE",
                  List.of("/urlInField", "/urlField", "/url"),
                  "When urlInField is true, urlField must be non-empty and resolve to an incoming row field. Native HttpMeta.check() reports ERROR with UrlfieldOk even when the URL field name is present; this is a native-check false positive, not grounds to reject an otherwise valid dynamic URL configuration. At runtime Http.firstProcessRow resolves the URL field index once, and Http.constructUrlBuilder reads the URL from each incoming row, overriding the configured literal url. Different rows can therefore call different endpoints; the URL field must exist in upstream row metadata. When urlInField is false, url must be non-empty and the configured URL is used."),
              new RuntimePrecondition(
                  "HTTP_RESULT_FIELD_NAMES_MIX_LITERAL_AND_RESOLVED_VARIABLES",
                  List.of("/result/name", "/result/code", "/result/response_time", "/result/response_header"),
                  "HttpMeta.getFields() appends result.name as a literal STRING field without variable resolution, while result.code and result.response_time are variable-resolved INTEGER fields and result.response_header is a variable-resolved STRING field. The body, code and time raw names are checked for emptiness before variable resolution, so a non-empty code/time placeholder resolving to an empty string still adds an empty-named INTEGER output field. The response header is checked after variable resolution and is omitted when its resolved name is empty. A raw empty body/code/time name omits that column. Preserve the native order and distinct empty-name semantics when diagnosing downstream row metadata; this is not a save-time validator."),
              new RuntimePrecondition(
                  "HTTP_REPEATED_RESPONSE_HEADER_NAMES_SERIALIZE_AS_JSON_ARRAY",
                  List.of("/result/response_header"),
                  "Http.callHttpService() serializes response headers as JSON text into the "
                      + "configured result.response_header STRING output column. The native "
                      + "extractHeaderString() keeps a single header occurrence as a JSON "
                      + "string, but turns exact duplicate header names into an ordered JSON "
                      + "array of strings; a third occurrence appends to that array. Names "
                      + "with different casing remain distinct JSON keys. Consumers must "
                      + "not collapse repeated header values or infer that every header "
                      + "value has the same JSON shape. This is a response-payload "
                      + "compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "HTTP_401_FATAL_204_EMPTY_OTHER_ERROR_STATUS_BODY",
                  List.of("/url", "/encoding", "/result/name", "/result/code",
                      "/result/response_header"),
                  "The native Http.callHttpService() treats HTTP 401 Unauthorized as a "
                      + "HopTransformException instead of producing the configured result "
                      + "columns. HTTP 204 No Content produces an empty STRING body, while "
                      + "HTTP 404 and other non-401 error responses return their response "
                      + "body and numeric status in the configured output fields. Preserve "
                      + "the saved result.name/result.code/result.response_header wire "
                      + "configuration; do not treat every HTTP 4xx response as a returned "
                      + "row or every non-2xx response as fatal. This is a runtime "
                      + "compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "HTTP_RESPONSE_BODY_DECODING_USES_CONFIG_ENCODING_NOT_RESPONSE_CHARSET",
                  List.of("/encoding", "/result/name"),
                  "Http.readResponseBody() buffers the response bytes, then wraps them in a "
                      + "new application/octet-stream entity before decoding. This discards "
                      + "the original response Content-Type charset. HttpMeta.encoding "
                      + "(default UTF-8) selects the decoding charset when non-empty; do "
                      + "not reuse REST's response-header charset rules for Http. The "
                      + "result.name column remains STRING. This is a native response "
                      + "decoding compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "HTTP_GET_REDIRECTS_RETURN_FINAL_STATUS_BODY_AND_HEADERS",
                  List.of("/url", "/encoding", "/result/name", "/result/code",
                      "/result/response_header"),
                  "The native Http.callHttpService() uses the HttpClient 5 GET redirect "
                      + "policy: same-origin relative HTTP 302 and 307 Location redirects "
                      + "are followed, and the configured result.name, result.code and "
                      + "result.response_header columns describe the final response "
                      + "(HTTP 200 in the local fixture), not the intermediate 3xx. "
                      + "A redirect-only header is not copied into the final response "
                      + "header JSON. Preserve the editable URL and output field names "
                      + "through JSON/HPL transport; this is a runtime compatibility "
                      + "diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "HTTP_GZIP_RESPONSE_DECODES_BEFORE_CONFIGURED_TEXT_AND_BYTE_COUNT",
                  List.of("/url", "/encoding", "/result/name", "/result/code"),
                  "The pinned Http transform uses the default HttpClient 5 content-compression "
                      + "handling for GET responses. For a Content-Encoding: gzip response, "
                      + "the entity passed to Http.readResponseBody() is already decompressed. "
                      + "The configured encoding then converts those decompressed bytes into "
                      + "the result.name STRING; the status remains the final HTTP status in "
                      + "result.code. HttpData.lastHttpResponseBodyBytes counts decompressed "
                      + "entity bytes, not the compressed wire Content-Length. Preserve the "
                      + "editable URL, encoding and result field names through JSON/HPL; "
                      + "this is a runtime diagnostic, not a save-time validator."));
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
                  "JSON_INPUT_MISSING_AND_EXPLICIT_NULL_PATHS_KEEP_TYPED_ROWS",
                  List.of("/IsInFields", "/valueField", "/ignoreMissingPath",
                      "/defaultPathLeafToNull", "/removeSourceField", "/fields/field"),
                  "When JsonInput reads a JSON STRING from the upstream valueField, "
                      + "defaultPathLeafToNull=true allows an absent leaf JsonPath to "
                      + "resolve to null, and ignoreMissingPath=true permits both "
                      + "missing-path and explicit JSON null results instead of "
                      + "raising JsonInputException. Native FastJsonReader keeps "
                      + "one result row per input JSON document in this scalar-path "
                      + "case, with null in the configured STRING/INTEGER output "
                      + "column rather than a fabricated empty string or zero. "
                      + "With removeSourceField=false the original JSON payload "
                      + "column and other upstream columns remain before the "
                      + "ordered fields.field outputs. When ignoreMissingPath=false, "
                      + "an all-null path result is a native error, even for explicit "
                      + "JSON null. Preserve the BOOLEAN flags and typed ordered "
                      + "JsonPath fields across config JSON editing and real HPL "
                      + "disk reopen; this is an execution compatibility diagnostic, "
                      + "not a draft-save validator."),
              new RuntimePrecondition(
                  "JSON_INPUT_SOURCE_REMOVAL_AND_AUXILIARY_OUTPUT_COLUMNS",
                  List.of("/IsInFields", "/removeSourceField", "/valueField", "/fields/field", "/include", "/include_field", "/rownum", "/rownum_field"),
                  "JsonInputMeta.getFields() removes the incoming valueField column only when IsInFields and removeSourceField are true and that field exists. It then appends fields.field[*] output columns in declaration order using JsonInputField.toValueMeta(), which resolves output names and propagates declared type, length, precision and formatting. If include is true it appends a variable-resolved STRING include_field of length 250; if rownum is true it appends a variable-resolved INTEGER rownum_field. Auxiliary fields follow the JSONPath fields. This describes the native downstream row schema and does not validate or reject draft saves."));
      case "org.apache.hop.pipeline.transforms.javascript.ScriptValuesMeta" ->
          List.of(new RuntimePrecondition(
              "SCRIPT_VALUE_MOD_REPLACE_TARGET_FALLS_BACK_TO_RENAME",
              List.of("/fields/field"),
              "ScriptValueMod fields.field[*] describes a JavaScript result column. When replace is false, ScriptValuesMeta.getFields() appends a new column named rename when non-empty, otherwise name, using the configured type, length and precision. When replace is true, it first searches the incoming row for name; only when name is absent does it try rename. It changes the matched column's type, length and precision in place without renaming it or appending a column. If neither name nor rename matches an incoming field, getFields() throws HopTransformException. Diagnose output row schema using the ordered field list and this name-first fallback, not the display alias alone; do not reject draft saves."),
               new RuntimePrecondition(
                   "SCRIPT_VALUE_MOD_LITERAL_ALIAS_COLLISION_AND_EMPTY_NAME_SKIP",
                   List.of("/fields/field"),
                   "ScriptValuesMeta.getFields() ignores a fields.field[*] entry entirely when name is empty, even if rename is non-empty. For non-replacing entries with non-empty name, a non-empty rename is the literal output field name (without Hop variable resolution), otherwise name is used. RowMeta.addValueMeta() disambiguates aliases colliding with existing or previously appended fields using the first unused numeric suffix. The new field uses the configured type, length and precision; original input fields remain intact. Derive downstream output names and types from the ordered entries; this is not a save-time validator."));
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
              "FilterRows requires a non-empty compare.condition and incoming rows. Native FilterRowsMeta.check() recursively collects both leftvalue and rightvalue field references from nested conditions and reports missing upstream fields as ERROR. Literal right-side values are not upstream field references; true/false target transforms are separate graph references, not condition fields. The canonical pointer anchors the condition object; recursive child pointers are not exposed by the current descriptor."),
              new RuntimePrecondition(
                  "FILTER_ROWS_CONDITION_FIELDS_RESET_DESCENDING_SORT_METADATA",
                  List.of("/compare/condition"),
                  "FilterRowsMeta.getFields() obtains both left and right field operands from compare.condition through Condition.getUsedFields() and clears sortedDescending on each matching incoming IValueMeta in place. Unreferenced fields retain their descending-sort metadata, and missing condition field names are skipped by getFields() rather than validated here. This prevents sorted-descending metadata from inverting subsequent condition comparisons; derive downstream row metadata from the native projection, not the upstream sort flags. This is a compatibility diagnostic, not a save-time validator."));
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
                  "GroupByMeta.getFields() appends an INTEGER group line-number field after the aggregate columns only when all_rows=true, add_linenr=true, and the configured linenr_fieldname is non-empty. It uses the configured field name literally, without resolving Hop variables; if that name already exists in the passed-through row, RowMeta.addValueMeta() adds a numeric suffix rather than replacing the original column. The appended field has DEFAULT_INTEGER_LENGTH, precision 0, and the GroupBy transform as origin. With all_rows=false, add_linenr=false, or an empty configured name, no line-number column is appended. Diagnose the exact downstream field names and types without rejecting draft saves."),
              new RuntimePrecondition(
                  "GROUPBY_AGGREGATE_OUTPUT_TYPES_FOLLOW_OPERATION_AND_SUBJECT",
                  List.of("/fields/field", "/group/field", "/all_rows"),
                  "GroupByMeta.getFields() retains only group.field keys when all_rows=false, otherwise passes all incoming fields, then appends fields.field aggregations in configured order. COUNT_ANY emits INTEGER without a subject, COUNT_ALL and COUNT_DISTINCT emit INTEGER for existing subjects; SUM on a nonnumeric subject becomes NUMBER, CUM_AVG on an INTEGER subject becomes NUMBER, statistical operations emit NUMBER, CONCAT operations emit STRING, and FIRST/LAST/MIN/MAX inherit the subject type. Except for COUNT_ANY, aggregates whose subject is missing from the incoming row are omitted from output metadata. Derived columns preserve the subject conversion mask. This is a downstream row-schema diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "GROUPBY_EMPTY_INPUT_GIVE_BACK_ROW_EMITS_ZERO_COUNT",
                  List.of("/give_back_row", "/all_rows", "/group/field", "/fields/field"),
                  "With no incoming rows, native GroupBy.processRow() emits no row when give_back_row=false. When give_back_row=true and all_rows=false, it derives input metadata from the upstream transform and emits one aggregate row: configured grouping keys are null and COUNT_ANY is INTEGER zero. With all_rows=true, no buffered source rows exist to emit, even if give_back_row=true. Preserve the BOOLEAN flags and ordered grouped LISTs through JSON config editing and HPL disk reopen; this is a runtime diagnostic, not a draft-save validator."),
              new RuntimePrecondition(
                  "GROUPBY_DISTINCT_ALL_ANY_NULL_COUNTS_DIFFER",
                  List.of("/fields/field", "/group/field"),
                  "GroupBy COUNT_DISTINCT counts each unique non-null subject value once per contiguous group, while COUNT_ALL counts every non-null subject occurrence including duplicates and COUNT_ANY counts every row without requiring a subject. With only null subject values, native COUNT_DISTINCT leaves its INTEGER aggregate null, COUNT_ALL emits INTEGER zero, and COUNT_ANY still emits the group row count. The distinct set resets for each group. Preserve each fields.field[*].type label, subject, list order and grouping keys across JSON editing and real HPL disk reopen. This is a native execution compatibility diagnostic, not a draft-save validator."),
              new RuntimePrecondition(
                  "GROUPBY_FIRST_LAST_NULL_INCLUSION_CHANGES_VALUES",
                  List.of("/fields/field", "/group/field"),
                  "GroupBy FIRST and LAST skip null subject values, whereas FIRST_INCL_NULL and LAST_INCL_NULL preserve positional nulls. The first-including-null aggregate marks its first non-ignored row as seen even when that subject is null, so a later non-null value does not replace it. The last-including-null aggregate can overwrite a prior value with null. All four output columns inherit the subject type, and ordered fields.field[*].type labels must survive JSON editing and HPL disk reopen. Diagnose downstream null handling from the selected aggregation type; do not reject draft saves."));
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
                  "REST_DYNAMIC_GET_POST_BODY_REQUIRES_STATIC_BODY_CAPABLE_METHOD",
                  List.of("/dynamicMethod", "/methodFieldName", "/method", "/bodyField",
                      "/urlInField", "/urlField"),
                  "For mixed GET/POST input rows, dynamicMethod selects the effective HTTP verb "
                      + "and urlInField selects the URL independently for every row. Native "
                      + "Rest.processRow() binds bodyField only if the configured static method "
                      + "is body-capable, even when dynamicMethod is true: use method=POST for "
                      + "a dynamic GET/POST pipeline that needs POST request bodies. The GET "
                      + "rows send no entity, while POST rows send the current row's body. "
                      + "This is a native runtime compatibility diagnostic, not a draft-save "
                      + "validator."),
              new RuntimePrecondition(
                  "REST_DYNAMIC_METHOD_NATIVE_CHECK_FALSE_POSITIVE",
                  List.of("/dynamicMethod", "/methodFieldName", "/method"),
                  "When dynamicMethod is true, methodFieldName must be non-empty and resolve to an incoming row field. The native RestMeta.check() incorrectly reports ERROR even when the field name is present (MethodFieldOk); this is a native-check false positive, not a reason to reject an otherwise valid dynamic-method configuration. Runtime also validates each incoming HTTP method token. When dynamicMethod is false, method must be a valid non-empty HTTP method token."),
              new RuntimePrecondition(
                  "REST_CUSTOM_METHOD_TOKENS_PRESERVE_CASE_AND_BODY_RULES",
                  List.of("/method", "/dynamicMethod", "/methodFieldName"),
                  "RestMeta.normalizeMethod() trims surrounding whitespace and uppercases only "
                      + "the seven well-known verbs GET, POST, PUT, DELETE, HEAD, OPTIONS and PATCH. "
                      + "A valid custom HTTP method such as pUrGe or M-SEARCH retains its original "
                      + "case, since HTTP method tokens are case-sensitive. RestMeta.isValidMethodToken() "
                      + "accepts RFC 9110 token characters and rejects spaces, CR/LF and empty strings. "
                      + "After normalization, GET/HEAD/OPTIONS have no request body; HEAD/OPTIONS "
                      + "have no query or matrix parameters, while custom verbs may use both. "
                      + "The method field is STRING, not a closed ENUM: preserve custom tokens "
                      + "through JSON config edits and Hop XML persistence. For dynamicMethod=true, "
                      + "methodFieldName identifies the incoming row field; diagnose runtime tokens "
                      + "separately without rejecting draft saves."),
              new RuntimePrecondition(
                  "REST_CONNECTION_BASE_URL_JOINS_RELATIVE_PATHS_AND_ABSOLUTE_OVERRIDE",
                  List.of("/connection_name", "/url", "/urlInField", "/urlField"),
                  "When connection_name resolves to a REST connection, native Rest.resolveAgainstBase() "
                      + "joins its base URL to the static url or an incoming urlField value. It "
                      + "removes one duplicate boundary slash or adds one missing slash; an "
                      + "absolute URL with a valid scheme:// overrides the connection base, while "
                      + "localhost:8080/path is treated as relative because a colon alone is not "
                      + "an absolute URL scheme. An empty path preserves the base URL. Preserve "
                      + "literal URL configuration through JSON/XML edits and diagnose the "
                      + "effective request target after variable resolution; this is not a "
                      + "save-time validator or a request execution proof."),
              new RuntimePrecondition(
                  "REST_GET_RELATIVE_302_307_REDIRECTS_RETURN_FINAL_RESPONSE",
                  List.of("/url", "/method", "/result/name", "/result/code",
                      "/result/response_header"),
                  "The pinned REST transform creates an HttpClient5 client with default "
                      + "redirect handling. For GET, same-origin relative 302 and 307 "
                      + "Location redirects are followed; the result.name STRING body, "
                      + "result.code INTEGER status and result.response_header STRING JSON "
                      + "describe the final response, not the intermediate redirect. "
                      + "A header present only on the 3xx response is not carried into "
                      + "the final header JSON. Keep the configured URL and output-field "
                      + "names losslessly editable across config JSON and Hop XML disk "
                      + "reopen; this is a native runtime compatibility diagnostic, "
                      + "not a save-time validator."),
              new RuntimePrecondition(
                  "REST_NONSTREAMING_204_AND_HTTP_ERRORS_EMIT_TYPED_RESULT_ROWS",
                  List.of("/url", "/method", "/streamingEnabled", "/result/name",
                      "/result/code", "/result/response_header", "/result/binary",
                      "/retryMethods/retryMethod", "/retryStatusCodes/retryStatusCode",
                      "/retryTimes"),
                  "In non-streaming mode when Hop retry rules do not match the outgoing "
                      + "method or response status, native Rest.invokeRestExchange() "
                      + "materializes HTTP responses without requiring a 2xx status. "
                      + "A 204 No Content response produces an empty STRING body, while "
                      + "401, 404 and 500 responses with UTF-8 entities retain their text. "
                      + "The result.code field is declared INTEGER in Hop row metadata "
                      + "and carried as a Java Long in the native result row; the "
                      + "response-header field carries final-response header JSON. "
                      + "Status >=400 is logged but does not by itself throw in this path. "
                      + "If retryMethods includes the outgoing method and retryStatusCodes "
                      + "includes the status, executeWithRetry() can instead exhaust "
                      + "retries and throw, even with retryTimes=0. Do not promise an "
                      + "output row for matching retry rules; streaming non-2xx behavior "
                      + "also differs. Preserve typed result fields and the grouped "
                      + "retry LISTs across JSON edits and Hop XML disk reopen; this "
                      + "is a runtime diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "REST_BODYLESS_ACTIVE_METHODS_SEND_ZERO_LENGTH_ENTITY",
                  List.of("/method", "/applicationType", "/bodyField"),
                  "Native Rest.executeRequest() supplies an empty HTTP entity for active-body "
                      + "methods such as POST, PUT, PATCH and DELETE when no bodyField or "
                      + "request body is configured. HttpClient5 therefore sends Content-Length: 0 "
                      + "with an empty body rather than omitting the entity entirely. The "
                      + "configured applicationType still determines the entity Content-Type. "
                      + "Keep method and applicationType as STRING fields and a missing or "
                      + "empty bodyField distinct from a supplied upstream payload across "
                      + "JSON configuration and Hop XML disk reopen. This is a wire-level "
                      + "compatibility diagnostic, not a save-time validation rule."),
              new RuntimePrecondition(
                  "REST_BINARY_REQUEST_BODY_PRESERVES_BYTES_AND_CLIENT_OWNS_CONTENT_LENGTH",
                  List.of("/method", "/applicationType", "/bodyField", "/headers/header"),
                  "When a REST input bodyField resolves to a BINARY upstream row field, "
                      + "native Rest.invokeRestExchange() passes its byte[] unchanged to "
                      + "the HTTP entity; do not decode it as text or re-encode it using "
                      + "applicationType. For active-body methods such as POST, the "
                      + "HTTP client computes Content-Length from the actual entity. "
                      + "A configured Content-Length header is deliberately ignored "
                      + "case-insensitively, even when supplied by an input row, to avoid "
                      + "a conflicting length. Preserve the literal STRING bodyField, "
                      + "STRING applicationType, method and ordered headers/header LIST "
                      + "through config JSON and Hop XML; diagnose BINARY source type "
                      + "against incoming row metadata. This describes runtime wire "
                      + "compatibility, not a save-time validator."),
              new RuntimePrecondition(
                  "REST_REQUEST_HEADER_EXACT_DUPLICATES_LAST_WINS_ACCEPT_OVERRIDES_DEFAULT",
                  List.of("/headers/header", "/applicationType"),
                  "Native Rest.invokeRestExchange() reads headers.header[*] in configured "
                      + "order into a LinkedHashMap<String,String>. When the exact header name "
                      + "occurs more than once, the later row value replaces the earlier value "
                      + "on the outgoing HTTP request, although both ordered entries remain "
                      + "in the editable JSON and persisted Hop XML. Unlike query parameters, "
                      + "these request headers are not a multivalue list at runtime. An explicit "
                      + "Accept header, detected case-insensitively, suppresses the default "
                      + "Accept derived from applicationType (or streaming SSE). Keep the "
                      + "canonical indexed paths /headers/header/<index>/field and "
                      + "/headers/header/<index>/name; do not deduplicate or silently change "
                      + "draft entries at save time. Diagnose the effective outgoing headers "
                      + "separately from the lossless config wire format."),
              new RuntimePrecondition(
                  "REST_REPEATED_QUERY_PARAMETER_NAMES_PRESERVE_ORDER_AND_VALUES",
                  List.of("/url", "/parameters/parameter"),
                  "Native Rest.buildRequestUri() starts with the existing URL query and "
                      + "appends each parameters.parameter[*] row-field value using URIBuilder.addParameter() "
                      + "in declared LIST order. Repeated parameter names are retained as separate "
                      + "query pairs rather than collapsed into a Map; existing pairs remain first. "
                      + "UTF-8 percent encoding preserves Unicode, literal plus and reserved "
                      + "characters in values. The wire path is /parameters/parameter/<index>/{field,name} "
                      + "because Hop ConfigJsonSerializer groups the repeated parameter leaf under "
                      + "its metadata groupKey. Preserve list indexes and duplicate names through "
                      + "JSON edits and real .hpl disk reopen; this is a runtime compatibility "
                      + "diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "REST_MATRIX_PARAMETERS_USE_PATH_ENCODING_BEFORE_QUERY",
                  List.of("/url", "/matrixParameters/matrixParameter", "/parameters/parameter"),
                  "Native Rest.buildRequestUri() places ordered matrix parameters on the last URL "
                      + "path segment before any existing query string, and adds query parameters "
                      + "using URIBuilder.addParameter() without replacing existing query entries. "
                      + "Matrix values use UTF-8 path percent encoding (space becomes %20, "
                      + "literal plus becomes %2B), not form-style plus-for-space encoding. "
                      + "Do not merge matrix and query parameter editors or interpret a plus in "
                      + "a path segment as a space. The input-row field names and list order "
                      + "remain significant; this is a runtime URL compatibility diagnostic, "
                      + "not a save-time validator."),
              new RuntimePrecondition(
                  "REST_BINARY_RESULT_FIELD_CHANGES_OUTPUT_ROW_TYPE",
                  List.of("/result/name", "/result/binary", "/result/code",
                      "/result/response_time", "/result/response_header"),
                  "RestMeta.getFields() emits result.name as BINARY when result.binary is true, otherwise STRING; no result body column is added when result.name is empty. Result code and response_time columns are INTEGER, and response_header is STRING. With result.binary true, native Rest.invokeRestExchange() keeps the HTTP response body as raw byte[] without charset decoding, and Rest.assembleResultRow() puts those bytes into the BINARY result.name column before the INTEGER status column. A textual result instead decodes the response bytes using the response charset. These names are output columns, not incoming fields; preserve native types and order when deriving downstream row metadata. This is an output-schema compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "REST_GZIP_CONTENT_ENCODING_DECOMPRESSES_BEFORE_TEXT_AND_BINARY_RESULT",
                  List.of("/url", "/result/name", "/result/binary", "/result/code"),
                  "For a non-streaming REST response with Content-Encoding: gzip, the "
                      + "native HttpClient5 response entity is transparently decompressed "
                      + "before Rest.materialize() consumes its bytes. With result.binary=false, "
                      + "the decompressed bytes are decoded using the response Content-Type "
                      + "charset and emitted as STRING. With result.binary=true, the BINARY "
                      + "result contains the decompressed entity bytes, not the compressed "
                      + "transfer representation. HTTP status remains the final numeric code. "
                      + "Preserve result.binary as BOOLEAN and result.name/result.code as "
                      + "STRING through JSON config and Hop XML; do not interpret binary mode "
                      + "as a guarantee of wire-level compressed bytes. This is a runtime "
                      + "compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "REST_RESULT_FIELD_NAMES_RESOLVE_VARIABLES_AND_OMIT_EMPTY_HEADER",
                  List.of("/result/name", "/result/code", "/result/response_time",
                      "/result/response_header"),
                  "Unlike HttpMeta, RestMeta.getFields() resolves Hop variables in all four result output names. It appends non-empty configured result.name (STRING or BINARY), result.code (INTEGER), and result.response_time (INTEGER) in that order, followed by a STRING response_header only when its resolved name is non-empty. For the first three names, the native empty check precedes variable resolution; the header empty check follows resolution. Preserve upstream fields and the native output order when diagnosing downstream row schemas. This is not a save-time validator."),
              new RuntimePrecondition(
                  "REST_RESPONSE_CHARSET_HEADER_OVERRIDES_REQUEST_TYPE_UTF8_FALLBACK",
                  List.of("/applicationType", "/result/name", "/result/binary"),
                  "For textual REST results, native Rest.resolveCharset() chooses the response "
                      + "Content-Type charset when it is valid (for example UTF-16LE), and "
                      + "falls back to UTF-8 when the response type is absent, lacks a charset, "
                      + "or is malformed. applicationType configures the request body type; "
                      + "it must not be treated as the response character encoding. A binary "
                      + "result preserves response bytes instead of decoding text. Preserve "
                      + "the canonical STRING applicationType and BOOLEAN result.binary "
                      + "configuration and diagnose downstream response decoding separately; "
                      + "this is not a save-time validator or a claim of network execution."),
              new RuntimePrecondition(
                  "REST_SPLIT_PATH_JSONPATH_OVERRIDES_REQUEST_TYPE_XPATH_REQUIRES_XML",
                  List.of("/applicationType", "/resultSplitPath", "/result/name"),
                  "For non-streaming REST response splitting, a resultSplitPath starting with $ "
                      + "selects JsonPath even when applicationType is FORM URLENCODED or TEXT "
                      + "PLAIN: applicationType describes the request content type, not necessarily "
                      + "the response. An XPath such as /root/item instead requires an XML "
                      + "applicationType (XML, TEXT XML, ATOM XML, SVG XML, or XHTML); TEXT PLAIN "
                      + "with XPath raises HopException when native splitResultItems() processes "
                      + "the response. JsonPath array matches produce ordered JSON fragments, "
                      + "while XPath node matches produce ordered XML fragments. Preserve the "
                      + "literal STRING paths in the JSON/HPL draft and diagnose this format "
                      + "dependency without inventing a save-time rejection. The split behavior "
                      + "can be verified locally without issuing an HTTP request."),
              new RuntimePrecondition(
                  "REST_STREAMING_SPLIT_PATH_REJECTED_BEFORE_HTTP",
                  List.of("/streamingEnabled", "/resultSplitPath", "/result/binary",
                      "/paginationEnabled"),
                  "With streamingEnabled=true, Rest.processRow() rejects a non-empty "
                      + "resultSplitPath on its first row before sending an HTTP request: "
                      + "streaming consumes individual records while JsonPath/XPath splitting "
                      + "requires a complete response document. Native rejection occurs even "
                      + "when paginationEnabled=false and result.binary=false. Preserve the "
                      + "editable draft and canonical STRING/BOOLEAN wire values; warn before "
                      + "execution rather than silently dropping the split path or streaming "
                      + "setting. This runtime guard does not prove HTTP streaming works."),
              new RuntimePrecondition(
                  "REST_STREAMING_BINARY_RESULT_REJECTED_BEFORE_HTTP",
                  List.of("/streamingEnabled", "/result/binary", "/paginationEnabled",
                      "/resultSplitPath"),
                  "With streamingEnabled=true and result.binary=true, Rest.processRow() "
                      + "rejects the first row before issuing HTTP, even when paginationEnabled "
                      + "is false and resultSplitPath is empty. Rest.init() accepts this pair "
                      + "when pagination is disabled, and RestMeta.getFields() still projects "
                      + "the configured result.name as BINARY. Keep both BOOLEAN settings "
                      + "editable and persisted through JSON/HPL, but diagnose the incompatible "
                      + "runtime combination rather than silently converting response bytes "
                      + "to text or clearing the streaming option. This is not a save-time "
                      + "validator or a network execution proof."),
              new RuntimePrecondition(
                  "REST_PAGINATION_REJECTS_BINARY_RESULT_AT_INIT",
                  List.of("/paginationEnabled", "/result/binary", "/result/name"),
                  "Native Rest.init() rejects paginationEnabled=true together with result.binary=true "
                      + "before any HTTP exchange: paging must inspect a textual response body "
                      + "to extract page tokens or split records, whereas binary mode preserves "
                      + "opaque bytes. RestMeta.check() does not detect this combination, and "
                      + "RestMeta.getFields() still projects a BINARY result column when a result "
                      + "name is configured. Diagnose the incompatible BOOLEAN pair before "
                      + "execution without silently changing either persisted setting or "
                      + "rejecting an editable draft save."),
              new RuntimePrecondition(
                  "REST_PAGINATION_WITHOUT_PAGING_CONNECTION_FALLS_BACK",
                  List.of("/paginationEnabled", "/connection_name"),
                  "The transform-level paginationEnabled flag alone does not activate paging. "
                      + "Native Rest.supportsPaging() also requires a loaded REST connection "
                      + "whose pagination type is non-null and not NONE. With no connection, "
                      + "Rest.processRow() uses the legacy single-request path even when "
                      + "paginationEnabled=true, emitting only a detailed diagnostic. "
                      + "Keep the editable draft intact but surface the missing connection "
                      + "dependency before users expect multiple pages; this is not a "
                      + "save-time rejection or a claim that network paging was tested."),
              new RuntimePrecondition(
                  "REST_FIELD_DRIVEN_OPTIONS_REQUIRE_INPUT_HOP",
                  List.of("/urlInField", "/urlField", "/dynamicMethod",
                      "/methodFieldName", "/bodyField", "/headers/header",
                      "/parameters/parameter", "/matrixParameters/matrixParameter"),
                  "A REST transform without any incoming hop can act as a source for a static "
                      + "request, even though RestMeta.check() reports no input. However, native "
                      + "Rest.processRow() rejects every field-driven option on the first row "
                      + "before issuing HTTP: urlInField, dynamicMethod, bodyField, non-empty "
                      + "headers/header, parameters/parameter or matrixParameters/matrixParameter. "
                      + "The guard checks options in that order, and does not exempt a bodyField "
                      + "merely because the configured method is GET. Preserve the typed BOOLEAN "
                      + "and STRING settings and ordered grouped LIST values in JSON and HPL; "
                      + "diagnose the missing upstream hop rather than silently clearing editable "
                      + "draft fields. The canonical pointers refer to the wire containers built "
                      + "from Hop groupKey, not a flattened form path."),
              new RuntimePrecondition(
                  "REST_BASIC_AUTH_PREEMPTIVE_FLAG_IS_INVERTED",
                  List.of("/non_preemptive_basic_auth"),
                  "RestMeta stores non_preemptive_basic_auth as the BOOLEAN inverse of isPreemptive(): false (the default) sends Basic credentials on the first request, while true waits for a 401 challenge. The legacy XML preemptive key was removed because it never affected runtime behavior; an old HPL lacking non_preemptive_basic_auth must retain the default preemptive behavior. Preserve this exact wire boolean through config JSON editing and Hop XML disk reopen; do not reverse the UI label into the persisted value or expose password material. This is a compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "REST_HTTPCLIENT_DEFAULT_429_503_RETRIES_ARE_INDEPENDENT_OF_HOP_LISTS",
                  List.of("/method", "/retryMethods/retryMethod",
                      "/retryStatusCodes/retryStatusCode", "/retryTimes", "/retryDelayMs"),
                  "RestClientFactory builds HttpClient5 without disabling automatic retries. "
                      + "The default HttpClient retry strategy may retry HTTP 429 and 503 once "
                      + "for a GET request and uses the response Retry-After header to determine "
                      + "its delay. This happens inside a single Rest.executeWithRetry() supplier "
                      + "call, independently of the Hop retryMethods/retryMethod and "
                      + "retryStatusCodes/retryStatusCode lists; empty Hop retry lists therefore "
                      + "do not guarantee one network request. If Hop-level retries are also "
                      + "enabled, network attempts can multiply. Preserve both grouped LISTs "
                      + "and typed numeric retry settings through JSON/HPL, and diagnose the "
                      + "two retry layers without silently rewriting user configuration."),
              // Hop's retry method and status-code lists use exact string membership.
              // They are not case-folded or trimmed by the runtime.
              new RuntimePrecondition(
                  "REST_RETRY_METHOD_AND_STATUS_LISTS_MATCH_EXACT_TOKENS",
                  List.of("/method", "/retryMethods/retryMethod",
                      "/retryStatusCodes/retryStatusCode", "/retryTimes", "/retryDelayMs"),
                  "Rest.executeWithRetry() tests retryMethods.retryMethod using exact "
                      + "case-sensitive String.contains against the normalized outgoing HTTP "
                      + "method (for example GET); a lowercase get entry does not match GET. "
                      + "It likewise compares the decimal response status text exactly "
                      + "against retryStatusCodes.retryStatusCode: a value containing spaces "
                      + "does not match 503. These grouped String LIST values preserve order "
                      + "and duplicates through the canonical JSON/HPL transport, but duplicate "
                      + "entries do not increase retry attempts. Keep the editable lists as "
                      + "literal strings and surface this runtime diagnostic; do not silently "
                      + "uppercase, trim, deduplicate or reject a draft at save time."));
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
                  "ReplaceStringMeta.getFields() copies the source field's string encoding onto a newly appended STRING field when fields.field[*].out_stream_name is non-empty and the source field exists. With an empty out_stream_name, it does not append a field; it normalizes the matched input field's storage type to STORAGE_TYPE_NORMAL, leaving unrelated fields unchanged. A missing source is silently skipped in this metadata projection for in-place rules, although native check reports an error; do not treat getFields() success as validation. This is a downstream field-metadata compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "REPLACE_STRING_REPLACEMENT_FIELD_RESOLVES_PER_INPUT_ROW",
                  List.of("/fields/field"),
                  "When fields.field[*].replace_field_by_string is non-empty, native ReplaceString.processRow() looks up that upstream STRING-convertible field once at initialization and takes its replacement text separately from every incoming row. This overrides the configured replace_by_string literal; set_empty_string=true takes precedence over both. An absent replacement field fails native initialization even when the source field exists. Preserve the literal field reference, BOOLEAN flags, and ordered LIST across JSON editing and HPL disk reopen; this is a runtime diagnostic, not a draft-save validator."),
              new RuntimePrecondition(
                  "REPLACE_STRING_REGEX_CAPTURE_GROUPS_USE_JAVA_MATCHER_REPLACEMENT",
                  List.of("/fields/field"),
                  "With fields.field[*].use_regex=true, native ReplaceString builds a Java "
                      + "Pattern from replace_string and passes replace_by_string to "
                      + "Matcher.replaceAll(). Thus $1 and $2 refer to capture groups "
                      + "rather than literal dollar text; a replacement such as $2/$1 "
                      + "reorders the captured fields. A non-empty out_stream_name appends "
                      + "the transformed STRING while preserving the original input. "
                      + "Native replacement preprocessing doubles backslashes but does "
                      + "not quote dollar signs. Preserve the regex flag, replacement "
                      + "expression and canonical ordered LIST item fields across JSON "
                      + "editing and real HPL disk reopen; this is an execution "
                      + "compatibility diagnostic, not a save-time validator."));
      case "org.apache.hop.pipeline.transforms.selectvalues.SelectValuesMeta" ->
          List.of(
              new RuntimePrecondition(
                  "SELECT_VALUES_STAGE_SPECIFIC_FIELD_RESOLUTION",
                  List.of("/fields/field", "/fields/remove", "/fields/meta"),
                  "Select & Alter field[*].name resolves against incoming row fields; Remove remove[*].name resolves against the row after selection and renaming; Metadata meta[*].name resolves after removal. A renamed field must be referenced by its new name in later stages. Diagnose each stage against its own row metadata, not only the original input."),
              new RuntimePrecondition(
                  "SELECT_VALUES_UNSPECIFIED_FIELDS_SORT_AFTER_EXPLICIT_SELECTION",
                  List.of("/fields/select_unspecified", "/fields/field"),
                  "When fields.field contains an explicit selection, SelectValuesMeta.getFields() emits selected fields in configured list order (using their output rename), then, only if fields.select_unspecified is true, appends all unselected incoming fields sorted by original field name rather than input order. When the flag is false, unselected fields are omitted. With an empty fields.field list, native selection is skipped and the original input order remains unchanged regardless of select_unspecified. Diagnose downstream column order from both settings; do not reject draft saves."),
              new RuntimePrecondition(
                  "SELECT_VALUES_METADATA_CHANGES_TYPE_AND_FORMAT_AFTER_SELECTION",
                  List.of("/fields/meta"),
                  "SelectValuesMeta.getFields() applies fields.meta[*] after selection, renaming, and removal. Each fields.meta[*].name matches the post-selection field name. A non-NONE fields.meta[*].type change clones the value metadata to the requested Hop type and resets binary-string storage to NORMAL. Configured rename, length, precision, conversion_mask, encoding, decimal_symbol, and grouping_symbol then propagate to the same output field; unselected columns are not converted. Diagnose downstream type and format from the ordered metadata stage, not from the original upstream type. This is an output-schema compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "SELECT_VALUES_LOCALE_DECIMAL_GROUPING_CONVERTS_REAL_NUMERIC_ROWS",
                  List.of("/fields/meta"),
                  "When a post-selection STRING field is converted to Number by fields.meta[*], "
                      + "the native SelectValues metadataValues() stage applies its conversion_mask, "
                      + "decimal_symbol and grouping_symbol to the source value metadata before "
                      + "performing typed conversion. For example, grouping symbol '.' and decimal "
                      + "symbol ',' allow localized input 1.234,50 to become NUMBER 1234.5 when "
                      + "the numeric mask is configured accordingly. Preserve these STRING "
                      + "properties, their LIST index and the target type across JSON editing "
                      + "and HPL disk reopen. This is a runtime compatibility diagnostic, "
                      + "not a save-time validator."),
              new RuntimePrecondition(
                  "SELECT_VALUES_STRING_BOOLEAN_UNKNOWN_TOKENS_MAP_FALSE",
                  List.of("/fields/meta"),
                  "When a post-selection STRING field is converted to Boolean through "
                      + "fields.meta[*].type, pinned Hop ValueMetaBase recognizes Y, TRUE, YES "
                      + "and 1 (case-insensitive for words) as true; any other non-empty "
                      + "token, including N, FALSE, 0 and an unrecognized value, becomes "
                      + "false rather than producing a conversion error. An empty source "
                      + "string converts to null. Native SelectValues.metadataValues() "
                      + "uses this conversion when source and target metadata types differ. "
                      + "Preserve the ordered fields.meta LIST, the source field name, "
                      + "output rename and Boolean target type through JSON editing and "
                      + "real HPL disk reopen. An editor should flag unexpected tokens "
                      + "as a data-quality concern rather than silently promising strict "
                      + "Boolean validation; this is a runtime compatibility diagnostic, "
                      + "not a save-time validator."),
              new RuntimePrecondition(
                  "SELECT_VALUES_BIGNUMBER_EXACT_DECIMAL_CONVERSION",
                  List.of("/fields/meta"),
                  "For a post-selection STRING field whose fields.meta[*].type is BigNumber, "
                      + "native SelectValues.metadataValues() converts the source value to "
                      + "the BigNumber target metadata rather than first narrowing it to "
                      + "a double-precision Number. With length and precision both -1 and "
                      + "no rounding mask, long decimal values and very small fractional "
                      + "values must remain exact BigDecimal values in emitted rows. "
                      + "Preserve the ordered fields.meta LIST, type, length and precision "
                      + "through JSON editing and real HPL disk reopen; never coerce "
                      + "the BigNumber wire type to Number for a generic editor. "
                      + "This is an engine compatibility diagnostic, not a save-time validator."));
      case "org.apache.hop.pipeline.transforms.sort.SortRowsMeta" ->
          List.of(
              new RuntimePrecondition(
                  "SORT_ROWS_KEYS_AND_TEMP_DIRECTORY_NATIVE_CHECK",
                  List.of("/fields/field", "/directory"),
                  "Native SortRowsMeta.check() requires a non-empty fields.field list, with each fields.field[*].name present in incoming row metadata. The resolved directory must exist and be a directory for temporary sort files; missing upstream fields or input hops are separate native errors. Diagnose against upstream metadata and resolved variables, not just the JSON field types."),
              new RuntimePrecondition(
                  "SORT_ROWS_GETFIELDS_PROPAGATES_SORT_FLAGS_AND_NORMALIZES_STORAGE",
                  List.of("/fields/field"),
                  "SortRowsMeta.getFields() preserves incoming field names, types and positions but annotates each resolved fields.field[*].name with sortedDescending = !ascending, caseInsensitive = !case_sensitive, collatorDisabled = !collator_enabled and collatorStrength from collator_strength. Selected keys are also normalized to STORAGE_TYPE_NORMAL with null storage metadata; unselected or unknown fields are left untouched. The presorted flag does not change this getFields metadata propagation. This is a downstream row-metadata compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "SORT_ROWS_NONCOLLATOR_STRING_KEYS_USE_JAVA_CODE_UNIT_ORDER",
                  List.of("/fields/field"),
                  "For native SortRows string keys with fields.field[*].collator_enabled=false and case_sensitive=true, pinned ValueMetaBase.compare() uses Java String.compareTo, not locale-aware or natural-language ordering. For example, the character 乙 (U+4E59) sorts before 甲 (U+7532) in ascending order. Multi-key SortRows still applies configured ascending/descending flags in list order. To request locale-aware collation, the user must enable the collator and choose a supported strength; do not silently reinterpret the saved key order or compare semantics. This is a runtime compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "SORT_ROWS_NONCOLLATOR_CASE_INSENSITIVE_USES_JAVA_IGNORE_CASE_ORDER",
                  List.of("/fields/field"),
                  "When fields.field[*].collator_enabled=false and case_sensitive=false for a native SortRows String key, pinned ValueMetaBase.compare() uses Java String.compareToIgnoreCase, not the configured locale Collator. For ascending values z, B, a and M, the native output order is a, B, M, z; case-sensitive Java comparison would place B and M before a. The serializer must retain both flags as Boolean values at their canonical ordered LIST item paths. This is an engine behavior compatibility diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "SORT_ROWS_UNIQUE_KEYS_DROP_NONKEY_PAYLOAD_ACROSS_SPILLS",
                  List.of("/unique_rows", "/fields/field", "/sort_size"),
                  "When unique_rows=true, native SortRows removes rows whose configured "
                      + "sort-key tuple compares equal, even if other payload columns differ. "
                      + "The comparison uses fields.field in declared order, including each "
                      + "key's ascending, case_sensitive and collator settings; it is not a "
                      + "whole-row DISTINCT. SortRows deduplicates within temporary sorted "
                      + "runs and again while merging them, so a small sort_size does not "
                      + "preserve duplicate keys across disk spills. This can discard "
                      + "non-key payload data. Keep unique_rows as a Boolean and the ordered "
                      + "fields.field list intact through JSON and HPL persistence; this "
                      + "is a native execution diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "SORT_ROWS_NUMERIC_NULL_PLACEMENT_FOLLOWS_DIRECTION",
                  List.of("/fields/field"),
                  "For a native SortRows numeric key, pinned ValueMetaBase.compare() treats a "
                      + "null input value as smaller than every non-null value in ascending "
                      + "order, but greater than every non-null value when the key's "
                      + "ascending flag is false. Thus a missing INTEGER value sorts first "
                      + "ascending and last descending, while non-null numeric values retain "
                      + "their numeric ordering. Preserve the ordered fields.field LIST and "
                      + "typed ascending BOOLEAN through JSON editing and real HPL disk "
                      + "reopen; do not substitute lexical string sorting or silently "
                      + "discard rows with missing numeric values. This is a runtime "
                      + "compatibility diagnostic, not a save-time validator."));
      case "org.apache.hop.pipeline.transforms.streamlookup.StreamLookupMeta" ->
          List.of(
              new RuntimePrecondition(
                  "STREAM_LOOKUP_TWO_INPUTS_AND_STAGE_SPECIFIC_FIELDS",
                  List.of("/from", "/lookup/key", "/lookup/value"),
                  "StreamLookup needs a selected lookup-source transform and at least two incoming hops: the main stream and the lookup/info stream. Each lookup.key[*].name must resolve in the main incoming row, whereas lookup.key[*].field and lookup.value[*].name must resolve in lookup/info row metadata. Native StreamLookupMeta.check() checks these separately, including missing source and missing input hops. A return value's rename is an output alias, not a lookup input field. Diagnose graph and row-metadata prerequisites without rejecting draft saves."),
              new RuntimePrecondition(
                  "STREAM_LOOKUP_RETURN_TYPES_FOLLOW_INFO_ROW_OR_DEFAULT_TYPE",
                  List.of("/lookup/value"),
                  "StreamLookupMeta.getFields() appends lookup.value[*] in configured order. With exactly one non-null lookup/info row metadata entry, each lookup.value[*].name must exist in that info row; the output uses its actual source type, not lookup.value[*].type, and is renamed to lookup.value[*].rename. Native getFields() renames the found info-row IValueMeta in place, so callers deriving schemas must isolate shared row metadata. With no usable single info-row metadata entry, getFields() instead creates output fields using each configured default type. Missing source fields with a usable info row throw HopTransformException. Diagnose the output type from info-row availability, without rejecting draft saves."),
              new RuntimePrecondition(
                  "STREAM_LOOKUP_MISSING_MATCH_USES_TYPED_DEFAULT_VALUES",
                  List.of("/lookup/key", "/lookup/value"),
                  "For each main input row, StreamLookup matches lookup.key[*] against the selected info stream. A matching key appends the actual lookup.value[*].name fields; a missing key appends the configured lookup.value[*].default for each return field, converted to its declared type at initialization (STRING, INTEGER, etc.). Hop ConfigJsonSerializer transports lookup.value[*].type as a numeric IValueMeta type code (for example 2 for STRING and 5 for INTEGER), not the human-readable XML intCodeConverter name. Defaults resolve variables once at initialization; an empty STRING default becomes null. The output field schema still follows the actual info-row types when available. Preserve ordered return values and distinguish a missing match from a missing lookup-source field; this is an execution diagnostic, not a draft-save rejection."),
              new RuntimePrecondition(
                  "STREAM_LOOKUP_DUPLICATE_INFO_KEYS_LAST_VALUE_WINS",
                  List.of("/from", "/preserve_memory", "/sorted_list",
                      "/lookup/key", "/lookup/value"),
                  "Native StreamLookup.addToCache() overwrites an earlier info row "
                      + "with the same lookup key: the ordinary map, memory-preserving "
                      + "byte-array hash index, and sorted-list cache all retain the "
                      + "later value. A matching main row receives that last value, "
                      + "not a one-to-many result or a duplicate-key error. Info-stream "
                      + "row order therefore changes results when keys are duplicated. "
                      + "Preserve the from reference, ordered lookup.key and lookup.value "
                      + "LISTs, and cache-mode BOOLEAN flags across config JSON editing "
                      + "and HPL disk reopen. Diagnose duplicate lookup keys as a "
                      + "data-quality risk, not a draft-save validator."),
              new RuntimePrecondition(
                  "STREAM_LOOKUP_STRING_KEYS_CASE_SENSITIVE_ACROSS_CACHE_MODES",
                  List.of("/from", "/lookup/key", "/lookup/value",
                      "/preserve_memory", "/sorted_list"),
                  "Native StreamLookup compares STRING lookup keys using their actual "
                      + "row-metadata string values. Case variants such as Station_A, "
                      + "station_a and STATION_A are distinct lookup keys with the "
                      + "default string metadata, in the ordinary map, memory-preserving "
                      + "hash-index and sorted-list cache modes. A case-only mismatch "
                      + "therefore emits configured typed defaults instead of a match. "
                      + "Preserve the exact input and info key values and the ordered "
                      + "lookup.key and lookup.value lists; diagnose case mismatches "
                      + "without silently normalizing identifiers or rejecting drafts."));
      case "org.apache.hop.pipeline.transforms.stringcut.StringCutMeta" ->
          List.of(
              new RuntimePrecondition(
                  "STRING_CUT_SOURCE_FIELDS_REQUIRE_UPSTREAM_STRINGS",
                  List.of("/fields/field"),
                  "Each fields.field[*].in_stream_name must be non-empty and exist in the incoming row as STRING. The native StringCut check rejects missing or non-string source fields, but unlike ReplaceString does not reject repeated source names. out_stream_name is an optional output alias, not an upstream field."),
              new RuntimePrecondition(
                  "STRING_CUT_ALIAS_APPENDS_VARIABLE_RESOLVED_FIXED_LENGTH_STRING",
                  List.of("/fields/field"),
                  "For each fields.field[*], StringCutMeta.getFields() preserves incoming columns and appends a STRING field of length 100 only when out_stream_name is non-empty. The appended name resolves Hop variables; a blank alias updates the original field in place without appending a column. Multiple rules can reference the same input field, and their non-empty output aliases append in rule order. Diagnose downstream field names, order, and length without blocking draft saves."),
              new RuntimePrecondition(
                  "STRING_CUT_ALIAS_FRESH_METADATA_AND_INPLACE_STORAGE_NORMALIZATION",
                  List.of("/fields/field"),
                  "StringCutMeta.getFields() creates a fresh ValueMetaString for each non-empty fields.field[*].out_stream_name, with resolved alias, length 100, default string encoding and the StringCut transform origin. Unlike ReplaceString, it does not clone the input field encoding or require the source to exist at this metadata-projection stage; native check separately validates source fields. With an empty alias, getFields() normalizes the matching incoming field to STORAGE_TYPE_NORMAL without adding a column, and silently skips missing sources; unrelated columns retain their storage type. Derive downstream encoding and storage from these distinct branches; this is a diagnostic, not a save-time validator."),
              new RuntimePrecondition(
                  "STRING_CUT_NEGATIVE_OFFSETS_AND_OUT_OF_RANGE_NULL",
                  List.of("/fields/field"),
                  "Native StringCut interprets cut_from and cut_to as resolved integer offsets into Java UTF-16 strings, not code-point indices. With both offsets negative, it uses substring(length + cut_to, length + cut_from): (-1,-3) extracts the middle two characters of a four-character BMP string. With cut_from=0 and cut_to=-2, it selects the final two characters. Nonnegative cut_from greater than the input length, or a nonnegative cut_to less than cut_from, produces null rather than a truncated value. Supplementary Unicode symbols count as two UTF-16 units and may be split by a boundary. Preserve the ordered STRING offsets and alias names through config JSON and HPL; diagnose the native result without rejecting draft saves."));
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
                  "When return_keys is true, return_field must resolve to a non-empty output field name. Native TableOutputMeta.getFields() adds an INTEGER field only when both conditions hold, but TableOutput may append the generated key to successful output rows whenever return_keys is true. An empty return_field therefore risks row-data/metadata width mismatch. The output field name is variable-resolved; diagnose this before execution without blocking draft saves."),
              new RuntimePrecondition(
                  "TABLE_OUTPUT_GENERATED_KEY_RESOLVED_COLLISION_AND_EMPTY_EXPRESSION",
                  List.of("/return_keys", "/return_field"),
                  "TableOutputMeta.getFields() tests the configured return_field for emptiness before resolving Hop variables. With return_keys true and a non-empty variable expression, it appends an INTEGER field with the resolved name; RowMeta.addValueMeta() suffixes collisions with existing fields (including previously suffixed names) rather than replacing input columns. An expression resolving to an empty string still appends an empty-named INTEGER field, whereas a literally empty return_field or return_keys false appends none. Diagnose the resulting downstream row schema separately from generated-key database execution; do not reject draft saves."),
              new RuntimePrecondition(
                  "TABLE_OUTPUT_DYNAMIC_TABLE_NAME_FIELD_CONTROLS_INSERT_PROJECTION",
                  List.of("/tablename_in_field", "/tablename_field", "/tablename_in_table",
                      "/specify_fields"),
                  "When tablename_in_field is true, TableOutput resolves tablename_field as a Hop variable expression and requires that field in each incoming row to choose the destination table; a missing field fails at runtime and an empty row value cannot identify a table. When tablename_in_table is false and specify_fields is false, the runtime excludes the table-name field from its internal INSERT row and column list. With specify_fields true, the explicit fields.field mapping determines inserted columns instead; the table-name field is not automatically removed. This does not remove the field from TableOutputMeta.getFields() downstream row metadata. Diagnose source-field existence and insert projection separately; do not reject draft saves."));
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
