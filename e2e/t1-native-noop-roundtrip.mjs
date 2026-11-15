/** Real Hop no-op metadata invariance. Copies a producer fixture; never edits the original. */
import { request as apiRequest } from "playwright";
import { copyFile, unlink } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const base = process.env.T1_PREVIEW_URL ?? "http://127.0.0.1:4196/t1/";
const workspace = process.env.T1_TEST_WORKSPACE ?? "/home/ubuntu/hmw-agent-lab/workspaces/t1";
const fixtureDir = path.join(path.dirname(fileURLToPath(import.meta.url)), "fixtures");
const cases = [
  ["ExecSql", process.env.T1_EXECSQL_FIXTURE ?? path.join(fixtureDir, "execsql-native-waterlevel.hpl"), "org.apache.hop.pipeline.transforms.sql.ExecSqlMeta"],
  ["SelectValues", process.env.T1_SELECTVALUES_FIXTURE ?? path.join(fixtureDir, "v9-select-values-proof.hpl"), "org.apache.hop.pipeline.transforms.selectvalues.SelectValuesMeta"],
];
const request = await apiRequest.newContext();
const created = [];
const results = [];
async function post(url, data) {
  const response = await request.post(new URL(url, base).href, { data });
  const body = await response.text();
  if (!response.ok()) throw Error(url + " HTTP " + response.status() + ": " + body.slice(0, 1200));
  return JSON.parse(body);
}
try {
  for (const [label, source, expectedClass] of cases) {
    const name = "t1-native-noop-" + randomUUID() + ".hpl";
    const file = path.join(workspace, name);
    await copyFile(source, file);
    created.push(file);
    const open = async () => post("api/pipelines/open", { path: "/workspace/" + name });
    const initial = await open();
    assert.ok(initial.id, label + " missing document ID");
    const candidates = initial.nodes ?? [];
    let selected;
    for (const node of candidates) {
      const nodeId = node.id;
      if (!nodeId) continue;
      const read = await post("api/pipelines/" + initial.id + "/config/read", { nodeId });
      if (read.descriptor?.className === expectedClass) { selected = { nodeId, read }; break; }
    }
    assert.ok(selected, label + " missing native transform " + expectedClass);
    const before = selected.read.config;
    const written = await post("api/pipelines/" + initial.id + "/config/write", { nodeId: selected.nodeId, config: before });
    assert.deepStrictEqual(written.config, before, label + " no-op config/write mutated metadata");
    await post("api/pipelines/" + initial.id + "/save", {});
    const reopened = await open();
    const after = await post("api/pipelines/" + reopened.id + "/config/read", { nodeId: selected.nodeId });
    assert.deepStrictEqual(after.config, before, label + " disk reopen changed native metadata");
    results.push({ transform: label, nodeId: selected.nodeId, nativeClass: expectedClass, noOpWrite: true, diskReopen: true });
  }
  console.log(JSON.stringify({ pass: true, results }));
} finally {
  await request.dispose();
  for (const file of created) await unlink(file).catch(() => {});
}