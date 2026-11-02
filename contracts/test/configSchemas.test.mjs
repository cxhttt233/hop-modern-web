// V4 contract regression: this file intentionally validates only browser/server transport shape.
import assert from "node:assert/strict";
import test from "node:test";
import { findConfigSchema, listConfigSchemas } from "../dist/index.js";

test("registry exposes the first real config targets", () => {
  assert.deepEqual(
    listConfigSchemas().map(({ pluginType, pluginId }) => `${pluginType}:${pluginId}`),
    [
      "transform:TableInput",
      "transform:SelectValues",
      "transform:SortRows",
      "metadata:DatabaseMeta",
    ],
  );
});

test("Table Input uses a metadata picker without inventing storeWithName semantics", () => {
  const schema = findConfigSchema("transform", "TableInput");
  assert.ok(schema);
  const connection = schema.fields.find(({ key }) => key === "connection");
  assert.deepEqual(
    {
      control: connection?.control,
      metadataType: connection?.metadataType,
      storeWithName: connection?.storeWithName,
    },
    { control: "MetadataPicker", metadataType: "DatabaseMeta", storeWithName: undefined },
  );
  const lookup = schema.fields.find(({ key }) => key === "lookup");
  assert.equal(lookup?.valueType, "string");
  assert.equal(lookup?.pickerHint, "INFO_STREAM_TRANSFORM_PICKER",
    "TableInput.lookup selects a pipeline INFO transform, not a database connection");
  assert.equal(lookup?.control, "VariableInput",
    "until React implements the picker, the native transform name remains editable");
  assert.equal(lookup?.metadataType, undefined);
  assert.equal(lookup?.variable, undefined,
    "the INFO transform name is literal, not a variable expression");
  assert.equal(schema.fields.find(({ key }) => key === "sql")?.control, "MonacoEditor");
  assert.equal(schema.fields.find(({ key }) => key === "sql_from_file")?.control, "VfsPicker");
  assert.equal(schema.fields.find(({ key }) => key === "fields.field")?.control, "EditableTable");
  const outputFields = schema.fields.find(({ key }) => key === "fields.field");
  assert.equal(outputFields?.columns?.find(({ key }) => key === "type")?.valueType, "integer",
    "TableInputField.type is an int on ConfigJsonSerializer wire; do not send a type-name string");
  assert.deepEqual(outputFields?.columns?.map(({ key }) => key),
    ["name", "type", "format", "length", "precision"],
    "TableInputField.format is a separate native conversion mask, not a type code");
  assert.equal(outputFields?.columns?.find(({ key }) => key === "format")?.valueType, "string");

});

test("SortRows spill directory is server-local, not a remote HopVfs picker", () => {
  const sort = findConfigSchema("transform", "SortRows");
  const table = findConfigSchema("transform", "TableInput");
  assert.ok(sort && table);
  const directory = sort.fields.find(({ key }) => key === "directory");
  assert.deepEqual(
    { control: directory?.control, valueType: directory?.valueType,
      pickerHint: directory?.pickerHint, variable: directory?.variable,
      vfsMode: directory?.vfsMode },
    { control: "VariableInput", valueType: "string",
      pickerHint: "LOCAL_DIRECTORY_PICKER", variable: true,
      vfsMode: undefined },
    "SortRows uses java.io.File for temporary spill files; never advertise remote VFS");
  assert.equal(table.fields.find(({ key }) => key === "sql_from_file")?.control,
    "VfsPicker", "TableInput SQL files still use HopVfs and must not be narrowed");
  assert.equal(sort.fields.find(({ key }) => key === "sort_prefix")?.pickerHint,
    undefined, "local-directory semantics apply only to the directory field");
});

test("Database Connection marks password transport as sensitive", () => {
  const schema = findConfigSchema("metadata", "DatabaseMeta");
  assert.ok(schema);
  const password = schema.fields.find(({ key }) => key === "rdbms.password");
  assert.equal(password?.sensitive, true);
});

