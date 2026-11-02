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
      "transform:CheckSum",
      "transform:StringCut",
      "transform:ReplaceString",
      "transform:ExecSql",
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

test("TableInput SQL source precedence is a machine-readable native wire contract", () => {
  const schema = findConfigSchema("transform", "TableInput");
  assert.ok(schema?.tabs);
  const sql = schema.fields.find(({ key }) => key === "sql");
  const sqlFile = schema.fields.find(({ key }) => key === "sql_from_file");
  assert.deepEqual(sql?.sqlSourceContext, {
    inlineSqlPointer: "/sql",
    fileSqlPointer: "/sql_from_file",
    precedence: "FILE_WHEN_NONEMPTY",
    inlineReadOnlyWhenFileSelected: true,
  }, "native TableInput loads nonempty HopVfs SQL first; T4 must not guess precedence");
  assert.equal(sql?.control, "MonacoEditor");
  assert.equal(sql?.language, "sql");
  assert.equal(sql?.variable, true);
  assert.equal(sql?.readOnly, undefined,
    "inline SQL remains an editable draft when file mode is later disabled");
  assert.equal(sql?.activation, undefined,
    "do not hide or delete the inline SQL when a file is selected");
  assert.equal(sqlFile?.control, "VfsPicker");
  assert.equal(sqlFile?.vfsMode, "file");
  assert.equal(sqlFile?.sqlSourceContext, undefined,
    "the source selection contract belongs to the inline SQL field");
  const layoutSql = schema.tabs.flatMap(tab => tab.groups)
    .flatMap(group => group.fields).find(({ key }) => key === "sql");
  assert.strictEqual(layoutSql, sql,
    "T1 layout must share the same source semantics as flat T0 fallback");
  assert.deepEqual(JSON.parse(JSON.stringify(sql.sqlSourceContext)), sql.sqlSourceContext,
    "the handoff must remain plain JSON without runtime-only objects");
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


test("DatabaseMeta T1 layout preserves native paths and sensitive credential semantics", () => {
  const schema = findConfigSchema("metadata", "DatabaseMeta");
  assert.ok(schema?.tabs);
  assert.deepEqual(schema.tabs.map(({ key }) => key),
    ["identity", "endpoint", "credentials"]);
  const grouped = schema.tabs.flatMap((tab) => tab.groups.flatMap((group) => group.fields));
  assert.deepEqual(grouped.map(({ key }) => key), [
    "name", "rdbms.hostname", "rdbms.port", "rdbms.databaseName",
    "rdbms.manualUrl", "rdbms.username", "rdbms.password",
  ], "do not invent JDBC fields or silently omit native DatabaseMeta properties");
  assert.equal(grouped.length, schema.fields.length);
  assert.equal(new Set(grouped.map(({ key }) => key)).size, schema.fields.length);
  for (const field of grouped) {
    assert.strictEqual(field, schema.fields.find(({ key }) => key === field.key),
      "metadata layout must reuse the flat native field, not clone or rewrite values");
  }
  const credentialGroup = schema.tabs[2].groups[0];
  assert.deepEqual(credentialGroup.fields.map(({ key }) => key),
    ["rdbms.username", "rdbms.password"]);
  const password = credentialGroup.fields[1];
  assert.equal(password.sensitive, true, "grouping must not drop secret transport markers");
  assert.equal(password.valueType, "string");
  assert.equal(password.metadataType, undefined);
  const manualUrl = schema.fields.find(({ key }) => key === "rdbms.manualUrl");
  assert.equal(manualUrl?.variable, true,
    "native manual JDBC URLs may retain Hop variable expressions");
  assert.equal(manualUrl?.activation, undefined,
    "do not discard host/database drafts when a manual JDBC URL is present");
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
  assert.deepEqual(declared?.activation, { flagPointer: "/specify_fields", activeWhen: true });
  assert.deepEqual(validate?.activation, { flagPointer: "/specify_fields", activeWhen: true });
  assert.equal(schema.fields.find(({ key }) => key === "specify_fields")?.activation, undefined);
  assert.match(schema.fields.find(({ key }) => key === "sql_from_file")?.description ?? "",
    /instead of inline SQL/, "native file SQL takes precedence but inline draft must be retained");
});

test("SelectValues layout preserves ordered native stages without duplicating wire fields", () => {
  const schema = findConfigSchema("transform", "SelectValues");
  assert.ok(schema?.tabs);
  assert.deepEqual(schema.tabs.map(({ key }) => key), ["select", "remove", "metadata"]);
  const grouped = schema.tabs.flatMap((tab) => tab.groups.flatMap((group) => group.fields));
  assert.deepEqual(grouped.map(({ key }) => key), [
    "fields.field", "fields.select_unspecified", "fields.remove", "fields.meta",
  ], "select, remove and metadata are distinct native LIST stages");
  assert.equal(new Set(grouped.map(({ key }) => key)).size, schema.fields.length);
  for (const field of grouped) {
    assert.strictEqual(field, schema.fields.find(({ key }) => key === field.key),
      "tabs must not clone or rewrite the original Hop wire field");
  }
  assert.equal(schema.fields.find(({ key }) => key === "fields.meta")?.columns
    ?.find(({ key }) => key === "type")?.pickerHint, "VALUE_META_TYPE_PICKER");
});

test("SelectValues T1 field pickers follow the native Select, Remove and Meta row stages", () => {
  const schema = findConfigSchema("transform", "SelectValues");
  assert.ok(schema?.tabs);
  const selected = schema.fields.find(({ key }) => key === "fields.field");
  const removed = schema.fields.find(({ key }) => key === "fields.remove");
  const metadata = schema.fields.find(({ key }) => key === "fields.meta");
  const selectName = selected?.columns?.find(({ key }) => key === "name");
  const selectRename = selected?.columns?.find(({ key }) => key === "rename");
  const removeName = removed?.columns?.find(({ key }) => key === "name");
  const metaName = metadata?.columns?.find(({ key }) => key === "name");
  const metaRename = metadata?.columns?.find(({ key }) => key === "rename");

  assert.deepEqual(
    [selectName?.pickerHint, selectName?.fieldSourceStage],
    ["UPSTREAM_FIELD_PICKER", undefined],
    "Select reads the original incoming row, not the renamed intermediate schema");
  assert.deepEqual(
    [removeName?.pickerHint, removeName?.fieldSourceStage],
    ["STAGED_FIELD_PICKER", "AFTER_SELECT"],
    "Remove must offer names after Select has renamed them");
  assert.deepEqual(
    [metaName?.pickerHint, metaName?.fieldSourceStage],
    ["STAGED_FIELD_PICKER", "AFTER_REMOVE"],
    "Meta must not offer fields removed by the preceding stage");
  for (const outputName of [selectRename, metaRename]) {
    assert.equal(outputName?.pickerHint, undefined,
      "output aliases are authored names, not incoming-field references");
    assert.equal(outputName?.fieldSourceStage, undefined);
  }
  for (const [key, field] of [
    ["fields.field", selected], ["fields.remove", removed], ["fields.meta", metadata],
  ]) {
    assert.strictEqual(
      schema.tabs.flatMap(tab => tab.groups).flatMap(group => group.fields)
        .find(grouped => grouped.key === key), field,
      "the grouped T1 editor must reuse the exact flat fallback field");
  }
  assert.deepEqual(JSON.parse(JSON.stringify({
    selectName, removeName, metaName,
  })).metaName.fieldSourceStage, "AFTER_REMOVE",
  "staged picker contracts must survive plain JSON transport");
});

test("SortRows layout separates ordered keys from memory and local spill settings", () => {
  const schema = findConfigSchema("transform", "SortRows");
  assert.ok(schema?.tabs);
  assert.deepEqual(schema.tabs.map(({ key }) => key), ["fields", "performance"]);
  const grouped = schema.tabs.flatMap((tab) => tab.groups.flatMap((group) => group.fields));
  assert.deepEqual(grouped.map(({ key }) => key), [
    "fields.field", "unique_rows", "sort_size", "free_memory",
    "directory", "sort_prefix", "compress", "compress_variables",
  ]);
  assert.equal(new Set(grouped.map(({ key }) => key)).size, schema.fields.length);
  for (const field of grouped) {
    assert.strictEqual(field, schema.fields.find(({ key }) => key === field.key),
      "layout must share the original field instance and preserve wire values");
  }
  const spill = schema.tabs[1].groups[1].fields;
  assert.equal(spill.find(({ key }) => key === "directory")?.pickerHint,
    "LOCAL_DIRECTORY_PICKER");
  assert.equal(spill.find(({ key }) => key === "compress_variables")?.variable, true);
});

test("SortRows comparison picker consumes incoming row fields, not output aliases or local spill paths", () => {
  const schema = findConfigSchema("transform", "SortRows");
  assert.ok(schema?.tabs);
  const comparison = schema.fields.find(({ key }) => key === "fields.field");
  assert.equal(comparison?.control, "EditableTable");
  assert.equal(comparison?.valueType, "object[]");
  const name = comparison?.columns?.find(({ key }) => key === "name");
  assert.deepEqual(
    { valueType: name?.valueType, pickerHint: name?.pickerHint, fieldSourceStage: name?.fieldSourceStage },
    { valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER", fieldSourceStage: undefined },
    "native SortRowsField.name resolves in the incoming row, unlike SelectValues staged fields",
  );
  for (const column of comparison?.columns?.filter(({ key }) => key !== "name") ?? []) {
    assert.equal(column.pickerHint, undefined,
      "sort direction, collator settings and presorted flags are not incoming field references");
  }
  assert.strictEqual(schema.tabs[0].groups[0].fields[0], comparison,
    "grouped SortRows layout must expose the same picker as the flat schema");
  assert.equal(JSON.parse(JSON.stringify(name)).pickerHint, "UPSTREAM_FIELD_PICKER",
    "the producer-owned picker hint must survive browser JSON transport");
  assert.equal(schema.fields.find(({ key }) => key === "directory")?.pickerHint,
    "LOCAL_DIRECTORY_PICKER", "temporary directories are not row field references");
  const tableInputOutputName = findConfigSchema("transform", "TableInput")
    ?.fields.find(({ key }) => key === "fields.field")
    ?.columns?.find(({ key }) => key === "name");
  assert.equal(tableInputOutputName?.pickerHint, undefined,
    "declared JDBC output names must remain authored names, not incoming field picks");
});

test("CheckSum T1 preserves native grouped field list and code-backed enum wire values", () => {
  const schema = findConfigSchema("transform", "CheckSum");
  assert.ok(schema?.tabs);
  assert.deepEqual(schema.fields.map(({ key }) => key), [
    "fields.field", "resultfieldName", "checksumtype", "resultType",
    "separator", "prefix", "suffix",
  ], "match the pinned CheckSumMeta @HopMetadataProperty keys exactly");
  assert.deepEqual(schema.tabs.map(({ key }) => key), ["source", "result"]);
  const grouped = schema.tabs.flatMap(tab => tab.groups.flatMap(group => group.fields));
  assert.deepEqual(grouped.map(({ key }) => key), schema.fields.map(({ key }) => key));
  for (const field of grouped) {
    assert.strictEqual(field, schema.fields.find(({ key }) => key === field.key),
      "T1 layout must reuse native flat fields, not clone or alter wire properties");
  }
  const selected = schema.fields[0];
  assert.equal(selected.control, "EditableTable");
  assert.equal(selected.valueType, "object[]");
  assert.deepEqual(selected.columns, [
    { key: "name", label: "Input field", valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER" },
  ], "CheckSum.Field.name is the only native list item property");
  for (const key of ["checksumtype", "resultType"]) {
    const field = schema.fields.find(f => f.key === key);
    assert.equal(field?.valueType, "string", "native storeWithCode ENUM values are strings");
    assert.equal(field?.options, undefined,
      "do not freeze installed Hop enum options or localized labels into a static T1 schema");
    assert.match(field?.description ?? "", /code|ENUM/i);
  }
  for (const key of ["separator", "prefix", "suffix", "resultfieldName"]) {
    assert.equal(schema.fields.find(f => f.key === key)?.variable, true,
      "native expressions must be preserved for server-side resolution");
  }
  assert.equal(JSON.parse(JSON.stringify(schema)).tabs[0].groups[0].fields[0].key,
    "fields.field", "browser JSON handoff must retain the native LIST descriptor key");
});

test("StringCut T1 keeps cut bounds as variable-capable strings and output aliases free", () => {
  const schema = findConfigSchema("transform", "StringCut");
  assert.ok(schema?.tabs);
  assert.deepEqual(schema.fields.map(({ key }) => key), ["fields.field"]);
  assert.deepEqual(schema.tabs.map(({ key }) => key), ["rules"]);
  assert.strictEqual(schema.tabs[0].groups[0].fields[0], schema.fields[0]);
  const rules = schema.fields[0];
  assert.equal(rules.control, "EditableTable");
  assert.equal(rules.valueType, "object[]");
  assert.deepEqual(rules.columns?.map(({ key }) => key), [
    "in_stream_name", "out_stream_name", "cut_from", "cut_to",
  ], "match the pinned StringCutField annotation keys without inventing scalar roots");
  const input = rules.columns.find(({ key }) => key === "in_stream_name");
  const output = rules.columns.find(({ key }) => key === "out_stream_name");
  assert.equal(input.pickerHint, "UPSTREAM_FIELD_PICKER");
  assert.equal(output.pickerHint, undefined,
    "the output alias is an authored field name, not an upstream reference");
  for (const key of ["cut_from", "cut_to"]) {
    const column = rules.columns.find(c => c.key === key);
    assert.equal(column.valueType, "string",
      "StringCutField cut boundaries are native STRING values, not JSON numbers");
    assert.equal(column.variable, true,
      "keep ${} expressions and leading zeros until the native runtime resolves them");
  }
  assert.deepEqual(JSON.parse(JSON.stringify(rules.columns)).map(({ key }) => key),
    ["in_stream_name", "out_stream_name", "cut_from", "cut_to"]);
});

test("ReplaceString T1 matches native ordered rule columns and Boolean flags", () => {
  const schema = findConfigSchema("transform", "ReplaceString");
  assert.ok(schema?.tabs);
  assert.deepEqual(schema.fields.map(({ key }) => key), ["fields.field"]);
  assert.deepEqual(schema.tabs.map(({ key }) => key), ["rules"]);
  assert.strictEqual(schema.tabs[0].groups[0].fields[0], schema.fields[0]);
  const rules = schema.fields[0];
  assert.equal(rules.control, "EditableTable");
  assert.equal(rules.valueType, "object[]");
  assert.deepEqual(rules.columns.map(({ key }) => key), [
    "in_stream_name", "out_stream_name", "use_regex", "replace_string",
    "replace_by_string", "set_empty_string", "replace_field_by_string",
    "whole_word", "case_sensitive", "is_unicode",
  ]);
  for (const key of ["use_regex", "set_empty_string", "whole_word", "case_sensitive", "is_unicode"]) {
    assert.equal(rules.columns.find(c => c.key === key).valueType, "boolean",
      "native ReplaceStringField flags must remain JSON Booleans");
  }
  for (const key of ["in_stream_name", "replace_field_by_string"]) {
    assert.equal(rules.columns.find(c => c.key === key).pickerHint, "UPSTREAM_FIELD_PICKER");
  }
  for (const key of ["out_stream_name", "replace_string", "replace_by_string"]) {
    assert.equal(rules.columns.find(c => c.key === key).pickerHint, undefined,
      "output aliases and literal replacement text are not upstream references");
  }
  assert.deepEqual(JSON.parse(JSON.stringify(rules.columns)).map(c => c.key),
    rules.columns.map(c => c.key), "browser transport preserves ordered native columns");
});

test("ExecSql T1 preserves native SQL file precedence, indexed arguments and two-flag quote activation", () => {
  const schema = findConfigSchema("transform", "ExecSql");
  assert.ok(schema?.tabs);
  assert.deepEqual(schema.fields.map(field => field.key), [
    "connection", "sql", "sql_from_file", "execute_each_row", "single_statement",
    "replace_variables", "set_params", "quoteString", "arguments.argument",
    "insert_field", "update_field", "delete_field", "read_field",
  ]);
  assert.deepEqual(schema.tabs.map(tab => tab.key), ["sql", "execution", "results"]);
  const grouped = schema.tabs.flatMap(tab => tab.groups.flatMap(group => group.fields));
  assert.equal(grouped.length, schema.fields.length);
  for (const field of grouped) {
    assert.strictEqual(field, schema.fields.find(item => item.key === field.key),
      "tabs must reuse the native wire fields, not clone them");
  }
  assert.deepEqual(schema.fields.find(field => field.key === "sql")?.sqlSourceContext, {
    inlineSqlPointer: "/sql", fileSqlPointer: "/sql_from_file",
    precedence: "FILE_WHEN_NONEMPTY", inlineReadOnlyWhenFileSelected: true,
  });
  assert.equal(schema.fields.find(field => field.key === "sql_from_file")?.control, "VfsPicker");
  assert.deepEqual(schema.fields.find(field => field.key === "quoteString")?.activation, {
    flagPointer: "/execute_each_row", activeWhen: true,
    additionalConditions: [{ flagPointer: "/set_params", activeWhen: false }],
  });
  const argumentsList = schema.fields.find(field => field.key === "arguments.argument");
  assert.equal(argumentsList?.valueType, "object[]");
  assert.deepEqual(argumentsList?.columns, [
    { key: "name", label: "Incoming field", valueType: "string", pickerHint: "UPSTREAM_FIELD_PICKER" },
  ]);
  for (const name of ["insert_field", "update_field", "delete_field", "read_field"]) {
    assert.equal(schema.fields.find(field => field.key === name)?.pickerHint, undefined,
      "result counters are output names, not upstream field references");
  }
  assert.deepEqual(JSON.parse(JSON.stringify(schema)).fields.find(field =>
    field.key === "quoteString").activation.additionalConditions,
    [{ flagPointer: "/set_params", activeWhen: false }],
    "both activation conditions must survive JSON transport");
});
