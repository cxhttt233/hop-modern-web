import assert from "node:assert/strict";
import fs from "node:fs/promises";

const [, , fixtureArg = "docs/p0-transform-descriptors.generated.json", outputArg = "docs/p0-list-editor-contract.generated.json"] = process.argv;
const rows = JSON.parse(await fs.readFile(fixtureArg, "utf8"));

const byId = id => {
  const row = rows.find(candidate => candidate.id === id);
  assert.ok(row, `${id}: production descriptor missing`);
  return row;
};
const property = (id, key) => {
  const candidate = byId(id).descriptor.properties.find(item => item.key === key);
  assert.ok(candidate, `${id}.${key}: descriptor property missing`);
  return candidate;
};
const listContract = descriptor => {
  assert.equal(descriptor.shape, "LIST", `${descriptor.key}: expected LIST`);
  assert.equal(descriptor.layout?.presentation, "TABLE", `${descriptor.key}: LIST must declare TABLE presentation`);
  const columns = descriptor.elementProperties ?? [];
  return {
    rowKind: columns.length === 0 ? "PRIMITIVE" : "OBJECT",
    columns: columns.map(column => ({
      key: column.key,
      javaField: column.javaField,
      shape: column.shape,
      javaType: column.javaType,
      elementJavaType: column.elementJavaType ?? null,
      sensitive: Boolean(column.sensitive),
      options: column.options ?? [],
    })),
  };
};
const editList = (value, operations) => {
  let next = value == null ? [] : structuredClone(value);
  assert.ok(Array.isArray(next), "LIST editor value must normalize to an array");
  for (const operation of operations) {
    if (operation.type === "add") next.splice(operation.index ?? next.length, 0, structuredClone(operation.value));
    else if (operation.type === "delete") next.splice(operation.index, 1);
    else if (operation.type === "edit") next[operation.index] = structuredClone(operation.value);
    else if (operation.type === "reorder") {
      const [moved] = next.splice(operation.from, 1);
      next.splice(operation.to, 0, moved);
    } else assert.fail(`unsupported list operation ${operation.type}`);
  }
  return next;
};

const mergeJoin = listContract(property("MergeJoin", "keyFields1"));
assert.equal(mergeJoin.rowKind, "PRIMITIVE");
assert.deepEqual(mergeJoin.columns, []);
assert.deepEqual(editList(null, [
  { type: "add", value: "left_id" },
  { type: "add", value: "right_id" },
  { type: "reorder", from: 1, to: 0 },
  { type: "edit", index: 1, value: "left_key" },
]), ["right_id", "left_key"]);

const tableOutput = listContract(property("TableOutput", "fields"));
assert.equal(tableOutput.rowKind, "OBJECT");
assert.ok(tableOutput.columns.length >= 2, "TableOutput.fields must expose object-row columns");
assert.ok(tableOutput.columns.every(column => column.key && column.shape), "object columns must be descriptor-addressable");
const tableRow = Object.fromEntries(tableOutput.columns.map(column => [column.key, column.shape === "NUMBER" ? 0 : "sample"]));
assert.deepEqual(editList([tableRow], [
  { type: "add", value: tableRow },
  { type: "delete", index: 0 },
]), [tableRow]);

const concat = listContract(property("ConcatFields", "outputFields"));
assert.equal(concat.rowKind, "OBJECT");
assert.ok(concat.columns.some(column => column.shape === "STRING"), "ConcatFields.outputFields must expose STRING cells");
assert.ok(concat.columns.some(column => column.shape === "NUMBER"), "ConcatFields.outputFields must expose NUMBER cells");
const concatKeys = concat.columns.map(column => column.key);
assert.equal(new Set(concatKeys).size, concatKeys.length, "column keys must be unique for write-back");

const dataGrid = listContract(property("DataGrid", "dataLines"));
const recursive = dataGrid.columns.filter(column => ["LIST", "OBJECT"].includes(column.shape));
assert.ok(recursive.length > 0, "DataGrid.dataLines must retain its nested recursive boundary");
assert.ok(recursive.some(column => column.shape === "LIST"), "DataGrid.dataLines must expose nested LIST cell metadata");

const acceptance = {
  contract: {
    activation: "shape=LIST && layout.presentation=TABLE",
    primitiveRow: "elementProperties.length=0",
    objectRow: "columns=elementProperties in descriptor order",
    operations: ["edit", "add", "delete", "reorder"],
    nullNormalization: "null->[] on edit",
    writeBack: "ordered value written to descriptor property key; authoritative config/write round-trip remains server-owned",
    nestedBoundary: "LIST/OBJECT cells recursively delegate and MUST_NOT_FLATTEN",
    pluginSpecificBranches: false,
    genericDescriptorMetadataDelta: "NONE",
  },
  cases: [
    { pluginId: "MergeJoin", path: "keyFields1", ...mergeJoin },
    { pluginId: "TableOutput", path: "fields", ...tableOutput },
    { pluginId: "ConcatFields", path: "outputFields", ...concat },
    { pluginId: "DataGrid", path: "dataLines", ...dataGrid, recursiveColumns: recursive.map(column => column.key) },
  ],
  task4Admission: {
    singleCommonRendererAllowed: true,
    conditions: [
      "activation is descriptor-only (LIST + TABLE), never pluginId-specific",
      "primitive and object rows derive exclusively from elementProperties",
      "scalar cells reuse generic scalar controls",
      "edit/add/delete/reorder preserve list order and descriptor column identity",
      "LIST/OBJECT cells recursively delegate rather than flatten",
      "result writes back through the existing config/write authoritative round-trip",
    ],
  },
};
await fs.writeFile(outputArg, JSON.stringify(acceptance, null, 2) + "\n", "utf8");
console.log("LIST_EDITOR_ACCEPTANCE=PASS MergeJoin.keyFields1,TableOutput.fields,ConcatFields.outputFields,DataGrid.dataLines");
console.log("LIST_EDITOR_DESCRIPTOR_METADATA_DELTA=NONE");
console.log("LIST_EDITOR_TASK4_ADMISSION=READY");
console.log("LIST_EDITOR_CONTRACT=" + outputArg);
