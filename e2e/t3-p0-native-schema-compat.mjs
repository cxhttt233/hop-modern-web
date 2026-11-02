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
