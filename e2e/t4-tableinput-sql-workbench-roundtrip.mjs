// Real TableInput SQL synchronization, cancel, native .hpl save/reopen.
import { chromium } from "playwright";
import { copyFile, unlink, readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";

const base = process.env.T4_PREVIEW_URL ?? "http://127.0.0.1:4196/t4/";
const workspace = process.env.T4_TEST_WORKSPACE ?? "/home/ubuntu/hmw-agent-lab/workspaces/t4";
const basename = process.env.T4_FIXTURE_BASENAME ?? ("table-input-sql-" + randomUUID() + ".hpl");
if (!/^table-input-sql-[a-f0-9-]{36}\.hpl$/.test(basename)) throw new Error("invalid TableInput test fixture basename");
const fixture = path.join(workspace, basename);
const browser = await chromium.launch({ headless: true, args: ["--no-sandbox"] });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = [], writes = [];
page.on("pageerror", error => errors.push(error.message));
page.on("response", response => {
  if (response.url().endsWith("/config/write")) writes.push(response.status());
});
function assert(ok, message) { if (!ok) throw new Error(message); }
async function api(endpoint, data) {
  const response = await page.request.post(new URL("api/pipelines" + endpoint, base).href, { data });
  assert(response.ok(), endpoint + " HTTP " + response.status());
  return response.json();
}
async function open() {
  await page.goto(base, { waitUntil: "networkidle" });
  await page.getByRole("button", { name: /打开流程/ }).first().click();
  await page.getByRole("textbox", { name: /服务器流程路径/ }).fill("/workspace/" + basename);
  const response = page.waitForResponse(r => r.url().endsWith("/api/pipelines/open") && r.request().method() === "POST");
  await page.getByRole("button", { name: /打开文件/ }).first().click();
  const result = await response;
  assert(result.ok(), "open .hpl HTTP " + result.status());
  return (await result.json()).id;
}
async function panel() {
  await page.locator(".react-flow__node").first().dblclick();
  const form = page.locator("form.generic-config");
  await form.waitFor();
  await form.locator("#config-sql").waitFor();
  return form;
}
async function openMonaco(form) {
  const details = form.locator("details.config-sql-workbench");
  if (!(await details.evaluate(el => el.open))) await details.locator("summary").click();
  const editor = form.locator(".monaco-editor");
  await editor.waitFor({ state: "visible", timeout: 15000 });
  await page.waitForFunction(() => {
    const field = document.querySelector("#config-sql");
    const view = document.querySelector(".monaco-editor .view-lines");
    const normalize = value => value.split(String.fromCharCode(160)).join("").split(" ").join("").split(String.fromCharCode(10)).join("").split(String.fromCharCode(13)).join("");
    return Boolean(field && view && normalize(view.innerText) === normalize(field.value));
  });
  return editor;
}
async function monacoReplace(editor, sql) {
  await editor.click();
  await page.keyboard.press("Control+A");
  await page.keyboard.type(sql, { delay: 0 });
  const field = page.locator("#config-sql");
  await page.waitForFunction(expected => document.querySelector("#config-sql")?.value === expected, sql);
  assert(await field.inputValue() === sql, "Monaco and authoritative SQL textarea diverged");
}
try {
  await copyFile(path.join(workspace, "table-input.hpl"), fixture);
  let id = await open();
  let form = await panel();
  const original = await form.locator("#config-sql").inputValue();
  assert(original === "SELECT id, name FROM customers", "unexpected native SQL fixture");
  let editor = await openMonaco(form);
  const firstDraft = "SELECT id, name, created_at\nFROM customers\nWHERE active = 1\nORDER BY created_at DESC;";
  await monacoReplace(editor, firstDraft);
  await form.locator("details.config-sql-workbench summary").click();
  assert(await form.locator("#config-sql").inputValue() === firstDraft, "collapse lost SQL draft");
  editor = await openMonaco(form);
  assert((await editor.locator(".view-lines").innerText()).replaceAll(String.fromCharCode(160), " ").includes("WHERE active = 1"), "Monaco reopen did not reflect draft");

  const secondDraft = "SELECT customer_id, total_amount\nFROM invoices\nWHERE total_amount > 0\nORDER BY total_amount DESC;";
  await form.locator("#config-sql").fill(secondDraft);
  editor = await openMonaco(form);
  assert((await editor.locator(".view-lines").innerText()).includes("customer_id"), "raw textarea -> Monaco sync failed");
  const beforeCancelWrites = writes.length;
  await monacoReplace(editor, "SELECT * FROM invoices WHERE");
  await form.getByRole("button", { name: /取消配置/ }).click();
  assert(writes.length === beforeCancelWrites, "cancel unexpectedly wrote config");
  id = await open();
  let stored = (await api("/" + id + "/config/read", { nodeId: "table-input" })).config;
  assert(stored.sql === original, "cancel modified native SQL on disk");

  form = await panel();
  editor = await openMonaco(form);
  const finalSql = "SELECT customer_id, total_amount, currency\nFROM invoices\nWHERE total_amount >= 125.50 AND status = 'PAID'\nORDER BY total_amount DESC;";
  await monacoReplace(editor, finalSql);
  const configWrite = page.waitForResponse(r => r.url().endsWith("/config/write") && r.request().method() === "POST");
  await form.getByRole("button", { name: /保存配置/ }).click();
  assert((await configWrite).status() === 200, "config/write failed");
  await api("/" + id + "/save", {});
  id = await open();
  stored = (await api("/" + id + "/config/read", { nodeId: "table-input" })).config;
  assert(stored.sql === finalSql, "saved SQL differs after native .hpl reopen");
  assert(errors.length === 0, "browser errors: " + JSON.stringify(errors));
  assert(writes.length === beforeCancelWrites + 1 && writes.every(x => x === 200), "unexpected config write sequence " + JSON.stringify(writes));
  const xml = await readFile(fixture, "utf8");
  assert(xml.includes("125.50") && xml.includes("PAID"), "native .hpl XML does not contain committed SQL");
  console.log("T4_FIXTURE_PATH=" + fixture);
  console.log(JSON.stringify({ pass: true, transform: "TableInput", monacoToRaw: true, rawToMonaco: true,
    collapseReopen: true, cancelNoWrite: true, nativeSaveReopen: true, writes, errors }));
} finally {
  await browser.close();
  if (process.env.T4_KEEP_FIXTURE !== "1") await unlink(fixture).catch(() => {});
}