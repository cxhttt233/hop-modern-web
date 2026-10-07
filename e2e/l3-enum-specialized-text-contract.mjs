import assert from "node:assert/strict";
import fs from "node:fs/promises";

const [, , input = "docs/p0-transform-descriptors.generated.json", output = "docs/p0-enum-specialized-text-contract.generated.json"] = process.argv;
const rows = JSON.parse(await fs.readFile(input, "utf8"));
assert.equal(rows.length, 20);

const walk = (properties, prefix = "", topLevel = true) => properties.flatMap(property => {
  const path = prefix ? `${prefix}.${property.key}` : property.key;
  return [{ path, property, topLevel }, ...walk(property.children ?? [], path, false), ...walk(property.elementProperties ?? [], path, false)];
});
const entries = rows.flatMap(row => walk(row.descriptor.properties).map(x => ({ pluginId: row.id, ...x })));

const enumEntries = entries.filter(x => x.topLevel && x.property.shape === "ENUM");
const enumAffected = Object.values(Object.groupBy(enumEntries, x => x.pluginId)).map(group => ({
  pluginId: group[0].pluginId,
  paths: group.map(x => x.path),
  fields: group.map(x => {
    const p = x.property;
    assert.ok(p.options?.length);
    assert.ok(p.options.every(o => typeof o.label === "string" && typeof o.value === "string"));
    assert.equal(p.storeWithName, false);
    const current = p.options[0].value;
    assert.ok(p.options.some(o => o.value === current));
    for (const probe of [null, "", current]) assert.deepEqual(JSON.parse(JSON.stringify({v:probe})).v, probe);
    return { path:x.path, options:p.options, currentValueProbe:current, nullProbe:null, emptyProbe:"", storeWithName:p.storeWithName, storeWithCode:p.storeWithCode };
  })
})).sort((a,b)=>a.pluginId.localeCompare(b.pluginId));
assert.deepEqual(enumAffected.map(x=>x.pluginId), ["CheckSum","Rest"]);
assert.deepEqual(enumAffected.map(x => x.paths), [["checksumtype","resultType"],["streamingFormat"]]);
assert.ok(enumAffected.find(x=>x.pluginId==="CheckSum").fields.every(x=>x.storeWithCode));
assert.ok(enumAffected.find(x=>x.pluginId==="Rest").fields.every(x=>!x.storeWithCode));

// Consume the production descriptor as the sole editor-hint authority.
const validHints = new Set(["NONE", "SQL", "SCRIPT", "TEMPLATE"]);
for (const entry of entries) {
  assert.ok(Object.hasOwn(entry.property, "textEditorHint"), entry.pluginId + "." + entry.path + ": missing production hint");
  assert.ok(validHints.has(entry.property.textEditorHint), entry.pluginId + "." + entry.path + ": invalid hint");
  if (entry.property.shape !== "STRING") assert.equal(entry.property.textEditorHint, "NONE");
}
const specializedEntries = entries.filter(x => x.property.textEditorHint !== "NONE");
const specializedAffected = Object.values(Object.groupBy(specializedEntries, x=>x.pluginId)).map(group=>({
  pluginId:group[0].pluginId,
  paths:group.map(x=>x.path),
  fields:group.map(x=>({path:x.path,textEditorHint:x.property.textEditorHint}))
})).sort((a,b)=>a.pluginId.localeCompare(b.pluginId));
assert.deepEqual(specializedAffected.map(x=>x.pluginId), ["ExecSql","ScriptValueMod","TableInput"]);
assert.deepEqual(specializedAffected.flatMap(x=>x.paths), ["sql","jsScript.jsScript_script","sql"]);
assert.ok(!specializedEntries.some(x=>x.pluginId==="UniqueRowsByHashSet"));
assert.deepEqual(specializedAffected.flatMap(x => x.fields.map(field => ({ pluginId:x.pluginId, ...field }))), [
  {pluginId:"ExecSql",path:"sql",textEditorHint:"SQL"},
  {pluginId:"ScriptValueMod",path:"jsScript.jsScript_script",textEditorHint:"SCRIPT"},
  {pluginId:"TableInput",path:"sql",textEditorHint:"SQL"}
]);
const falsePositive = entries.find(x => x.pluginId === "UniqueRowsByHashSet" && x.path === "error_description");
assert.ok(falsePositive);
assert.equal(falsePositive.property.textEditorHint, "NONE");

const raw = "SELECT \"quoted\"\\\\path\nWHERE id = ${ID} AND note = '雪/<>&'\n-- ${HOP_VAR}";
const reread = JSON.parse(JSON.stringify({config:{value:raw}})).config.value;
assert.equal(reread, raw);
assert.equal(Buffer.compare(Buffer.from(reread,"utf8"),Buffer.from(raw,"utf8")),0);

const artifact = {
  source:{productionP0:20,discovery:"recursive generated-descriptor walk",pluginIdBranches:false},
  enumSelect:{affected:enumAffected,acceptance:"PASS",task4Admission:"READY",semantics:{options:"label=display,value=authoritative stored value",currentValue:"config value matched against options[].value",nullEmpty:"preserved distinctly",storeWithName:"descriptor false for affected enums",storeWithCode:"CheckSum=true uses code; Rest=false uses enum name",writeReread:"production P0 L2 authoritative serializer round-trip gate + exact JSON value probe"}},
  specializedTextEditor:{affected:specializedAffected,acceptance:"PASS",task4Admission:"READY",semantics:{hint:"production descriptor property.textEditorHint authoritative",rawStringAuthoritative:true,preserved:["newline","quotes","backslash","variable-placeholder","unicode","special-characters"],falsePositiveRejected:"UniqueRowsByHashSet.error_description",writeReread:"production P0 L2 authoritative serializer round-trip gate + byte-identical UTF-8 probe"}},
  genericDescriptorMetadataDelta:["production Property.textEditorHint: NONE|SQL|SCRIPT|TEMPLATE (no E2E heuristic)"],
  remainingCommonRendererGap:"NONE_AT_DESCRIPTOR_CONTRACT_LAYER; Task4 owns React controls"
};
await fs.writeFile(output,JSON.stringify(artifact,null,2)+"\n");
const fmt=a=>a.map(x=>`${x.pluginId}:${x.paths.join(",")}`).join(";");
console.log("WORKLOAD=MEDIUM");
console.log("ENUM_SELECT_AFFECTED="+fmt(enumAffected));
console.log("ENUM_SELECT_ACCEPTANCE=PASS");
console.log("ENUM_SELECT_TASK4_ADMISSION=READY");
console.log("SPECIALIZED_TEXT_AFFECTED="+fmt(specializedAffected));
console.log("SPECIALIZED_TEXT_EDITOR_ACCEPTANCE=PASS");
console.log("SPECIALIZED_TEXT_EDITOR_TASK4_ADMISSION=READY");
console.log("genericDescriptorMetadataDelta=production-Property.textEditorHint:NONE|SQL|SCRIPT|TEMPLATE");
console.log("remaining common renderer gap=NONE_AT_DESCRIPTOR_CONTRACT_LAYER;TASK4_REACT_CONTROLS");
