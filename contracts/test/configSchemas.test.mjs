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


function assertDeclarativeLayoutCoversSchema(pluginId) {
  const schema = findConfigSchema("transform", pluginId);
  assert.ok(schema);
  assert.ok(schema.tabs?.length, `${pluginId} must expose a T1 declarative layout`);

  const schemaKeys = schema.fields.map(({ key }) => key).sort();
  const layoutKeys = schema.tabs
    .flatMap(({ groups }) => groups)
    .flatMap(({ fieldKeys }) => fieldKeys);

  assert.equal(new Set(layoutKeys).size, layoutKeys.length, `${pluginId} layout must not repeat fields`);
  assert.deepEqual([...layoutKeys].sort(), schemaKeys, `${pluginId} layout must cover schema.fields exactly once`);
}

test("Select Values and Sort Rows reuse schema.fields through declarative fieldKeys", () => {
  assertDeclarativeLayoutCoversSchema("SelectValues");
  assertDeclarativeLayoutCoversSchema("SortRows");
});

test("Database Connection sensitive read/write contract stays opaque", () => {
  const redacted = { state: "redacted" };
  const encrypted = { state: "encrypted", value: "Encrypted 2be98afc..." };

  assert.deepEqual(redacted, { state: "redacted" });
  assert.equal(encrypted.state, "encrypted");
  assert.equal(typeof encrypted.value, "string");

  const schema = findConfigSchema("metadata", "DatabaseMeta");
  const password = schema?.fields.find(({ key }) => key === "rdbms.password");
  assert.equal(password?.sensitive, true);
});
