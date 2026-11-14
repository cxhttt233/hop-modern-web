// A real Hop FilterRows native condition, GUI editing and .hpl disk roundtrip.
// Uses a UUID test copy so existing pipeline fixtures are never modified.
import { chromium } from "playwright";
import { randomUUID } from "node:crypto";
import { copyFile, unlink } from "node:fs/promises";
import path from "node:path";

const base = process.env.T3_PREVIEW_URL ?? "http://127.0.0.1:4196/t3/";
const workspace = process.env.T3_TEST_WORKSPACE ?? "/home/ubuntu/hmw-agent-lab/workspaces/t3";
const basename = "filterrows-atomic-" + randomUUID() + ".hpl";
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
  await editor.getByRole("textbox", { name: "过滤左侧字段" }).fill("water_level");
  await editor.getByRole("combobox", { name: "过滤比较方式" }).selectOption(">=");
  await editor.getByRole("textbox", { name: "过滤右侧字段" }).fill("warning_level");
  await f.locator('label[data-config-key="send_true_to"] input').fill("sink");
  await f.locator('label[data-config-key="send_false_to"] input').fill("source");
  assert(writes.length === 0, "editing issued config/write before Save");
  // Advanced JSON is always available for valid simple conditions.
  await editor.getByRole("button", { name: "编辑过滤条件原始 JSON" }).click();
  const raw = editor.locator("textarea");
  assert(await raw.count() === 1, "advanced JSON escape hatch missing");
  assert((await raw.inputValue()).includes("water_level"), "raw JSON lost draft");
  await editor.getByRole("button", { name: "返回过滤条件表单" }).click();
  assert(await editor.getByRole("textbox", { name: "过滤左侧字段" }).inputValue() === "water_level",
    "return from raw JSON lost input");

  const request = page.waitForResponse(response =>
    response.url().endsWith("/config/write") && response.request().method() === "POST");
  await f.getByRole("button", { name: /保存配置/ }).click();
  const response = await request;
  assert(response.ok(), "config/write HTTP " + response.status());
  await api("/" + id + "/save", {});
  id = await open();
  const written = (await api("/" + id + "/config/read", { nodeId: "filter-probe" })).config;
  assert(written.compare.condition.leftvalue === "water_level", "left field not persisted");
  assert(written.compare.condition.rightvalue === "warning_level", "right field not persisted");
  assert(written.compare.condition.function === ">=", "comparison operator not persisted");
  assert(written.send_true_to === "sink" && written.send_false_to === "source",
    "branch targets not persisted");
  f = await form();
  assert(await f.getByRole("textbox", { name: "过滤左侧字段" }).inputValue() === "water_level",
    "native editor failed after .hpl reopen");
  assert(writes.length === 1 && writes[0] === 200,
    "unexpected write count/status " + JSON.stringify(writes));
  assert(errors.length === 0, "browser errors: " + errors.join("; "));
  console.log(JSON.stringify({ pass: true, plugin: "FilterRows",
    comparison: "water_level >= warning_level", trueTarget: "sink", falseTarget: "source",
    nativeDiskRoundtrip: true, writes, errors }));
} finally {
  await browser.close();
  await unlink(file).catch(() => {});
}
