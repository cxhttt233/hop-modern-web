import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { createServer } from "vite";

const [, , fixtureArg = "docs/p0-transform-descriptors.generated.json", outputArg = "docs/p0-l3-classification.generated.json"] = process.argv;
const here = path.dirname(fileURLToPath(import.meta.url));
const appRoot = path.resolve(here, "../app");
const fixturePath = path.resolve(here, "..", fixtureArg);
const outputPath = path.resolve(here, "..", outputArg);
const sourceRows = JSON.parse(await fs.readFile(fixturePath, "utf8"));

assert.equal(sourceRows.length, 20, "P0 descriptor fixture must contain exactly 20 transforms");

const vite = await createServer({ root: appRoot, appType: "custom", server: { middlewareMode: true } });

const sampleValue = property => {
  switch (property.shape) {
    case "BOOLEAN": return false;
    case "NUMBER": return 0;
    case "ENUM": return property.options?.[0]?.value ?? "";
    case "OBJECT": return {};
    case "LIST": return [];
    default: return "";
  }
};

const controlKind = (html, property) => {
  const id = `config-${property.javaField}`;
  const escaped = id.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const textarea = new RegExp(`<textarea[^>]*id="${escaped}"`);
  if (textarea.test(html)) {
    if (property.shape === "OBJECT") return "JSON_TEXTAREA_FALLBACK";
    if (property.shape === "LIST") return "JSON_TEXTAREA_FALLBACK";
    return "TEXTAREA";
  }
  const select = new RegExp(`<select[^>]*id="${escaped}"`);
  if (select.test(html)) return "SELECT";
  const input = html.match(new RegExp(`<input[^>]*id="${escaped}"[^>]*>`))?.[0] ?? "";
  if (/type="checkbox"/.test(input)) return "CHECKBOX";
  if (/type="number"/.test(input)) return "NUMBER_INPUT";
  if (property.shape === "ENUM") return "TEXT_INPUT_FALLBACK";
  return "TEXT_INPUT";
};

const walkProperties = (properties, prefix = "", ancestry = []) => properties.flatMap(property => {
  const fieldPath = prefix ? `${prefix}.${property.key}` : property.key;
  const entry = { path: fieldPath, property, ancestry, topLevel: ancestry.length === 0 };
  return [entry,
    ...walkProperties(property.children ?? [], fieldPath, [...ancestry, "OBJECT"]),
    ...walkProperties(property.elementProperties ?? [], fieldPath, [...ancestry, "LIST"])];
});
const validTextHints = new Set(["NONE", "SQL", "SCRIPT", "TEMPLATE"]);
const expectedProducerFields = [
  ["CheckSum", "checksumtype", "ENUM", "NONE"],
  ["CheckSum", "resultType", "ENUM", "NONE"],
  ["Rest", "streamingFormat", "ENUM", "NONE"],
  ["ExecSql", "sql", "STRING", "SQL"],
  ["ScriptValueMod", "jsScript.jsScript_script", "STRING", "SCRIPT"],
  ["TableInput", "sql", "STRING", "SQL"],
  ["UniqueRowsByHashSet", "error_description", "STRING", "NONE"],
  ["JsonInput", "file.file.type_filter", "ENUM", "NONE"],
  ["FilterRows", "compare.condition.operator", "ENUM", "NONE"]
];
const producerIndex = new Map();
for (const source of sourceRows) {
  const entries = walkProperties(source.descriptor.properties);
  producerIndex.set(source.id, entries);
  for (const entry of entries) {
    const { property, path: fieldPath } = entry;
    assert.ok(Object.hasOwn(property, "textEditorHint"), `${source.id}.${fieldPath}: missing production hint`);
    assert.ok(validTextHints.has(property.textEditorHint), `${source.id}.${fieldPath}: invalid production hint`);
    if (property.shape !== "STRING") assert.equal(property.textEditorHint, "NONE", `${source.id}.${fieldPath}`);
    if (property.shape === "ENUM") {
      assert.ok(Array.isArray(property.options) && property.options.length > 0, `${source.id}.${fieldPath}: empty enum options`);
      assert.ok(property.options.every(option => typeof option.label === "string" && typeof option.value === "string"),
        `${source.id}.${fieldPath}: malformed enum option`);
    }
  }
}
for (const [pluginId, fieldPath, shape, hint] of expectedProducerFields) {
  const entry = producerIndex.get(pluginId)?.find(candidate => candidate.path === fieldPath);
  assert.ok(entry, `${pluginId}.${fieldPath}: production descriptor path absent`);
  assert.equal(entry.property.shape, shape, `${pluginId}.${fieldPath}: shape`);
  assert.equal(entry.property.textEditorHint, hint, `${pluginId}.${fieldPath}: hint`);
}
for (const [pluginId, fieldPath, storeWithCode] of [
  ["CheckSum", "checksumtype", true],
  ["CheckSum", "resultType", true],
  ["Rest", "streamingFormat", false]
]) {
  const property = producerIndex.get(pluginId).find(entry => entry.path === fieldPath).property;
  assert.equal(property.storeWithCode, storeWithCode, `${pluginId}.${fieldPath}: storeWithCode`);
  assert.equal(property.storeWithName, false, `${pluginId}.${fieldPath}: storeWithName`);
}
const reachabilityFor = entry => {
  if (entry.topLevel) return "TOP_LEVEL_FIELD";
  if (entry.ancestry.includes("LIST")) return "PRODUCER_PRESENT_BUT_LIST_RENDERER_UNREACHABLE";
  return "PRODUCER_PRESENT_OBJECT_CHILD_NOT_RENDERED_BY_T3";
};

