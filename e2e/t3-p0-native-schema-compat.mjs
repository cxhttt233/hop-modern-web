// Formal P0 CI: compare new T1 schemas with the actual installed Hop plugin
// descriptors, not a second hardcoded copy of native metadata annotations.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { findConfigSchema } from "../contracts/dist/index.js";

const sourceRows = JSON.parse(await readFile(
  new URL("../docs/p0-transform-descriptors.generated.json", import.meta.url), "utf8"));
const byId = new Map(sourceRows.map(row => [row.id, row.descriptor]));

for (const pluginId of ["CheckSum", "StringCut", "ReplaceString"]) {
  const descriptor = byId.get(pluginId);
  const schema = findConfigSchema("transform", pluginId);
  assert.ok(descriptor?.properties, `${pluginId}: installed native descriptor missing`);
  assert.ok(schema?.tabs, `${pluginId}: production T1 layout missing`);
  const native = new Map(descriptor.properties.map(property => [
    property.groupKey ? `${property.groupKey}.${property.key}` : property.key,
    property,
  ]));
  assert.deepEqual([...schema.fields.map(field => field.key)].sort(),
    [...native.keys()].sort(), `${pluginId}: T1 must cover every native root property`);

  for (const field of schema.fields) {
    const property = native.get(field.key);
    assert.ok(property, `${pluginId}.${field.key}: unknown native property`);
    if (property.shape === "LIST") {
      assert.equal(field.control, "EditableTable");
      assert.equal(field.valueType, "object[]");
      const columns = field.columns ?? [];
      const children = new Map((property.elementProperties ?? [])
        .map(child => [child.key, child]));
      assert.deepEqual(columns.map(column => column.key).sort(),
        [...children.keys()].sort(),
        `${pluginId}.${field.key}: T1 list columns must match native list item properties`);
      for (const column of columns) {
        const child = children.get(column.key);
        if (child.shape === "BOOLEAN") {
          assert.equal(column.valueType, "boolean",
            `${pluginId}.${field.key}.${column.key}: native Boolean wire type mismatch`);
          assert.equal(column.pickerHint, undefined);
        } else {
          assert.equal(child.shape, "STRING",
            `${pluginId}.${field.key}.${column.key}: expected native STRING or BOOLEAN`);
          assert.equal(column.valueType, "string");
          assert.equal(column.pickerHint,
            child.widgetHint === "UPSTREAM_FIELD_PICKER" ? "UPSTREAM_FIELD_PICKER" : undefined,
            `${pluginId}.${field.key}.${column.key}: native source picker mismatch`);
        }
      }
    } else if (property.shape === "ENUM") {
      assert.equal(field.valueType, "string",
        `${pluginId}.${field.key}: preserve code-backed enum strings`);
      assert.equal(property.storeWithCode, true);
      assert.ok(property.options?.length > 0);
      assert.ok(property.options.every(option => typeof option.value === "string"));
      assert.equal(field.options, undefined,
        "runtime plugin options must not be frozen into the T1 schema");
    } else {
      assert.equal(property.shape, "STRING",
        `${pluginId}.${field.key}: unexpected native scalar type`);
      assert.equal(field.valueType, "string");
    }
  }
  const layout = schema.tabs.flatMap(tab => tab.groups.flatMap(group => group.fields));
  assert.equal(layout.length, schema.fields.length);
  for (const field of layout) {
    assert.strictEqual(field, schema.fields.find(candidate => candidate.key === field.key),
      `${pluginId}: grouped and flat native schema must share field objects`);
  }
}
console.log("PROD_P0_T1_NATIVE_SCHEMA_COMPAT=PASS");


/**
 * Priority T1 native coverage: unlike the simple root LIST transforms above,
 * SelectValues nests three independently ordered lists inside a native OBJECT.
 * Validate those native JSON paths, typed columns, source stages and conditional
 * fields against the installed Hop descriptors. A green standalone TypeScript
 * schema test cannot prove that the pinned Java metadata still matches.
 */
