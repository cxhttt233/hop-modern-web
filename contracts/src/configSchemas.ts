import type { HopConfigSchema } from "./index.js";

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
    { key: "sql", label: "SQL", control: "MonacoEditor", valueType: "string", language: "sql", variable: true },
    { key: "sql_from_file", label: "SQL from file", control: "VfsPicker", valueType: "string", vfsMode: "file", variable: true },
    { key: "limit", label: "Row limit", control: "VariableInput", valueType: "string", variable: true },
    { key: "execute_each_row", label: "Execute for each row", control: "VariableInput", valueType: "boolean" },
    { key: "variables_active", label: "Replace variables", control: "VariableInput", valueType: "boolean" },
    { key: "use_named_parameters", label: "Use named parameters", control: "VariableInput", valueType: "boolean" },
    { key: "specify_fields", label: "Specify output fields", control: "VariableInput", valueType: "boolean" },
    { key: "validate_specified_fields", label: "Validate specified fields", control: "VariableInput", valueType: "boolean" },
    {
      key: "fields.field",
      label: "Output fields",
      control: "EditableTable",
      valueType: "object[]",
      columns: [
        { key: "name", label: "Name", valueType: "string" },
        { key: "type", label: "Type", valueType: "string" },
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
        { key: "name", label: "Field", valueType: "string" },
        { key: "rename", label: "Rename to", valueType: "string" },
        { key: "length", label: "Length", valueType: "integer" },
        { key: "precision", label: "Precision", valueType: "integer" },
      ],
    },
    { key: "fields.select_unspecified", label: "Include unspecified fields", control: "VariableInput", valueType: "boolean" },
    { key: "fields.remove", label: "Remove fields", control: "EditableTable", valueType: "object[]", columns: [{ key: "name", label: "Field", valueType: "string" }] },
    {
      key: "fields.meta",
      label: "Metadata changes",
      control: "EditableTable",
      valueType: "object[]",
      columns: [
        { key: "name", label: "Field", valueType: "string" },
        { key: "rename", label: "Rename to", valueType: "string" },
        { key: "type", label: "Type", valueType: "string" },
        { key: "length", label: "Length", valueType: "integer" },
        { key: "precision", label: "Precision", valueType: "integer" },
        { key: "conversion_mask", label: "Format", valueType: "string" },
        { key: "date_format_locale", label: "Date locale", valueType: "string" },
        { key: "date_format_timezone", label: "Date time zone", valueType: "string" },
        { key: "decimal_symbol", label: "Decimal symbol", valueType: "string" },
        { key: "grouping_symbol", label: "Grouping symbol", valueType: "string" },
        { key: "currency_symbol", label: "Currency symbol", valueType: "string" },
        { key: "encoding", label: "Encoding", valueType: "string" },
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
        { key: "name", label: "Field", valueType: "string" },
        { key: "ascending", label: "Ascending", valueType: "boolean" },
        { key: "case_sensitive", label: "Case sensitive", valueType: "boolean" },
        { key: "collator_enabled", label: "Use collator", valueType: "boolean" },
        { key: "collator_strength", label: "Collator strength", valueType: "integer" },
        { key: "presorted", label: "Presorted", valueType: "boolean" },
      ],
    },
    { key: "directory", label: "Temporary directory", control: "VfsPicker", valueType: "string", vfsMode: "directory", variable: true },
    { key: "sort_prefix", label: "Temporary file prefix", control: "VariableInput", valueType: "string", variable: true },
    { key: "sort_size", label: "Sort size", control: "VariableInput", valueType: "string", variable: true },
    { key: "free_memory", label: "Free memory threshold", control: "VariableInput", valueType: "string", variable: true },
    { key: "unique_rows", label: "Only pass unique rows", control: "VariableInput", valueType: "boolean" },
    { key: "compress", label: "Compress temporary files", control: "VariableInput", valueType: "boolean" },
    { key: "compress_variables", label: "Compression variable", control: "VariableInput", valueType: "string", variable: true },
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

const configSchemas: readonly HopConfigSchema[] = [
  tableInputConfigSchema,
  selectValuesConfigSchema,
  sortRowsConfigSchema,
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
