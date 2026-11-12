// V4 contract regression: this file intentionally validates only browser/server transport shape.
import assert from "node:assert/strict";
import test from "node:test";
import { findConfigSchema, listConfigSchemas, resolveNativeWireListItem } from "../dist/index.js";

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