try {
  const { GenericConfigPanel, applyGenericConfigDraft } = await vite.ssrLoadModule("/src/editor/GenericConfigPanel.tsx");
  const matrix = [];
  const gapIds = new Map();
  let consumerDraftRoundTrip = null;

  for (const source of sourceRows) {
    const descriptor = source.descriptor;
    assert.ok(descriptor?.className, `${source.id}: descriptor.className missing`);
    assert.ok(Array.isArray(descriptor.properties), `${source.id}: descriptor.properties missing`);

    const producerEntries = producerIndex.get(source.id);
    const producerSpecialized = producerEntries.filter(entry => entry.property.textEditorHint !== "NONE");
    const producerEnums = producerEntries.filter(entry => entry.property.shape === "ENUM");
    const config = Object.fromEntries(descriptor.properties.map(property => [property.key, sampleValue(property)]));
    if (source.id === "TableInput") {
      const rawSql = "SELECT \"quoted\"\\\\path\nWHERE id = ${ID} AND note = '雪/<>&'\n-- ${HOP_VAR}";
      const original = { sql: "old", untouched: { marker: "preserve" } };
      const updated = applyGenericConfigDraft(original, descriptor, { sql: rawSql });
      assert.equal(updated.sql, rawSql, "actual consumer must preserve raw SQL");
      assert.equal(Buffer.compare(Buffer.from(updated.sql, "utf8"), Buffer.from(rawSql, "utf8")), 0,
        "actual consumer must preserve SQL UTF-8 bytes");
      assert.deepEqual(updated.untouched, original.untouched, "unrelated config must be preserved");
      consumerDraftRoundTrip = { pluginId: "TableInput", function: "applyGenericConfigDraft",
        rawUtf8ByteIdentical: true, unrelatedConfigPreserved: true, status: "PASS" };
    }
    const html = renderToStaticMarkup(React.createElement(GenericConfigPanel, {
      descriptor,
      config,
      onCancel() {},
      onSave() {},
    }));

    const controls = {};
    for (const property of descriptor.properties) controls[property.key] = controlKind(html, property);

    const shapes = [...new Set(descriptor.properties.map(property => property.shape))].sort();
    const fallbackShapes = [...new Set(descriptor.properties
      .filter(property => ["ENUM", "OBJECT", "LIST"].includes(property.shape))
      .map(property => property.shape))].sort();

    const gaps = [];
    if (shapes.includes("LIST")) gaps.push("LIST_EDITOR");
    if (shapes.includes("OBJECT")) gaps.push("OBJECT_EDITOR");
    if (shapes.includes("ENUM")) gaps.push("ENUM_SELECT");
    if (descriptor.properties.some(property =>
      property.shape === "STRING" && property.textEditorHint !== "NONE")) {
      gaps.push("SPECIALIZED_TEXT_EDITOR");
    }

    for (const gap of gaps) {
      const ids = gapIds.get(gap) ?? [];
      ids.push(source.id);
      gapIds.set(gap, ids);
    }

    let classification = "PASS_GENERIC";
    let reason = "all annotated properties render with native generic scalar controls";
    if (shapes.includes("LIST") || shapes.includes("OBJECT")) {
      classification = "NEEDS_COMMON_WIDGET";
      reason = "OBJECT/LIST properties currently render as JSON textarea fallbacks";
    } else if (shapes.includes("ENUM")) {
      classification = "PARTIAL_FALLBACK";
      reason = "ENUM properties currently render as text-input fallbacks instead of select controls";
    } else if (gaps.includes("SPECIALIZED_TEXT_EDITOR")) {
      reason = "generic controls are usable; SQL/script-like text uses textarea but specialized editor remains a common UX gap";
    } else if (descriptor.properties.length === 0) {
      reason = "no annotated editable properties were discovered; generic panel has no fields to render";
    }

    matrix.push({
      pluginId: source.id,
      metaClass: source.metaClass,
      descriptorPropertyCount: descriptor.properties.length,
      descriptorShapes: source.descriptorShapes ?? {},
      renderedControlKinds: controls,
      fallbackShapes,
      classification,
      exactReason: reason,
      gaps,
      producerContract: {
        recursivePropertyCount: producerEntries.length,
        enums: producerEnums.map(entry => ({ path: entry.path, optionsCount: entry.property.options.length,
          storeWithCode: entry.property.storeWithCode, storeWithName: entry.property.storeWithName })),
        specializedText: producerSpecialized.map(entry => ({ path: entry.path,
          hint: entry.property.textEditorHint, reachability: reachabilityFor(entry) }))
      },
      consumerReachability: producerEntries.filter(entry => !entry.topLevel && (entry.property.shape === "ENUM" ||
        entry.property.textEditorHint !== "NONE")).map(entry => ({ path: entry.path, shape: entry.property.shape,
          t3Panel: reachabilityFor(entry), task4ActualReactUi: "NOT_VERIFIED_BY_THIS_TEST" }))
    });
  }

  const classifications = Object.groupBy(matrix, row => row.classification);
  const counts = Object.fromEntries(Object.entries(classifications).map(([key, rows]) => [key, rows.length]));
  for (const key of ["PASS_GENERIC", "PARTIAL_FALLBACK", "NEEDS_COMMON_WIDGET", "BESPOKE_REQUIRED"]) {
    counts[key] ??= 0;
  }

  const commonRendererGaps = [...gapIds.entries()]
    .map(([kind, ids]) => ({ kind, ids: ids.sort(), affected: ids.length }))
    .sort((a, b) => b.affected - a.affected || a.kind.localeCompare(b.kind));

  assert.ok(consumerDraftRoundTrip?.rawUtf8ByteIdentical, "TableInput actual draft test must run");
  const output = {
    counts, commonRendererGaps, rows: matrix,
    producerContract: { checkedPlugins: producerIndex.size, expectedExactPaths: expectedProducerFields.length,
      metadataSource: "recursive production descriptor", status: "PASS" },
    consumerDraftRoundTrip,
    acceptanceBoundary: {
      producerDescriptor: "PASS",
      t3ActualDraftFunction: "PASS_FOR_TABLEINPUT_SQL",
      task4ReactUi: "NOT_READY_NOT_TESTED",
      specializedTextConsumerFormalAdmission: "PENDING_UNTIL_SEPARATE_CONTRACT_IN_CI"
    }
  };
  await fs.mkdir(path.dirname(outputPath), { recursive: true });
  await fs.writeFile(outputPath, JSON.stringify(output, null, 2) + "\n", "utf8");

  assert.equal(matrix.length, 20);
  assert.equal(matrix.filter(row => row.classification === "BESPOKE_REQUIRED").length, 0,
    "BESPOKE_REQUIRED must only appear with explicit evidence");

  console.log("PROD_P0_L3_COUNTS=" + JSON.stringify(counts));
  for (const [classification, rows] of Object.entries(classifications)) {
    console.log(`PROD_P0_L3_${classification}=${rows.map(row => row.pluginId).sort().join(",")}`);
  }
  for (const gap of commonRendererGaps) {
    console.log(`COMMON_RENDERER_GAP=${gap.kind}:${gap.ids.join(",")}`);
  }
  console.log("PROD_P0_L3_PRODUCER_CONTRACT=PASS");
  console.log("PROD_P0_L3_TABLEINPUT_SQL_DRAFT_UTF8=PASS");
  console.log("PROD_P0_L3_TASK4_UI_ADMISSION=NOT_READY");
  console.log("PROD_P0_L3_MATRIX=" + outputPath);
} finally {
  await vite.close();
}
