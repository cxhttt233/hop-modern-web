// A real Hop FilterRows native condition, GUI editing and .hpl disk roundtrip.
// Uses a UUID test copy so existing pipeline fixtures are never modified.
import { chromium } from "playwright";
import { randomUUID } from "node:crypto";
import { copyFile, unlink } from "node:fs/promises";
import path from "node:path";

const base = process.env.T3_PREVIEW_URL ?? "http://127.0.0.1:4196/t3/";
const workspace = process.env.T3_TEST_WORKSPACE ?? "/home/ubuntu/hmw-agent-lab/workspaces/t3";
const basename = "filterrows-cancel-" + randomUUID() + ".hpl";
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
  // A cancelled native Condition draft must not issue config/write or mutate Hop XML.
  const before = (await api("/" + id + "/config/read", { nodeId: "filter-probe" })).config;
  await editor.getByRole("textbox", { name: "过滤左侧字段" }).fill("cancelled_probe");
  await editor.getByRole("combobox", { name: "过滤比较方式" }).selectOption(">=");
  await f.getByRole("button", { name: /取消/ }).click();
  assert(writes.length === 0, "cancel unexpectedly issued config/write");
  const afterCancel = (await api("/" + id + "/config/read", { nodeId: "filter-probe" })).config;
  assert(JSON.stringify(afterCancel) === JSON.stringify(before), "cancel changed native Condition");
  await api("/" + id + "/save", {});
  id = await open();
  const reopened = (await api("/" + id + "/config/read", { nodeId: "filter-probe" })).config;
  assert(JSON.stringify(reopened.compare) === JSON.stringify(before.compare), "cancel changed .hpl Condition after reopen");
  f = await form();
  assert(await f.getByRole("textbox", { name: "过滤左侧字段" }).inputValue() !== "cancelled_probe",
    "cancelled draft appeared in reopened form");
  assert(errors.length === 0, "browser errors: " + errors.join("; "));
  console.log(JSON.stringify({ pass: true, plugin: "FilterRows", cancelledNativeDraft: true,
    nativeDiskRoundtrip: true, writes, errors }));
} finally {
  await browser.close();
  await unlink(file).catch(() => {});
}