function flattenNativeProperties(properties, parent = "") {
  const entries = [];
  for (const property of properties) {
    const key = [parent, property.groupKey, property.key].filter(Boolean).join(".");
    if (property.shape === "OBJECT" && property.children?.length) {
      entries.push(...flattenNativeProperties(property.children, key));
    } else {
      entries.push([key, property]);
    }
  }
  return entries;
}

function assertNativeValueType(valueType, property, context) {
  switch (property.shape) {
    case "STRING":
      assert.equal(valueType, "string", context);
      break;
    case "BOOLEAN":
      assert.equal(valueType, "boolean", context);
      break;
    case "NUMBER":
      assert.ok(["integer", "number"].includes(valueType), context);
      break;
    case "ENUM":
      assert.equal(valueType, "string", context);
      break;
    default:
      assert.fail(context + ": unsupported native shape " + property.shape);
  }
}

const nativePickerHints = new Set([
  "UPSTREAM_FIELD_PICKER", "STAGED_FIELD_PICKER",
  "VALUE_META_CODE_PICKER", "VALUE_META_TYPE_PICKER",
]);
// SortRows is installed as a non-P0 fixture but is not emitted in the 20-plugin P0 catalog.
// Its native descriptor/wire checks remain covered by formal Java JUnit.
for (const pluginId of ["TableInput", "SelectValues"]) {
  const descriptor = byId.get(pluginId);
  const schema = findConfigSchema("transform", pluginId);
  assert.ok(descriptor?.properties, pluginId + ": installed native descriptor missing");
  assert.ok(schema?.tabs, pluginId + ": T1 layout missing");

  const nativeEntries = flattenNativeProperties(descriptor.properties);
  const native = new Map(nativeEntries);
  assert.equal(native.size, nativeEntries.length, pluginId + ": duplicate native paths");
  assert.deepEqual(schema.fields.map(field => field.key).sort(), [...native.keys()].sort(),
    pluginId + ": T1 paths must match all native scalar/LIST properties");

  for (const field of schema.fields) {
    const property = native.get(field.key);
    const context = pluginId + "." + field.key;
    assert.ok(property, context + ": native property missing");
    if (property.shape === "LIST") {
      assert.equal(field.control, "EditableTable", context);
      assert.equal(field.valueType, "object[]", context);
      const nativeColumns = property.elementProperties ?? [];
      assert.deepEqual((field.columns ?? []).map(column => column.key).sort(),
        nativeColumns.map(column => column.key).sort(),
        context + ": native LIST column coverage");
      const byColumn = new Map(nativeColumns.map(column => [column.key, column]));
      for (const column of field.columns ?? []) {
        const child = byColumn.get(column.key);
        const columnContext = context + "." + column.key;
        assertNativeValueType(column.valueType, child, columnContext);
        if (nativePickerHints.has(child.widgetHint) || column.pickerHint) {
          assert.equal(column.pickerHint, child.widgetHint, columnContext + ": native picker");
        }
        if (child.fieldSourceStage != null || column.fieldSourceStage != null) {
          assert.equal(column.fieldSourceStage, child.fieldSourceStage,
            columnContext + ": native staged field source");
        }
      }
      const listPointer = "/" + field.key.replaceAll(".", "/");
      const wire = descriptor.wireLists?.find(entry => entry.listPointer === listPointer);
      assert.ok(wire, context + ": canonical native wire LIST pointer missing");
      assert.equal(wire.itemPointerTemplate, listPointer + "/{index}",
        context + ": indexed JSON Pointer template");
    } else {
      assertNativeValueType(field.valueType, property, context);
      if (field.activation) {
        assert.equal(property.activationContext?.flagPointer, field.activation.flagPointer,
          context + ": native activation flag path");
        assert.equal(property.activationContext?.activeWhen, field.activation.activeWhen,
          context + ": native activation Boolean");
      }
      if (field.sqlSourceContext) {
        const source = property.sqlSourceContext;
        assert.ok(source, context + ": native SQL source contract missing");
        for (const [key, value] of Object.entries(field.sqlSourceContext)) {
          assert.equal(source[key], value, context + ": native SQL " + key);
        }
      }
    }
  }

  const grouped = schema.tabs.flatMap(tab => tab.groups.flatMap(group => group.fields));
  assert.equal(grouped.length, schema.fields.length, pluginId + ": layout field count");
  for (const field of grouped) {
    assert.strictEqual(field, schema.fields.find(candidate => candidate.key === field.key),
      pluginId + ": tabs must reuse the original T1 native fields");
  }
  if (pluginId === "TableInput") {
    assert.equal(native.get("connection").widgetHint, "METADATA_PICKER");
    assert.equal(native.get("sql_from_file").widgetHint, "VFS_PICKER");
    assert.equal(native.get("lookup").widgetHint, "INFO_STREAM_TRANSFORM_PICKER");
    assert.equal(schema.fields.find(field => field.key === "sql_from_file").control, "VfsPicker");
  }
}
console.log("PROD_P0_T1_NATIVE_PRIORITY_SCHEMA_COMPAT=PASS");

