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

test("Table Input preserves named metadata and editor picker semantics", () => {
  const schema = findConfigSchema("transform", "TableInput");
  assert.ok(schema);
  const connection = schema.fields.find(({ key }) => key === "connection");
  assert.deepEqual(
    { control: connection?.control, metadataType: connection?.metadataType, storeWithName: connection?.storeWithName },
    { control: "MetadataPicker", metadataType: "DatabaseMeta", storeWithName: true },
  );
  assert.equal(schema.fields.find(({ key }) => key === "sql")?.control, "MonacoEditor");
  assert.equal(schema.fields.find(({ key }) => key === "sql_from_file")?.control, "VfsPicker");
});

test("Database Connection marks password transport as sensitive", () => {
  const schema = findConfigSchema("metadata", "DatabaseMeta");
  assert.ok(schema);
  const password = schema.fields.find(({ key }) => key === "rdbms.password");
  assert.equal(password?.sensitive, true);
});
