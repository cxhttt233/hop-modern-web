// A real Hop FilterRows native condition, GUI editing and .hpl disk roundtrip.
// Uses a UUID test copy so existing pipeline fixtures are never modified.
import { chromium } from "playwright";
import { randomUUID } from "node:crypto";
import { copyFile, unlink } from "node:fs/promises";
import path from "node:path";

const base = process.env.T3_PREVIEW_URL ?? "http://127.0.0.1:4196/t3/";
const workspace = process.env.T3_TEST_WORKSPACE ?? "/home/ubuntu/hmw-agent-lab/workspaces/t3";
const basename = process.env.T3_FIXTURE_BASENAME ?? ("filterrows-nested-" + randomUUID() + ".hpl");
if (!/^filterrows-nested-[a-f0-9-]{36}\.hpl$/.test(basename)) throw new Error("invalid test fixture basename");
const file = path.join(workspace, basename);
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = [], writes = [];
page.on("pageerror", error => errors.push(error.message));
page.on("response", response => {
  if (response.url().endsWith("/config/write")) writes.push(response.status());
});
function assert(ok, message) { if (!ok) throw new Error(message); }
async function api(endpoint, data) {
  const response = await page.request.post(
    new URL("api/pipelines" + endpoint, base).href, { data });
  assert(response.ok(), endpoint + " HTTP " + response.status());
  return response.json();
}
async function open() {
  await page.goto(base, { waitUntil: "networkidle" });
  await page.getByRole("button", { name: /打开流程/ }).first().click();
  await page.getByRole("textbox", { name: /服务器流程路径/ }).fill("/workspace/" + basename);
  const request = page.waitForResponse(response =>
    response.url().endsWith("/api/pipelines/open") && response.request().method() === "POST");
  await page.getByRole("button", { name: /打开文件/ }).first().click();
  const response = await request;
  assert(response.ok(), "browser open HTTP " + response.status());
  return (await response.json()).id;
}
async function form() {
  await page.locator(".react-flow__node").last().dblclick();
  const f = page.locator("form.generic-config");
  await f.waitFor();
  return f;
}