test("typed table picker hints match native Hop wire formats", () => {
  const table = findConfigSchema("transform", "TableInput");
  const select = findConfigSchema("transform", "SelectValues");
  assert.ok(table && select);
  const tableType = table.fields.find(({ key }) => key === "fields.field")
    ?.columns?.find(({ key }) => key === "type");
  const selectType = select.fields.find(({ key }) => key === "fields.meta")
    ?.columns?.find(({ key }) => key === "type");
  assert.deepEqual(
    { valueType: tableType?.valueType, pickerHint: tableType?.pickerHint },
    { valueType: "integer", pickerHint: "VALUE_META_CODE_PICKER" },
    "TableInputField.type uses native numeric codes, not SelectValues type names");
  assert.deepEqual(
    { valueType: selectType?.valueType, pickerHint: selectType?.pickerHint },
    { valueType: "string", pickerHint: "VALUE_META_TYPE_PICKER" },
    "SelectMetadataChange.type uses the native value-meta plugin name");
  assert.equal(tableType?.options, undefined);
  assert.equal(selectType?.options, undefined,
    "registered Hop type options come from the runtime descriptor, not a stale static list");
});

test("SelectValues metadata table covers every native SelectMetadataChange wire property", () => {
  const schema = findConfigSchema("transform", "SelectValues");
  assert.ok(schema);
  const meta = schema.fields.find(({ key }) => key === "fields.meta");
  assert.equal(meta?.valueType, "object[]");
  assert.deepEqual(meta?.columns?.map(({ key }) => key), [
    "name", "rename", "type", "length", "precision", "storage_type",
    "conversion_mask", "date_format_lenient", "date_format_locale",
    "date_format_timezone", "lenient_string_to_number", "decimal_symbol",
    "grouping_symbol", "currency_symbol", "encoding", "roundingType",
  ], "do not omit native advanced fields when rendering or rewriting an editable row");
  for (const key of ["date_format_lenient", "lenient_string_to_number"]) {
    assert.equal(meta?.columns?.find((column) => column.key === key)?.valueType, "boolean",
      `${key} is a native BOOLEAN, not a text value`);
  }
  for (const key of ["storage_type", "roundingType"]) {
    assert.equal(meta?.columns?.find((column) => column.key === key)?.valueType, "string",
      `${key} is a native STRING; preserve unknown historical values`);
  }
});

test("TableInput layout preserves every native field exactly once and reuses flat fallback", () => {
  const schema = findConfigSchema("transform", "TableInput");
  assert.ok(schema?.tabs);
  assert.deepEqual(schema.tabs.map(({ key }) => key), ["query", "output"]);
  const grouped = schema.tabs.flatMap((tab) => tab.groups.flatMap((group) => group.fields));
  assert.deepEqual(grouped.map(({ key }) => key), [
    "connection", "sql", "sql_from_file", "lookup",
    "limit", "execute_each_row", "variables_active", "use_named_parameters",
    "specify_fields", "fields.field", "validate_specified_fields",
  ]);
  assert.equal(new Set(grouped.map(({ key }) => key)).size, schema.fields.length);
  for (const field of grouped) {
    assert.strictEqual(field, schema.fields.find(({ key }) => key === field.key),
      "layout must reuse the same native field descriptor as the flat fallback");
  }
  const declared = schema.fields.find(({ key }) => key === "fields.field");
  const validate = schema.fields.find(({ key }) => key === "validate_specified_fields");
  assert.deepEqual(declared?.activation, { flagKey: "specify_fields", activeWhen: true });
  assert.deepEqual(validate?.activation, { flagKey: "specify_fields", activeWhen: true });
  assert.equal(schema.fields.find(({ key }) => key === "specify_fields")?.activation, undefined);
  assert.match(schema.fields.find(({ key }) => key === "sql_from_file")?.description ?? "",
    /instead of inline SQL/, "native file SQL takes precedence but inline draft must be retained");
});
