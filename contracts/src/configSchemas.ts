import type { HopConfigSchema, HopConfigTab } from "./index.js";

/**
 * First real Transform contract. Keys mirror TableInputMeta @HopMetadataProperty keys so the thin
 * server adapter can map values without introducing a second business model.
 */
export const tableInputConfigSchema: HopConfigSchema = {
  pluginId: "TableInput",
  pluginType: "transform",
  title: "Table Input",
  tier: "T1",
  fields: [
    { key: "connection", label: "Connection", control: "MetadataPicker", valueType: "string", metadataType: "DatabaseMeta", required: true, variable: true },
    { key: "lookup", label: "SQL parameter source", control: "VariableInput", valueType: "string", pickerHint: "INFO_STREAM_TRANSFORM_PICKER", description: "Named pipeline INFO-stream transform supplying SQL parameter rows; preserve the literal transform name, not a database metadata reference." },
    {
      key: "sql", label: "SQL", control: "MonacoEditor", valueType: "string",
      language: "sql", variable: true,
      // Native TableInput.getEffectiveSql() reads a nonempty VFS file first.
      // Keep both wire values; the editor is read-only only while file mode is active.
      sqlSourceContext: {
        inlineSqlPointer: "/sql",
        fileSqlPointer: "/sql_from_file",
        precedence: "FILE_WHEN_NONEMPTY",
        inlineReadOnlyWhenFileSelected: true,
      },
    },
    { key: "sql_from_file", label: "SQL from file", control: "VfsPicker", valueType: "string", vfsMode: "file", variable: true, description: "When nonempty, native TableInput reads SQL from this HopVfs file instead of inline SQL; retain the inline SQL draft when switching back." },
    { key: "limit", label: "Row limit", control: "VariableInput", valueType: "string", variable: true },
    { key: "execute_each_row", label: "Execute for each row", control: "VariableInput", valueType: "boolean" },
    { key: "variables_active", label: "Replace variables", control: "VariableInput", valueType: "boolean" },
    { key: "use_named_parameters", label: "Use named parameters", control: "VariableInput", valueType: "boolean" },
    { key: "specify_fields", label: "Specify output fields", control: "VariableInput", valueType: "boolean" },
    { key: "validate_specified_fields", label: "Validate specified fields", control: "VariableInput", valueType: "boolean", activation: { flagPointer: "/specify_fields", activeWhen: true } },
    {
      key: "fields.field",
      label: "Output fields",
      activation: { flagPointer: "/specify_fields", activeWhen: true },
      control: "EditableTable",
      valueType: "object[]",
      columns: [
        { key: "name", label: "Name", valueType: "string" },
        { key: "type", label: "Type", valueType: "integer", pickerHint: "VALUE_META_CODE_PICKER" },
        { key: "format", label: "Format", valueType: "string" },
        { key: "length", label: "Length", valueType: "integer" },
        { key: "precision", label: "Precision", valueType: "integer" },
      ],
    },
  ],
};