/** ExecSql has an ordered native arguments LIST and two independent SQL sources. */
{
  const pluginId = "ExecSql";
  const descriptor = byId.get(pluginId);
  const schema = findConfigSchema("transform", pluginId);
  assert.ok(descriptor?.properties, "ExecSql installed native descriptor missing");
  assert.ok(schema?.tabs, "ExecSql production T1 schema missing");
  const entries = flattenNativeProperties(descriptor.properties);
  const native = new Map(entries);
  assert.equal(native.size, entries.length, "ExecSql native keys must be unique");
  assert.deepEqual(schema.fields.map(field => field.key).sort(), [...native.keys()].sort(),
    "ExecSql T1 must cover all native properties without changing their wire names");
  for (const field of schema.fields) {
    const property = native.get(field.key);
    const context = "ExecSql." + field.key;
    assert.ok(property, context + ": missing native property");
    if (property.shape === "LIST") {
      assert.equal(field.control, "EditableTable", context);
      assert.equal(field.valueType, "object[]", context);
      assert.deepEqual((field.columns ?? []).map(column => column.key),
        (property.elementProperties ?? []).map(column => column.key), context + ": list columns");
      for (const column of field.columns ?? []) {
        const child = property.elementProperties.find(item => item.key === column.key);
        assertNativeValueType(column.valueType, child, context + "." + column.key);
        assert.equal(column.pickerHint, child.widgetHint, context + ": input field picker");
      }
    } else {
      assertNativeValueType(field.valueType, property, context);
    }
    if (field.activation) {
      assert.deepEqual(property.activationContext, field.activation,
        context + ": native multi-flag activation");
    }
    if (field.sqlSourceContext) {
      assert.deepEqual(property.sqlSourceContext, field.sqlSourceContext,
        context + ": native SQL precedence");
    }
  }
  const argumentsWire = descriptor.wireLists?.find(entry =>
    entry.listPointer === "/arguments/argument");
  assert.ok(argumentsWire, "ExecSql native ordered arguments pointer missing");
  assert.equal(argumentsWire.itemPointerTemplate, "/arguments/argument/{index}");
  assert.equal(schema.fields.find(field => field.key === "connection")?.metadataType, "DatabaseMeta");
  assert.equal(native.get("connection").widgetHint, "METADATA_PICKER");
  assert.equal(native.get("sql_from_file").widgetHint, "VFS_PICKER");
  assert.equal(native.get("sql").textEditorHint, "SQL");
  for (const key of ["insert_field", "update_field", "delete_field", "read_field"]) {
    assert.equal(native.get(key).widgetHint, "NONE", key + " must remain an output name");
  }
  const grouped = schema.tabs.flatMap(tab => tab.groups.flatMap(group => group.fields));
  assert.equal(grouped.length, schema.fields.length);
  for (const field of grouped) {
    assert.strictEqual(field, schema.fields.find(item => item.key === field.key));
  }
}
console.log("PROD_P0_T1_EXECSQL_NATIVE_SCHEMA_COMPAT=PASS");

