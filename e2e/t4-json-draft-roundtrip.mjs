// T4 native SelectValues JSON fallback: typed text, invalid/cancel, field reset,
// real Hop .hpl persistence and reopening. Only touches a UUID fixture.
import { chromium } from "playwright";
import { copyFile, unlink, readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";

const base = process.env.T4_PREVIEW_URL ?? "http://127.0.0.1:4196/t4/";
const workspace = process.env.T4_TEST_WORKSPACE ?? "/home/ubuntu/hmw-agent-lab/workspaces/t4";
const basename = "json-draft-" + randomUUID() + ".hpl";
const fixture = path.join(workspace, basename);
const browser = await chromium.launch({ headless: true, args: ["--no-sandbox"] });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = [];
let id, writes = 0;
page.on("pageerror", error => errors.push(error.message));
page.on("request", request => {
  if (request.method() === "POST" && request.url().endsWith("/config/write")) writes++;
});
function assert(ok, why) { if (!ok) throw new Error(why); }
async function open() {
  await page.goto(base, { waitUntil: "networkidle" });
  await page.getByRole("button", { name: /打开流程/ }).first().click();
  await page.getByRole("textbox", { name: /服务器流程路径/ }).fill("/workspace/" + basename);
  const response = page.waitForResponse(r => r.url().endsWith("/api/pipelines/open") && r.request().method() === "POST");
  await page.getByRole("button", { name: /打开文件/ }).first().click();
  const loaded = await response;
  assert(loaded.status() === 200, "native .hpl open: " + loaded.status());
  id = (await loaded.json()).id;
}
async function rawEditor() {
  await page.locator(".react-flow__node").first().dblclick();
  const panel = page.locator("form.generic-config");
  await panel.waitFor();
  await panel.getByRole("button", { name: "编辑字段原始 JSON" }).click();
  const raw = panel.locator("textarea#config-selectOption");
  await raw.waitFor();
  return { panel, raw };
}
const baseData = { remove: [], meta: [], field: [], select_unspecified: false };
try {
  await copyFile(path.join(workspace, "select-values.hpl"), fixture);
  await open();
  let { panel, raw } = await rawEditor();
  const original = JSON.parse(await raw.inputValue());
  assert(JSON.stringify(original) === JSON.stringify(baseData), "unexpected native fixture shape");
  const typed = JSON.stringify({ ...original, select_unspecified: true }) + "\n  ";
  await raw.fill(typed);
  assert(await raw.inputValue() === typed, "valid JSON was reformatted / cursor draft overwritten");
  assert(await panel.getByRole("button", { name: /保存配置/ }).isEnabled(), "valid JSON blocked save");
  const bad = typed + "{";
  await raw.fill(bad);
  assert(await raw.inputValue() === bad, "incomplete JSON disappeared");
  assert(await panel.getByRole("button", { name: /保存配置/ }).isDisabled(), "invalid JSON allowed save");
  await raw.fill(typed);
  assert(await raw.inputValue() === typed, "corrected JSON lost literal whitespace");
  const beforeCancel = writes;
  await panel.getByRole("button", { name: /取消配置/ }).click();
  assert(writes === beforeCancel, "cancel wrote native config");
  const xmlAfterCancel = await readFile(fixture, "utf8");
  assert(xmlAfterCancel.includes("<select_unspecified>N</select_unspecified>"), "cancel changed disk fixture");

  ({ panel, raw } = await rawEditor());
  await raw.fill(typed);
  await panel.getByText("查看未保存字段").click();
  const reset = panel.getByRole("button", { name: /撤销.*的修改/ });
  assert(await reset.count() === 1, "changed-field reset action missing");
  await reset.click();
  await panel.getByRole("button", { name: "编辑字段原始 JSON" }).click();
  raw = panel.locator("textarea#config-selectOption");
  assert(JSON.parse(await raw.inputValue()).select_unspecified === false, "reset left stale JSON draft visible");
  await raw.fill(typed);
  const configWrite = page.waitForResponse(r => r.url().endsWith("/config/write") && r.request().method() === "POST");
  await panel.getByRole("button", { name: /保存配置/ }).click();
  assert((await configWrite).status() === 200, "native config write failed");
  const save = await page.request.post(new URL("api/pipelines/" + id + "/save", base).href);
  assert(save.status() === 200, "real .hpl save failed: " + save.status());
  assert((await readFile(fixture, "utf8")).includes("<select_unspecified>Y</select_unspecified>"), "native XML persistence missing");
  await open();
  ({ panel, raw } = await rawEditor());
  assert(JSON.parse(await raw.inputValue()).select_unspecified === true, "native .hpl reopened without change");
  assert(errors.length === 0, "browser JS errors: " + errors.join("; "));
  console.log(JSON.stringify({ pass: true, native: "SelectValues", exactRawDraft: true, invalidGuard: true,
    cancelNoWrite: true, resetRestoresView: true, nativeDiskReopen: true, browserErrors: errors }));
} finally {
  await browser.close();
  await unlink(fixture).catch(() => {});
}