/** Keys follow SelectValuesMeta -> SelectOptions @HopMetadataProperty nesting. */
export const selectValuesConfigSchema: HopConfigSchema = {
  pluginId: "SelectValues",
  pluginType: "transform",
  title: "Select Values",
  tier: "T1",
  fields: [
    {
      key: "fields.field",
      label: "Select & alter fields",
      control: "EditableTable",
      valueType: "object[]",
      columns: [
        { key: "name", label: "Field", valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER" },
        { key: "rename", label: "Rename to", valueType: "string" },
        { key: "length", label: "Length", valueType: "integer" },
        { key: "precision", label: "Precision", valueType: "integer" },
      ],
    },
    { key: "fields.select_unspecified", label: "Include unspecified fields", control: "VariableInput", valueType: "boolean" },
    { key: "fields.remove", label: "Remove fields", control: "EditableTable", valueType: "object[]", columns: [{ key: "name", label: "Field", valueType: "string", pickerHint: "STAGED_FIELD_PICKER", fieldSourceStage: "AFTER_SELECT" }] },
    {
      key: "fields.meta",
      label: "Metadata changes",
      control: "EditableTable",
      valueType: "object[]",
      columns: [
        { key: "name", label: "Field", valueType: "string", pickerHint: "STAGED_FIELD_PICKER", fieldSourceStage: "AFTER_REMOVE" },
        { key: "rename", label: "Rename to", valueType: "string" },
        { key: "type", label: "Type", valueType: "string", pickerHint: "VALUE_META_TYPE_PICKER" },
        { key: "length", label: "Length", valueType: "integer" },
        { key: "precision", label: "Precision", valueType: "integer" },
        { key: "storage_type", label: "Storage type", valueType: "string" },
        { key: "conversion_mask", label: "Format", valueType: "string" },
        { key: "date_format_lenient", label: "Lenient date parsing", valueType: "boolean" },
        { key: "date_format_locale", label: "Date locale", valueType: "string" },
        { key: "date_format_timezone", label: "Date time zone", valueType: "string" },
        { key: "lenient_string_to_number", label: "Lenient numeric parsing", valueType: "boolean" },
        { key: "decimal_symbol", label: "Decimal symbol", valueType: "string" },
        { key: "grouping_symbol", label: "Grouping symbol", valueType: "string" },
        { key: "currency_symbol", label: "Currency symbol", valueType: "string" },
        { key: "encoding", label: "Encoding", valueType: "string" },
        { key: "roundingType", label: "Rounding type", valueType: "string" },
      ],
    },
  ],
};

/** Keys mirror SortRowsMeta and SortRowsField @HopMetadataProperty keys. */
export const sortRowsConfigSchema: HopConfigSchema = {
  pluginId: "SortRows",
  pluginType: "transform",
  title: "Sort Rows",
  tier: "T1",
  fields: [
    {
      key: "fields.field",
      label: "Sort fields",
      control: "EditableTable",
      valueType: "object[]",
      columns: [
        { key: "name", label: "Field", valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER" },
        { key: "ascending", label: "Ascending", valueType: "boolean" },
        { key: "case_sensitive", label: "Case sensitive", valueType: "boolean" },
        { key: "collator_enabled", label: "Use collator", valueType: "boolean" },
        { key: "collator_strength", label: "Collator strength", valueType: "integer" },
        { key: "presorted", label: "Presorted", valueType: "boolean" },
      ],
    },
    { key: "directory", label: "Temporary directory", control: "VariableInput", valueType: "string", pickerHint: "LOCAL_DIRECTORY_PICKER", variable: true, description: "Native SortRowsMeta.check() validates the resolved directory with java.io.File.isDirectory(), while execution creates spill files through HopVfs. Prefer a server-local directory that passes native validation; preserve Hop variable expressions." },
    { key: "sort_prefix", label: "Temporary file prefix", control: "VariableInput", valueType: "string", variable: true },
    { key: "sort_size", label: "Sort size", control: "VariableInput", valueType: "string", variable: true },
    { key: "free_memory", label: "Free memory threshold", control: "VariableInput", valueType: "string", variable: true },
    { key: "unique_rows", label: "Only pass unique rows", control: "VariableInput", valueType: "boolean" },
    { key: "compress", label: "Compress temporary files", control: "VariableInput", valueType: "boolean" },
    { key: "compress_variables", label: "Compression variable", control: "VariableInput", valueType: "string", variable: true },
  ],
};

/**
 * P0 CheckSum uses a grouped native input-field LIST and two code-backed enums.
 * Keep enum values as the Hop wire codes; the installed runtime descriptor supplies
 * the actual allowed options, not this static T1 layout.
 */
export const checkSumConfigSchema: HopConfigSchema = {
  pluginId: "CheckSum",
  pluginType: "transform",
  title: "Add a checksum",
  tier: "T1",
  fields: [
    { key: "fields.field", label: "Fields to checksum", control: "EditableTable", valueType: "object[]",
      columns: [{ key: "name", label: "Input field", valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER" }] },
    { key: "resultfieldName", label: "Result field name", control: "VariableInput", valueType: "string", variable: true, required: true },
    { key: "checksumtype", label: "Checksum algorithm", control: "VariableInput", valueType: "string",
      description: "Native CheckSumType code; use the runtime descriptor's code-backed ENUM options, not localized labels." },
    { key: "resultType", label: "Result encoding", control: "VariableInput", valueType: "string",
      description: "Native ResultType code; preserve the runtime descriptor's code-backed ENUM values." },
    { key: "separator", label: "Field separator", control: "VariableInput", valueType: "string", variable: true },
    { key: "prefix", label: "Prefix", control: "VariableInput", valueType: "string", variable: true },
    { key: "suffix", label: "Suffix", control: "VariableInput", valueType: "string", variable: true },
  ],
};

/**
 * P0 StringCut stores cut bounds as STRING expressions, not JSON numbers.
 * The native runtime resolves variables before parsing them into integer offsets.
 */
export const stringCutConfigSchema: HopConfigSchema = {
  pluginId: "StringCut",
  pluginType: "transform",
  title: "String cut",
  tier: "T1",
  fields: [
    { key: "fields.field", label: "Cut rules", control: "EditableTable", valueType: "object[]",
      columns: [
        { key: "in_stream_name", label: "Input field", valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER" },
        { key: "out_stream_name", label: "Output field", valueType: "string", variable: true },
        { key: "cut_from", label: "Cut from", valueType: "string", variable: true },
        { key: "cut_to", label: "Cut to", valueType: "string", variable: true },
      ] },
  ],
};

/** P0 ReplaceString stores ordered replacement rules in the native fields.field LIST. */
export const replaceStringConfigSchema: HopConfigSchema = {
  pluginId: "ReplaceString",
  pluginType: "transform",
  title: "Replace in string",
  tier: "T1",
  fields: [
    { key: "fields.field", label: "Replacement rules", control: "EditableTable", valueType: "object[]",
      columns: [
        { key: "in_stream_name", label: "Input field", valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER" },
        { key: "out_stream_name", label: "Output field", valueType: "string" },
        { key: "use_regex", label: "Use regular expression", valueType: "boolean" },
        { key: "replace_string", label: "Search text or pattern", valueType: "string" },
        { key: "replace_by_string", label: "Replacement text", valueType: "string" },
        { key: "set_empty_string", label: "Set empty string", valueType: "boolean" },
        { key: "replace_field_by_string", label: "Replacement source field", valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER" },
        { key: "whole_word", label: "Whole word", valueType: "boolean" },
        { key: "case_sensitive", label: "Case sensitive", valueType: "boolean" },
        { key: "is_unicode", label: "Unicode", valueType: "boolean" },
      ] },
  ],
};

/** P0 ExecSql: keep ordered argument references, VFS SQL override and literal-quote mode separate. */
export const execSqlConfigSchema: HopConfigSchema = {
  pluginId: "ExecSql",
  pluginType: "transform",
  title: "Execute SQL script",
  tier: "T1",
  fields: [
    { key: "connection", label: "Connection", control: "MetadataPicker", valueType: "string", metadataType: "DatabaseMeta", required: true, variable: true },
    { key: "sql", label: "SQL", control: "MonacoEditor", valueType: "string", language: "sql", variable: true,
      sqlSourceContext: { inlineSqlPointer: "/sql", fileSqlPointer: "/sql_from_file",
        precedence: "FILE_WHEN_NONEMPTY", inlineReadOnlyWhenFileSelected: true } },
    { key: "sql_from_file", label: "SQL from file", control: "VfsPicker", valueType: "string", vfsMode: "file", variable: true },
    { key: "execute_each_row", label: "Execute for each input row", control: "VariableInput", valueType: "boolean" },
    { key: "single_statement", label: "Execute as single statement", control: "VariableInput", valueType: "boolean" },
    { key: "replace_variables", label: "Replace SQL variables", control: "VariableInput", valueType: "boolean" },
    { key: "set_params", label: "Bind parameters", control: "VariableInput", valueType: "boolean" },
    { key: "quoteString", label: "Quote substituted string arguments", control: "VariableInput", valueType: "boolean",
      activation: { flagPointer: "/execute_each_row", activeWhen: true,
        additionalConditions: [{ flagPointer: "/set_params", activeWhen: false }] } },
    { key: "arguments.argument", label: "Ordered input arguments", control: "EditableTable", valueType: "object[]",
      columns: [{ key: "name", label: "Incoming field", valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER" }] },
    { key: "insert_field", label: "Inserted row count field", control: "VariableInput", valueType: "string" },
    { key: "update_field", label: "Updated row count field", control: "VariableInput", valueType: "string" },
    { key: "delete_field", label: "Deleted row count field", control: "VariableInput", valueType: "string" },
    { key: "read_field", label: "Read row count field", control: "VariableInput", valueType: "string" },
  ],
};


/**
 * P0 DataGrid declares output metadata independently from literal input rows.
 * Native field items have their own nested "field" container; data.line[*].item
 * is an ordered STRING array, not an incoming-row field or a flat scalar.
 * Preserve both LISTs and cell order; browser support for nested arrays is a
 * separate consumer gate, not something this producer schema can assume.
 */
export const dataGridConfigSchema: HopConfigSchema = {
  pluginId: "DataGrid",
  pluginType: "transform",
  title: "Data grid",
  tier: "T1",
  fields: [
    { key: "fields.field", label: "Declared output columns", control: "EditableTable",
      valueType: "object[]", columns: [
        { key: "currency", label: "Currency symbol", valueType: "string" },
        { key: "decimal", label: "Decimal symbol", valueType: "string" },
        { key: "group", label: "Grouping symbol", valueType: "string" },
        { key: "name", label: "Output field name", valueType: "string" },
        { key: "type", label: "Value type", valueType: "string",
          pickerHint: "VALUE_META_TYPE_PICKER" },
        { key: "format", label: "Format", valueType: "string" },
        { key: "length", label: "Length", valueType: "integer" },
        { key: "precision", label: "Precision", valueType: "integer" },
        { key: "set_empty_string", label: "Set empty string", valueType: "boolean" },
      ] },
    { key: "data.line", label: "Literal rows", control: "EditableTable",
      valueType: "object[]",
      description: "Each ordered line contains an ordered item STRING array. Keep the nested cell array and original column positions; do not flatten or coerce literals.",
      columns: [{ key: "item", label: "Ordered literal cells", valueType: "string[]" }] },
  ],
};

/**
 * P0 ConcatFields has an ordered native fields.field LIST and a separate
 * capitalized ConcatFields OBJECT containing target-field settings. Keep
 * native STRING type names and NUMBER lengths; never flatten the OBJECT.
 */
export const concatFieldsConfigSchema: HopConfigSchema = {
  pluginId: "ConcatFields",
  pluginType: "transform",
  title: "Concat fields",
  tier: "T1",
  fields: [
    { key: "fields.field", label: "Source fields", control: "EditableTable", valueType: "object[]",
      columns: [
        { key: "name", label: "Incoming field", valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER" },
        { key: "type", label: "Value type", valueType: "string", pickerHint: "VALUE_META_TYPE_PICKER" },
        { key: "format", label: "Format", valueType: "string" },
        { key: "length", label: "Length", valueType: "integer" },
        { key: "precision", label: "Precision", valueType: "integer" },
        { key: "currency", label: "Currency symbol", valueType: "string" },
        { key: "decimal", label: "Decimal symbol", valueType: "string" },
        { key: "group", label: "Grouping symbol", valueType: "string" },
        { key: "nullif", label: "Null if", valueType: "string" },
        { key: "trim_type", label: "Trim type", valueType: "string" },
      ] },
    { key: "ConcatFields.targetFieldName", label: "Target field", control: "VariableInput",
      valueType: "string", required: true },
    { key: "ConcatFields.targetFieldLength", label: "Target length", control: "VariableInput",
      valueType: "integer" },
    { key: "ConcatFields.removeSelectedFields", label: "Remove selected source fields",
      control: "VariableInput", valueType: "boolean" },
    { key: "separator", label: "Separator", control: "VariableInput", valueType: "string" },
    { key: "enclosure", label: "Enclosure", control: "VariableInput", valueType: "string" },
    { key: "force_enclosure", label: "Force enclosure", control: "VariableInput", valueType: "boolean" },
    { key: "skip_value_empty", label: "Skip empty values", control: "VariableInput", valueType: "boolean" },
  ],
};

/**
 * P0 Http uses two independent ordered native lookup LISTs whose rows both
 * contain name/parameter leaves. Preserve the /lookup/arg and /lookup/header
 * containers and the encrypted password transport; do not flatten by leaf key.
 */

/**
 * P0 SetVariable uses a single ordered native assignment LIST. The scope is a
 * STRING code in pinned Hop VariableItem, not an annotated ENUM; do not
 * synthesize localized options or coerce it to a number. Only field_name
 * references an upstream row column; variable_name is a user-authored target.
 */
export const setVariableConfigSchema: HopConfigSchema = {
  pluginId: "SetVariable",
  pluginType: "transform",
  title: "Set variables",
  tier: "T1",
  fields: [
    { key: "fields.field", label: "Variable assignments", control: "EditableTable", valueType: "object[]",
      columns: [
        { key: "field_name", label: "Incoming field", valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER" },
        { key: "variable_name", label: "Variable name", valueType: "string" },
        { key: "variable_type", label: "Native scope code", valueType: "string" },
        { key: "default_value", label: "Default value", valueType: "string" },
      ] },
    { key: "use_formatting", label: "Use incoming field formatting", control: "VariableInput", valueType: "boolean" },
  ],
};

export const httpConfigSchema: HopConfigSchema = {
  pluginId: "Http",
  pluginType: "transform",
  title: "HTTP client",
  tier: "T1",
  fields: [
    { key: "urlInField", label: "Read URL from incoming row", control: "VariableInput", valueType: "boolean" },
    { key: "url", label: "URL", control: "VariableInput", valueType: "string", variable: true,
      activation: { flagPointer: "/urlInField", activeWhen: false } },
    { key: "urlField", label: "URL input field", control: "VariableInput", valueType: "string",
      pickerHint: "UPSTREAM_FIELD_PICKER",
      activation: { flagPointer: "/urlInField", activeWhen: true } },
    { key: "lookup.arg", label: "Query parameters", control: "EditableTable", valueType: "object[]",
      columns: [
        { key: "name", label: "Incoming field", valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER" },
        { key: "parameter", label: "Query parameter", valueType: "string" },
      ] },
    { key: "lookup.header", label: "Request headers", control: "EditableTable", valueType: "object[]",
      columns: [
        { key: "name", label: "Incoming field", valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER" },
        { key: "parameter", label: "Header name", valueType: "string" },
      ] },
    { key: "encoding", label: "Response encoding", control: "VariableInput", valueType: "string" },
    { key: "socketTimeout", label: "Socket timeout", control: "VariableInput", valueType: "string", variable: true },
    { key: "connectionTimeout", label: "Connection timeout", control: "VariableInput", valueType: "string", variable: true },
    { key: "closeIdleConnectionsTime", label: "Close idle connections after", control: "VariableInput", valueType: "string", variable: true },
    { key: "ignoreSsl", label: "Ignore SSL certificate errors", control: "VariableInput", valueType: "boolean" },
    { key: "proxyHost", label: "Proxy host", control: "VariableInput", valueType: "string", variable: true },
    { key: "proxyPort", label: "Proxy port", control: "VariableInput", valueType: "string", variable: true },
    { key: "httpLogin", label: "HTTP login", control: "VariableInput", valueType: "string", variable: true },
    { key: "httpPassword", label: "HTTP password", control: "VariableInput", valueType: "string", sensitive: true },
    { key: "result.name", label: "Response body field", control: "VariableInput", valueType: "string" },
    { key: "result.code", label: "Status code field", control: "VariableInput", valueType: "string" },
    { key: "result.response_time", label: "Response time field", control: "VariableInput", valueType: "string" },
    { key: "result.response_header", label: "Response headers field", control: "VariableInput", valueType: "string" },
  ],
};

/**
 * First real Metadata contract. DatabaseMeta stores its database implementation under `rdbms`;
 * standard connection fields below are BaseDatabaseMeta metadata properties. Password is marked
 * sensitive so browser/server transport can preserve Hop's encrypted representation.
 */
export const databaseConnectionConfigSchema: HopConfigSchema = {
  pluginId: "DatabaseMeta",
  pluginType: "metadata",
  title: "Database Connection",
  tier: "T1",
  fields: [
    { key: "name", label: "Connection name", control: "VariableInput", valueType: "string", required: true },
    { key: "rdbms.hostname", label: "Host name", control: "VariableInput", valueType: "string", variable: true },
    { key: "rdbms.port", label: "Port", control: "VariableInput", valueType: "string", variable: true },
    { key: "rdbms.databaseName", label: "Database name", control: "VariableInput", valueType: "string", variable: true },
    { key: "rdbms.username", label: "User name", control: "VariableInput", valueType: "string", variable: true },
    { key: "rdbms.password", label: "Password", control: "VariableInput", valueType: "string", sensitive: true },
    { key: "rdbms.manualUrl", label: "JDBC URL", control: "VariableInput", valueType: "string", variable: true },
  ],
};

/**
 * Bind a producer-owned layout to the same native field objects used by the flat T1
 * fallback. Reject missing/duplicate keys rather than silently dropping a Hop property.
 */
function bindLayout(
  schema: HopConfigSchema,
  specs: readonly {
    key: string; label: string;
    groups: readonly { key: string; label: string; keys: readonly string[] }[];
  }[],
): HopConfigTab[] {
  const byKey = new Map(schema.fields.map((field) => [field.key, field]));
  if (byKey.size !== schema.fields.length) throw new Error(`Duplicate native schema key: ${schema.pluginId}`);
  const seen = new Set<string>();
  const tabs = specs.map((tab) => ({
    key: tab.key,
    label: tab.label,
    groups: tab.groups.map((group) => ({
      key: group.key,
      label: group.label,
      fields: group.keys.map((key) => {
        const field = byKey.get(key);
        if (!field || seen.has(key)) throw new Error(`Invalid layout key ${schema.pluginId}:${key}`);
        seen.add(key);
        return field;
      }),
    })),
  }));
  if (seen.size !== byKey.size) throw new Error(`Incomplete native schema layout: ${schema.pluginId}`);
  return tabs;
}

// UI Schema v2 handoff: group without duplicating or rewriting the native Hop wire fields.
// Consumers without tab support continue to use tableInputConfigSchema.fields unchanged.
tableInputConfigSchema.tabs = bindLayout(tableInputConfigSchema, [
  { key: "query", label: "Query", groups: [
    { key: "source", label: "Connection and SQL",
      keys: ["connection", "sql", "sql_from_file", "lookup"] },
    { key: "execution", label: "Parameters and limits",
      keys: ["limit", "execute_each_row", "variables_active", "use_named_parameters"] },
  ] },
  { key: "output", label: "Output fields", groups: [
    { key: "columns", label: "Declared output columns",
      keys: ["specify_fields", "fields.field", "validate_specified_fields"] },
  ] },
]);

// The Select Values stages are ordered: selection/renaming, removal, then metadata
// conversion. Keep all three native LISTs independent and preserve their row order.
// The UI may present stages separately but must never discard inactive/unknown values.
selectValuesConfigSchema.tabs = bindLayout(selectValuesConfigSchema, [
  { key: "select", label: "Select and rename", groups: [
    { key: "selection", label: "Fields to select",
      keys: ["fields.field", "fields.select_unspecified"] },
  ] },
  { key: "remove", label: "Remove", groups: [
    { key: "removal", label: "Fields to remove",
      keys: ["fields.remove"] },
  ] },
  { key: "metadata", label: "Metadata", groups: [
    { key: "conversion", label: "Type and format changes",
      keys: ["fields.meta"] },
  ] },
]);

// SortRows keeps its ordered comparison-field LIST separate from the
// server-local spill configuration. Tabs only organize existing native values.
sortRowsConfigSchema.tabs = bindLayout(sortRowsConfigSchema, [
  { key: "fields", label: "Sort fields", groups: [
    { key: "comparison", label: "Comparison order",
      keys: ["fields.field", "unique_rows"] },
  ] },
  { key: "performance", label: "Execution and spill files", groups: [
    { key: "memory", label: "Memory",
      keys: ["sort_size", "free_memory"] },
    { key: "spill", label: "Temporary files",
      keys: ["directory", "sort_prefix", "compress", "compress_variables"] },
  ] },
]);


// The first Metadata target needs the same T1 layout contract as the three
// priority transforms. Keep native rdbms.* paths and the sensitive password
// descriptor unchanged; grouping is presentation only, not credential decoding.
// Manual JDBC URL remains an independent native field, not a replacement for
// or a destructive toggle over host/database drafts.
databaseConnectionConfigSchema.tabs = bindLayout(databaseConnectionConfigSchema, [
  { key: "identity", label: "Connection identity", groups: [
    { key: "name", label: "Connection name", keys: ["name"] },
  ] },
  { key: "endpoint", label: "Database endpoint", groups: [
    { key: "network", label: "Host and database",
      keys: ["rdbms.hostname", "rdbms.port", "rdbms.databaseName"] },
    { key: "jdbc", label: "Manual JDBC URL", keys: ["rdbms.manualUrl"] },
  ] },
  { key: "credentials", label: "Authentication", groups: [
    { key: "login", label: "Credentials",
      keys: ["rdbms.username", "rdbms.password"] },
  ] },
]);

// CheckSum has a native ordered source-field LIST followed by output and
// formatting options. These tabs share the flat fallback's original objects.
checkSumConfigSchema.tabs = bindLayout(checkSumConfigSchema, [
  { key: "source", label: "Input fields", groups: [
    { key: "fields", label: "Checksum source", keys: ["fields.field"] },
  ] },
  { key: "result", label: "Checksum result", groups: [
    { key: "output", label: "Output column and encoding",
      keys: ["resultfieldName", "checksumtype", "resultType"] },
    { key: "format", label: "Input formatting",
      keys: ["separator", "prefix", "suffix"] },
  ] },
]);

// StringCut is a single native LIST of ordered cut rules; do not invent
// separate root-level cut_from/cut_to properties or coerce expressions.
stringCutConfigSchema.tabs = bindLayout(stringCutConfigSchema, [
  { key: "rules", label: "Cut rules", groups: [
    { key: "fields", label: "Input and output field ranges", keys: ["fields.field"] },
  ] },
]);

// Replacement rules preserve the native LIST order and each Boolean regex flag.
// The two input-field references use native upstream pickers; output aliases stay free text.
replaceStringConfigSchema.tabs = bindLayout(replaceStringConfigSchema, [
  { key: "rules", label: "Replacement rules", groups: [
    { key: "replacements", label: "Search and replacement", keys: ["fields.field"] },
  ] },
]);

// ExecSql uses a VFS SQL file before inline SQL. The quoteString switch only
// affects per-row literal substitution without JDBC parameter binding; never
// erase the inactive Boolean or ordered argument list when changing modes.
execSqlConfigSchema.tabs = bindLayout(execSqlConfigSchema, [
  { key: "sql", label: "SQL source", groups: [
    { key: "source", label: "Connection and SQL", keys: ["connection", "sql", "sql_from_file"] },
  ] },
  { key: "execution", label: "Execution", groups: [
    { key: "options", label: "Execution mode",
      keys: ["execute_each_row", "single_statement", "replace_variables"] },
    { key: "arguments", label: "Parameter binding and input arguments",
      keys: ["set_params", "quoteString", "arguments.argument"] },
  ] },
  { key: "results", label: "Result counters", groups: [
    { key: "output", label: "Optional output fields",
      keys: ["insert_field", "update_field", "delete_field", "read_field"] },
  ] },
]);

// DataGrid has two independent native LIST containers. The declared columns
// are output metadata; literal data.line[*].item cells are nested STRING arrays.
// Tabs must not flatten either structure or infer upstream-field references.
dataGridConfigSchema.tabs = bindLayout(dataGridConfigSchema, [
  { key: "columns", label: "Output columns", groups: [
    { key: "metadata", label: "Declared field types and formats",
      keys: ["fields.field"] },
  ] },
  { key: "rows", label: "Literal data", groups: [
    { key: "values", label: "Ordered data rows", keys: ["data.line"] },
  ] },
]);

// ConcatFields retains the exact capitalized native ConcatFields OBJECT and
// the independent fields.field LIST; layout is only a grouping of those fields.
concatFieldsConfigSchema.tabs = bindLayout(concatFieldsConfigSchema, [
  { key: "source", label: "Input fields", groups: [
    { key: "fields", label: "Ordered source fields", keys: ["fields.field"] },
  ] },
  { key: "target", label: "Output", groups: [
    { key: "output", label: "Target field",
      keys: ["ConcatFields.targetFieldName", "ConcatFields.targetFieldLength",
        "ConcatFields.removeSelectedFields"] },
    { key: "format", label: "Delimited value formatting",
      keys: ["separator", "enclosure", "force_enclosure", "skip_value_empty"] },
  ] },
]);

// Preserve the native ordered variable assignments independently of formatting.
setVariableConfigSchema.tabs = bindLayout(setVariableConfigSchema, [
  { key: "assignments", label: "Variable assignments", groups: [
    { key: "fields", label: "Input fields and target variables", keys: ["fields.field"] },
  ] },
  { key: "formatting", label: "Formatting", groups: [
    { key: "options", label: "Value formatting", keys: ["use_formatting"] },
  ] },
]);

// Http query parameters and headers have identical leaf names but different
// canonical Hop wire containers. Grouping only reuses native field objects.
httpConfigSchema.tabs = bindLayout(httpConfigSchema, [
  { key: "request", label: "Request URL", groups: [
    { key: "url", label: "URL source", keys: ["urlInField", "url", "urlField"] },
  ] },
  { key: "parameters", label: "Request data", groups: [
    { key: "query", label: "Query parameters", keys: ["lookup.arg"] },
    { key: "headers", label: "Request headers", keys: ["lookup.header"] },
  ] },
  { key: "connection", label: "Connection settings", groups: [
    { key: "timeouts", label: "Timeouts",
      keys: ["socketTimeout", "connectionTimeout", "closeIdleConnectionsTime"] },
    { key: "transport", label: "Transport and proxy",
      keys: ["encoding", "ignoreSsl", "proxyHost", "proxyPort"] },
    { key: "credentials", label: "HTTP authentication",
      keys: ["httpLogin", "httpPassword"] },
  ] },
  { key: "response", label: "Response fields", groups: [
    { key: "fields", label: "Output names",
      keys: ["result.name", "result.code", "result.response_time", "result.response_header"] },
  ] },
]);

const configSchemas: readonly HopConfigSchema[] = [
  tableInputConfigSchema,
  selectValuesConfigSchema,
  sortRowsConfigSchema,
  checkSumConfigSchema,
  stringCutConfigSchema,
  replaceStringConfigSchema,
  execSqlConfigSchema,
  concatFieldsConfigSchema,
  dataGridConfigSchema,
  httpConfigSchema,
  setVariableConfigSchema,
  databaseConnectionConfigSchema,
];

/** Resolve the declarative browser schema without coupling callers to individual schema modules. */
export function findConfigSchema(
  pluginType: HopConfigSchema["pluginType"],
  pluginId: string,
): HopConfigSchema | undefined {
  return configSchemas.find(
    (schema) => schema.pluginType === pluginType && schema.pluginId === pluginId,
  );
}

export function listConfigSchemas(): readonly HopConfigSchema[] {
  return configSchemas;
}
