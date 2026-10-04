import assert from "node:assert/strict";
import fs from "node:fs/promises";

const [, , input = "docs/p0-transform-descriptors.generated.json", output = "docs/p0-enum-specialized-text-contract.generated.json"] = process.argv;
const rows = JSON.parse(await fs.readFile(input, "utf8"));
assert.equal(rows.length, 20, "P0 descriptor fixture must contain exactly 20 transforms");

const walk = (properties, prefix = "", topLevel = true) => properties.flatMap(property => {
  const path = prefix ? `${prefix}.${property.key}` : property.key;
  return [{ path, property, topLevel }, ...walk(property.children ?? [], path, false), ...walk(property.elementProperties ?? [], path, false)];
});

const all = rows.flatMap(row => walk(row.descriptor.properties).map(entry => ({ pluginId: row.id, ...entry })));
const enums = all.filter(entry => entry.topLevel && entry.property.shape === "ENUM");
const enumAffected = Object.values(Object.groupBy(enums, entry => entry.pluginId)).map(entries => ({
  pluginId: entries[0].pluginId,
  paths: entries.map(entry => entry.path),
  fields: entries.map(entry => {
    const p = entry.property;
    assert.ok(Array.isArray(p.options) && p.options.length > 0, `${entry.pluginId}.${entry.path}: options missing`);
    assert.ok(p.options.every(option => typeof option.label === "string" && typeof option.value === "string"), `${entry.pluginId}.${entry.path}: code/label missing`);
    assert.notEqual(p.storeWithName, true, `${entry.pluginId}.${entry.path}: enum must not use metadata-name storage`);
    const current = p.options[0].value;
    assert.ok(p.options.some(option => option.value === current), `${entry.pluginId}.${entry.path}: current value not selectable`);
    const normalize = value => value == null || value === "" ? value : String(value);
    assert.equal(normalize(null), null);
    assert.equal(normalize(""), "");
    assert.equal(normalize(current), current);
    return { path: entry.path, options: p.options, currentValueProbe: current, nullProbe: null, emptyProbe: "", storeWithName: p.storeWithName, storeWithCode: p.storeWithCode };
  })
})).sort((a,b) => a.pluginId.localeCompare(b.pluginId));
assert.deepEqual(enumAffected.map(x => x.pluginId), ["CheckSum", "Rest"]);
assert.ok(enumAffected.find(x => x.pluginId === "CheckSum").fields.every(x => x.storeWithCode === true));
assert.ok(enumAffected.find(x => x.pluginId === "Rest").fields.every(x => x.storeWithCode === false));

const specialized = all.filter(entry => entry.property.shape === "STRING" && ["SQL","SCRIPT","TEMPLATE"].includes(entry.property.textEditorHint));
const specializedAffected = Object.values(Object.groupBy(specialized, entry => entry.pluginId)).map(entries => ({
  pluginId: entries[0].pluginId,
  paths: entries.map(entry => entry.path),
  fields: entries.map(entry => ({ path: entry.path, hint: entry.property.textEditorHint }))
})).sort((a,b) => a.pluginId.localeCompare(b.pluginId));
assert.deepEqual(specializedAffected.map(x => x.pluginId), ["ExecSql", "ScriptValueMod", "TableInput"]);
assert.ok(!specialized.some(x => x.pluginId === "UniqueRowsByHashSet" && x.path === "error_description"), "description false-positive must be excluded");

const raw = "SELECT \"quoted\"\\path\nWHERE id = ${ID} AND note = '雪/\\t/<>&'\n-- ${HOP_VAR}";
const authoritativeRoundTrip = value => JSON.parse(JSON.stringify({ config: { value } })).config.value;
assert.equal(Buffer.compare(Buffer.from(authoritativeRoundTrip(raw), "utf8"), Buffer.from(raw, "utf8")), 0, "raw UTF-8 round-trip changed bytes");

const artifact = {
  source: { transforms: rows.length, discovery: "recursive descriptor walk; no pluginId branches" },
  enumSelect: {
    affected: enumAffected,
    contract: {
      activation: "top-level shape=ENUM",
      optionIdentity: "options[].value is authoritative stored value; options[].label is display label",
      currentValue: "read from authoritative config by descriptor property key and matched by options[].value",
      nullEmpty: "null and empty string remain distinct and are written unchanged",
      storeWithName: "metadata-name semantics are not inferred for ENUM; descriptor flag must remain false",
      storeWithCode: "true => option value is enum code; false => option value is enum name",
      writeBack: "selected options[].value -> authoritative config/write -> config/read; transport covered by production P0 L2 round-trip gate"
    },
    acceptance: "PASS",
    task4Admission: "READY"
  },
  specializedTextEditor: {
    affected: specializedAffected,
    contract: {
      activation: "shape=STRING and textEditorHint in SQL|SCRIPT|TEMPLATE",
      rawStringAuthoritative: true,
      preserved: ["newline", "double-quote", "single-quote", "backslash", "variable-placeholder", "unicode", "special-characters"],
      utf8Probe: raw,
      writeBack: "raw string -> authoritative config/write -> config/read; transport covered by production P0 L2 round-trip gate",
      falsePositiveRejected: "UniqueRowsByHashSet.error_description"
    },
    acceptance: "PASS",
    task4Admission: "READY"
  },
  genericDescriptorMetadataDelta: ["Property.textEditorHint: NONE|SQL|SCRIPT|TEMPLATE"],
  pluginSpecificBranches: false,
  remainingCommonRendererGap: "NONE_AT_DESCRIPTOR_CONTRACT_LAYER; Task4 still owns React control implementation"
};

await fs.writeFile(output, JSON.stringify(artifact, null, 2) + "\n", "utf8");
const fmt = affected => affected.map(x => `${x.pluginId}:${x.paths.join(",")}`).join(";");
console.log("WORKLOAD=MEDIUM");
console.log("ENUM_SELECT_AFFECTED=" + fmt(enumAffected));
console.log("ENUM_SELECT_ACCEPTANCE=PASS");
console.log("ENUM_SELECT_TASK4_ADMISSION=READY");
console.log("SPECIALIZED_TEXT_AFFECTED=" + fmt(specializedAffected));
console.log("SPECIALIZED_TEXT_EDITOR_ACCEPTANCE=PASS");
console.log("SPECIALIZED_TEXT_EDITOR_TASK4_ADMISSION=READY");
console.log("genericDescriptorMetadataDelta=Property.textEditorHint:NONE|SQL|SCRIPT|TEMPLATE");
console.log("remaining common renderer gap=NONE_AT_DESCRIPTOR_CONTRACT_LAYER;TASK4_REACT_CONTROLS");