/**
 * Http has two independent native LISTs nested under lookup, both using the
 * same name/parameter leaves. Compare against the installed plugin descriptor
 * so a flat T1 schema cannot silently collapse query parameters and headers.
 */
{
  const pluginId = "Http";
  const descriptor = byId.get(pluginId);
  const schema = findConfigSchema("transform", pluginId);
  assert.ok(descriptor?.properties, "Http installed native descriptor missing");
  assert.ok(schema?.tabs, "Http production T1 schema missing");
  const entries = flattenNativeProperties(descriptor.properties);
  const native = new Map(entries);
  assert.equal(native.size, entries.length, "Http native descriptor paths must be unique");
  assert.deepEqual(schema.fields.map(field => field.key).sort(), [...native.keys()].sort(),
    "Http T1 must cover all 18 native leaf paths including nested results");

  for (const field of schema.fields) {
    const property = native.get(field.key);
    const context = "Http." + field.key;
    assert.ok(property, context + ": native property missing");
    if (property.shape === "LIST") {
      assert.equal(field.control, "EditableTable", context);
      assert.equal(field.valueType, "object[]", context);
      const nativeColumns = property.elementProperties ?? [];
      assert.deepEqual((field.columns ?? []).map(column => column.key),
        nativeColumns.map(column => column.key), context + ": native list columns");
      for (const column of field.columns ?? []) {
        const child = nativeColumns.find(item => item.key === column.key);
        assert.ok(child, context + "." + column.key + ": native column missing");
        assertNativeValueType(column.valueType, child, context + "." + column.key);
        assert.equal(column.pickerHint,
          child.widgetHint === "UPSTREAM_FIELD_PICKER" ? "UPSTREAM_FIELD_PICKER" : undefined,
          context + "." + column.key + ": native upstream field source");
      }
      const pointer = "/" + field.key.replaceAll(".", "/");
      const wire = descriptor.wireLists?.find(entry => entry.listPointer === pointer);
      assert.ok(wire, context + ": producer-owned indexed wire LIST missing");
      assert.equal(wire.itemPointerTemplate, pointer + "/{index}",
        context + ": native indexed JSON Pointer");
    } else {
      assertNativeValueType(field.valueType, property, context);
    }
  }
  for (const [key, activeWhen] of [["url", false], ["urlField", true]]) {
    const property = native.get(key);
    const field = schema.fields.find(item => item.key === key);
    assert.equal(property.activationContext?.flagPointer, "/urlInField");
    assert.equal(property.activationContext?.activeWhen, activeWhen);
    assert.deepEqual(field.activation, { flagPointer: "/urlInField", activeWhen });
  }
  assert.equal(native.get("urlField").widgetHint, "UPSTREAM_FIELD_PICKER");
  assert.equal(schema.fields.find(field => field.key === "urlField").pickerHint,
    "UPSTREAM_FIELD_PICKER");
  assert.equal(native.get("httpPassword").password, true,
    "pinned Hop HttpMeta declares password=true");
  assert.equal(schema.fields.find(field => field.key === "httpPassword").sensitive, true);
  for (const key of ["result.name", "result.code", "result.response_time", "result.response_header"]) {
    assert.equal(native.get(key).widgetHint, "NONE",
      key + ": response field names are authored outputs, not input references");
  }
  const grouped = schema.tabs.flatMap(tab => tab.groups.flatMap(group => group.fields));
  assert.equal(grouped.length, schema.fields.length);
  for (const field of grouped) {
    assert.strictEqual(field, schema.fields.find(item => item.key === field.key));
  }
}
console.log("PROD_P0_T1_HTTP_NATIVE_SCHEMA_COMPAT=PASS");
