// V4 contract regression: this file intentionally validates only browser/server transport shape.
import assert from "node:assert/strict";
import test from "node:test";
import { findConfigSchema, listConfigSchemas, resolveNativeWireListItem, readNativeWireList, writeNativeWireList } from "../dist/index.js";

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
  assert.equal(schema.fields.find(({ key }) => key === "sql")?.control, "MonacoEditor");
  assert.equal(schema.fields.find(({ key }) => key === "sql_from_file")?.control, "VfsPicker");
  assert.equal(schema.fields.find(({ key }) => key === "fields.field")?.control, "EditableTable");
});

test("Database Connection marks password transport as sensitive", () => {
  const schema = findConfigSchema("metadata", "DatabaseMeta");
  assert.ok(schema);
  const password = schema.fields.find(({ key }) => key === "rdbms.password");
  assert.equal(password?.sensitive, true);
});

test("native LIST address resolver never guesses paths or overwrites a sibling list", () => {
  const descriptor = {
    wireLists: [
      { descriptorPath: "fields.field", listPointer: "/fields/field",
        itemPointerTemplate: "/fields/field/{index}" },
      { descriptorPath: "fields.remove", listPointer: "/fields/remove",
        itemPointerTemplate: "/fields/remove/{index}" },
    ],
  };
  assert.deepEqual(resolveNativeWireListItem(descriptor, "fields.field", 2), {
    listPointer: "/fields/field",
    itemPointer: "/fields/field/2",
  });
  assert.deepEqual(resolveNativeWireListItem(descriptor, "fields.remove", 2), {
    listPointer: "/fields/remove",
    itemPointer: "/fields/remove/2",
  });
  assert.equal(resolveNativeWireListItem(descriptor, "fields.meta", 0), null,
    "unknown fields must not guess a native JSON path");
  for (const invalid of [-1, 1.25, Number.NaN, Number.POSITIVE_INFINITY,
    Number.MAX_SAFE_INTEGER + 1]) {
    assert.throws(() => resolveNativeWireListItem(descriptor, "fields.field", invalid),
      RangeError);
  }
  assert.throws(() => resolveNativeWireListItem({ wireLists: [
    ...descriptor.wireLists, descriptor.wireLists[0],
  ] }, "fields.field", 0), /Ambiguous native LIST/);
  assert.throws(() => resolveNativeWireListItem({ wireLists: [{
    descriptorPath: "fields.field", listPointer: "/fields/~bad",
    itemPointerTemplate: "/fields/~bad/{index}",
  }] }, "fields.field", 0), /Invalid producer-owned/);
  assert.throws(() => resolveNativeWireListItem({ wireLists: [{
    descriptorPath: "fields.field", listPointer: "/fields/field",
    itemPointerTemplate: "/fields/other/{index}",
  }] }, "fields.field", 0), /Invalid producer-owned/);
});

test("native LIST resolver rejects cross-descriptor wire container aliasing", () => {
  const descriptor = {
    wireLists: [
      { descriptorPath: "fields.field", listPointer: "/fields/field",
        itemPointerTemplate: "/fields/field/{index}" },
      { descriptorPath: "fields.remove", listPointer: "/fields/field",
        itemPointerTemplate: "/fields/field/{index}" },
    ],
  };
  for (const path of ["fields.field", "fields.remove"]) {
    assert.throws(() => resolveNativeWireListItem(descriptor, path, 0),
      /Aliased native LIST wire pointer/,
      "different native lists must never share a writable JSON container");
  }
  const independent = { wireLists: descriptor.wireLists.map((entry, index) =>
    index === 0 ? entry : { ...entry, listPointer: "/fields/remove",
      itemPointerTemplate: "/fields/remove/{index}" }) };
  assert.deepEqual(resolveNativeWireListItem(independent, "fields.remove", 1),
    { listPointer: "/fields/remove", itemPointer: "/fields/remove/1" });
});

test("TableInput native field editor preserves wire paths and sibling values", () => {
  const descriptor = { wireLists: [{
    descriptorPath: "field", listPointer: "/fields/field",
    itemPointerTemplate: "/fields/field/{index}",
  }] };
  const original = { connection: "Warehouse", sql: "SELECT * FROM river",
    fields: { field: [{ name: "甲站", type: 2, length: 32 }],
      other: [{ keep: "nested-sibling" }] }, opaque: { credential: "keep" } };
  const rows = readNativeWireList(original, descriptor, "field");
  assert.deepEqual(rows, [{ name: "甲站", type: 2, length: 32 }]);
  const changed = writeNativeWireList(original, descriptor, "field",
    [{ ...rows[0], name: "黄河乙站", length: 64 }]);
  assert.deepEqual(changed.fields.field, [{ name: "黄河乙站", type: 2, length: 64 }]);
  assert.deepEqual(changed.fields.other, original.fields.other);
  assert.deepEqual(changed.opaque, original.opaque);
  assert.equal(changed.sql, original.sql);
  assert.equal(Object.hasOwn(changed, "field"), false);
  assert.equal(original.fields.field[0].name, "甲站", "native draft must be immutable");
  assert.deepEqual(readNativeWireList({ sql: "SELECT 1" }, descriptor, "field"), []);
  const created = writeNativeWireList({ sql: "SELECT 1" }, descriptor, "field",
    [{ name: "新站", type: 0 }]);
  assert.deepEqual(created, { sql: "SELECT 1", fields: { field: [{ name: "新站", type: 0 }] } });
  assert.equal(readNativeWireList(original, descriptor, "unlisted"), null);
  assert.throws(() => writeNativeWireList(original, descriptor, "unlisted", []),
    /Unattested native LIST/);
  assert.throws(() => writeNativeWireList({ fields: "bad" }, descriptor, "field", []),
    /non-object parent/);
  assert.throws(() => readNativeWireList({ fields: { field: "bad" } }, descriptor, "field"),
    /not an array/);
  assert.throws(() => writeNativeWireList({ fields: { field: "bad" } }, descriptor, "field", []),
    /non-list value/);
  const alias = { wireLists: [...descriptor.wireLists, {
    descriptorPath: "other", listPointer: "/fields/field",
    itemPointerTemplate: "/fields/field/{index}",
  }] };
  assert.throws(() => readNativeWireList(original, alias, "field"), /Aliased native LIST/);
  const hostile = { wireLists: [{
    descriptorPath: "field", listPointer: "/__proto__/field",
    itemPointerTemplate: "/__proto__/field/{index}",
  }] };
  assert.throws(() => writeNativeWireList(original, hostile, "field", []),
    /Unsafe native LIST/);
});