try {
  await copyFile(path.join(workspace, "example.hpl"), file);
  await page.goto(base, { waitUntil: "networkidle" });
  const initial = await api("/open", { path: "/workspace/" + basename });
  await api("/" + initial.id + "/edit/add",
    { nodeId: "filter-probe", pluginId: "FilterRows", x: 350, y: 230 });
  await api("/" + initial.id + "/save", {});
  let id = await open(), f = await form();
  const editor = f.getByRole("group", { name: "原生过滤条件配置" });
  assert(await editor.count() === 1, "FilterRows native form missing");
  await editor.getByRole("button", { name: "编辑过滤条件原始 JSON" }).click();
  const raw = editor.locator("textarea");
  const compare = JSON.parse(await raw.inputValue());
  const atomic = compare.condition;
  const first = { ...atomic, leftvalue: "water_level", rightvalue: "warning_level", function: ">" };
  const second = { ...atomic, leftvalue: "rainfall", rightvalue: "rainfall_limit", function: ">=", operator: "AND" };
  const third = { ...atomic, leftvalue: "rainfall", rightvalue: null, value: { name: "threshold", type: "Number", text: "5.0", length: 10, precision: 1, isnull: false }, function: ">", operator: "OR" };
  compare.condition = { ...atomic, leftvalue: null, rightvalue: null, conditions: { condition: [first, second, third] } };
  await raw.fill(JSON.stringify(compare));
  const request = page.waitForResponse(response =>
    response.url().endsWith("/config/write") && response.request().method() === "POST");
  await f.getByRole("button", { name: /保存配置/ }).click();
  const response = await request;
  assert(response.ok(), "config/write HTTP " + response.status());
  await api("/" + id + "/save", {});
  id = await open();
  const written = (await api("/" + id + "/config/read", { nodeId: "filter-probe" })).config;
  assert(written.compare.condition.conditions.condition.length === 3, "native children lost");
  assert(written.compare.condition.conditions.condition[1].operator === "AND", "native AND lost");
  assert(written.compare.condition.conditions.condition[2].operator === "OR", "native OR lost");
  f = await form();
  const composite = f.getByRole("group", { name: "过滤复合条件编辑" });
  assert(await composite.count() === 1, "composite native editor not shown");
  await composite.getByRole("textbox", { name: "条件2左侧字段" }).fill("rainfall_24h");
  await composite.getByRole("combobox", { name: "条件3逻辑连接" }).selectOption("AND");
  await composite.getByRole("textbox", { name: "条件3数字常量" }).fill("6.5");
  const change = page.waitForResponse(r => r.url().endsWith("/config/write") && r.request().method() === "POST");
  await f.getByRole("button", { name: /保存配置/ }).click();
  assert((await change).ok(), "composite write failed");
  await api("/" + id + "/save", {});
  id = await open();
  const persisted = (await api("/" + id + "/config/read", { nodeId: "filter-probe" })).config;
  const nodes = persisted.compare.condition.conditions.condition;
  assert(nodes[1].leftvalue === "rainfall_24h" && nodes[2].operator === "AND" && nodes[2].value?.text === "6.5", "composite edits lost");
  f = await form();
  const advanced = f.getByRole("group", { name: "原生过滤条件配置" });
  await advanced.getByRole("button", { name: "编辑过滤条件原始 JSON" }).click();
  const complexRaw = advanced.locator("textarea");
  const complex = JSON.parse(await complexRaw.inputValue());
  complex.condition.conditions.condition[1].conditions = { condition: [{ ...complex.condition.conditions.condition[0] }] };
  await complexRaw.fill(JSON.stringify(complex));
  await advanced.getByRole("button", { name: "返回过滤条件表单" }).click();
  assert(await f.getByRole("group", { name: "过滤复合条件编辑" }).count() === 0,
    "unknown nested tree must not enter structured editor");
  // Save the unknown nested tree through the real UI, then reopen the native Hop file.
  const nestedWrite = page.waitForResponse(r => r.url().endsWith("/config/write") && r.request().method() === "POST");
  await f.getByRole("button", { name: /保存配置/ }).click();
  assert((await nestedWrite).ok(), "nested condition write failed");
  await api("/" + id + "/save", {});
  id = await open();
  let preserved = (await api("/" + id + "/config/read", { nodeId: "filter-probe" })).config;
  assert(preserved.compare.condition.conditions.condition[1].conditions.condition.length === 1,
    "nested child lost on disk roundtrip");
  assert(preserved.compare.condition.conditions.condition[1].conditions.condition[0].leftvalue === "water_level",
    "nested child changed during XML roundtrip");
  f = await form();
  assert(await f.getByRole("group", { name: "过滤复合条件编辑" }).count() === 0,
    "reopened nested condition incorrectly enters flat editor");
  // Cancel after reopening, without altering the unknown nested tree.
  await f.getByRole("button", { name: /取消/ }).last().click();
  id = await open();
  preserved = (await api("/" + id + "/config/read", { nodeId: "filter-probe" })).config;
  assert(preserved.compare.condition.conditions.condition[1].conditions.condition[0].leftvalue === "water_level",
    "cancel after reopening changed nested condition");
  assert(writes.length === 3 && writes.every(x => x === 200),
    "unexpected write count/status " + JSON.stringify(writes));
  assert(errors.length === 0, "browser errors: " + errors.join("; "));
  console.log("T3_FIXTURE_PATH=" + file);
  console.log(JSON.stringify({ pass: true, plugin: "FilterRows",
    comparison: "water_level >= warning_level", trueTarget: "sink", falseTarget: "source",
    nativeDiskRoundtrip: true, writes, errors }));
} finally {
  await browser.close();
  if (process.env.T3_KEEP_FIXTURE !== "1") await unlink(file).catch(() => {});
}