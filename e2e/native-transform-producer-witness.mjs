/**
 * Real browser + bridge + Hop metadata witness smoke for TableOutput, ExecSql and InsertUpdate.
 * The source .hpl fixtures were PRODUCED and reverse-parsed by Hop Java, not fabricated.
 * Every run copies them to unique workspace files; nothing modifies the originals.
 */
import assert from "node:assert/strict";
import { copyFile, unlink } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const cases = [
  { slot: "t3", source: "tableoutput-native-waterlevel.hpl", type: "TableOutput", name: "water-level-archive" },
  { slot: "t4", source: "execsql-native-waterlevel.hpl", type: "ExecSql", name: "read-water-observation" },
  { slot: "t3", source: "insertupdate-native-waterlevel.hpl", type: "InsertUpdate", name: "upsert-water-level" }
];
const browser = await chromium.launch({ headless: true, args: ["--no-sandbox"] });
try {
  for (const spec of cases) {
    const basename = "native-producer-witness-" + randomUUID() + ".hpl";
    const dest = path.join("/home/ubuntu/hmw-agent-lab/workspaces", spec.slot, basename);
    const errors = [];
    const page = await browser.newPage({ viewport: { width: 1360, height: 840 } });
    page.on("pageerror", error => errors.push(error.message));
    try {
      await copyFile(path.join(root, "e2e/fixtures", spec.source), dest);
      const base = "http://127.0.0.1:4196/" + spec.slot + "/";
      await page.goto(base, { waitUntil: "networkidle" });
      await page.getByRole("button", { name: /打开流程/ }).first().click();
      await page.getByRole("textbox", { name: /服务器流程路径/ }).fill("/workspace/" + basename);
      const response = page.waitForResponse(r => r.url().endsWith("/api/pipelines/open") && r.request().method() === "POST");
      await page.getByRole("button", { name: /打开文件/ }).first().click();
      const opened = await response;
      assert.equal(opened.status(), 200, spec.type + " open HTTP");
      const { id, nodes } = await opened.json();
      assert.equal(nodes.length, 1, spec.type + " nodes");
      assert.equal(nodes[0].pluginId, spec.type, spec.type + " plugin not resolved");
      await page.locator(".react-flow__node").first().dblclick();
      const panel = page.locator("form.generic-config");
      await panel.waitFor();
      const read = await page.request.post(new URL("api/pipelines/" + id + "/config/read", base).href,
        { data: { nodeId: spec.name } });
      assert.equal(read.status(), 200, spec.type + " config/read");
      const data = await read.json();
      assert.equal(data.pluginId, spec.type);
      assert.ok(data.descriptor?.properties?.length > 0, spec.type + " descriptor absent");
      if (spec.type === "TableOutput") {
        assert.equal(data.config.connection, "Warehouse");
        assert.equal(data.config.table, "water_level_archive");
        assert.equal(data.config.specify_fields, true);
        assert.deepEqual(data.config.fields.field.map(f => [f.stream_name, f.column_name]),
          [["station_code", "station_id"], ["level_m", "water_level_m"], ["observed_at", "observation_time"]]);
      } else if (spec.type === "ExecSql") {
        assert.equal(data.config.connection, "Warehouse");
        assert.equal(data.config.single_statement, true);
        assert.equal(data.config.sql, "SELECT station_code, level_m FROM river_observation WHERE level_m >= 1.5;");
        assert.equal(await panel.locator("#config-sql").inputValue(), data.config.sql);
      } else {
        const config = data.config;
        assert.equal(config.connection, "Warehouse");
        assert.equal(config.commit, "250");
        assert.equal(config.lookup.schema, "public");
        assert.equal(config.lookup.table, "water_level_archive");
        assert.deepEqual(config.lookup.key.map(f => [f.name, f.field, f.condition]),
          [["station_code", "station_id", "="], ["observed_at", "observation_time", "="]]);
        assert.deepEqual(config.lookup.value.map(f => [f.name, f.rename, f.update]),
          [["water_level_m", "level_m", true], ["quality_flag", "quality_status", true]]);
        // Independently prove that the native JSON codec can round-trip this nested
        // structure through the real Hop JVM and disk, without hand-writing XML.
        const write = await page.request.post(new URL("api/pipelines/" + id + "/config/write", base).href,
          { data: {nodeId:spec.name, config} });
        assert.equal(write.status(), 200, "InsertUpdate no-op native config/write");
        const saved = await page.request.post(new URL("api/pipelines/" + id + "/save", base).href,
          { data: {} });
        assert.equal(saved.status(), 200, "InsertUpdate native .hpl save");
        const reopened = await page.request.post(new URL("api/pipelines/open", base).href,
          { data: {path:"/workspace/"+basename} });
        assert.equal(reopened.status(), 200, "InsertUpdate disk reopen");
        const reopenedId = (await reopened.json()).id;
        const verified = await page.request.post(
          new URL("api/pipelines/" + reopenedId + "/config/read", base).href,
          { data: {nodeId:spec.name} });
        assert.equal(verified.status(), 200, "InsertUpdate native reopen config/read");
        assert.deepEqual((await verified.json()).config, config, "InsertUpdate native codec reordered or discarded fields");
      }
      assert.deepEqual(errors, [], spec.type + " browser errors");
      console.log(JSON.stringify({ pass: true, slot: spec.slot, plugin: spec.type,
        node: spec.name, originalProducer: "Apache Hop 2.20 Java", nativeConfigRead: true,
        uiInspectorOpened: true, pageErrors: errors }));
    } finally {
      await page.close();
      await unlink(dest).catch(() => {});
    }
  }
} finally {
  await browser.close();
}