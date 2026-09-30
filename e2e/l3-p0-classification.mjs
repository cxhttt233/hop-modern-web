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

try {
  const { GenericConfigPanel } = await vite.ssrLoadModule("/src/editor/GenericConfigPanel.tsx");
  const matrix = [];
  const gapIds = new Map();

  for (const source of sourceRows) {
    const descriptor = source.descriptor;
    assert.ok(descriptor?.className, `${source.id}: descriptor.className missing`);
    assert.ok(Array.isArray(descriptor.properties), `${source.id}: descriptor.properties missing`);

    const config = Object.fromEntries(descriptor.properties.map(property => [property.key, sampleValue(property)]));
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
      property.shape === "STRING" && /sql|query|script|description|expression/i.test(property.key))) {
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

  const output = { counts, commonRendererGaps, rows: matrix };
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
  console.log("PROD_P0_L3_MATRIX=" + outputPath);
} finally {
  await vite.close();
}
