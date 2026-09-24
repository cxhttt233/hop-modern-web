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
    {
      key: "connection",
      label: "Connection",
      control: "MetadataPicker",
      valueType: "string",
      metadataType: "DatabaseMeta",
      storeWithName: true,
      required: true,
      variable: true,
    },
    {
      key: "sql",
      label: "SQL",
      control: "MonacoEditor",
      valueType: "string",
      language: "sql",
      variable: true,
    },
    {
      key: "sql_from_file",
      label: "SQL from file",
      control: "VfsPicker",
      valueType: "string",
      vfsMode: "file",
      variable: true,
    },
    {
      key: "limit",
      label: "Row limit",
      control: "VariableInput",
      valueType: "string",
      variable: true,
    },
    {
      key: "execute_each_row",
      label: "Execute for each row",
      control: "VariableInput",
      valueType: "boolean",
    },
    {
      key: "variables_active",
      label: "Replace variables",
      control: "VariableInput",
      valueType: "boolean",
    },
    {
      key: "use_named_parameters",
      label: "Use named parameters",
      control: "VariableInput",
      valueType: "boolean",
    },
    {
      key: "specify_fields",
      label: "Specify output fields",
      control: "VariableInput",
      valueType: "boolean",
    },
    {
      key: "validate_specified_fields",
      label: "Validate specified fields",
      control: "VariableInput",
      valueType: "boolean",
    },
    {
      key: "fields",
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
