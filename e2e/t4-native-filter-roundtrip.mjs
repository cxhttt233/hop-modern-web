// Browser round-trip for filtered native rows. Only writes a disposable fixture.
import { chromium } from "playwright";
import { copyFile, unlink } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";

const base = process.env.T4_PREVIEW_URL ?? "http://127.0.0.1:4196/t4/";
const workspace = process.env.T4_TEST_WORKSPACE ?? "/home/ubuntu/hmw-agent-lab/workspaces/t4";
const basename = "filter-e2e-" + randomUUID() + ".hpl";
const fixture = path.join(workspace, basename);
const documentPath = "/workspace/" + basename;
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = [];
page.on("pageerror", error => errors.push(error.message));
let documentId;
async function open() {
  await page.goto(base, { waitUntil: "networkidle" });
  await page.getByRole("button", { name: /打开流程/ }).first().click();
  await page.getByRole("textbox", { name: /服务器流程路径/ }).fill(documentPath);
  const request = page.waitForResponse(response =>
    response.url().endsWith("/api/pipelines/open") && response.request().method() === "POST");
  await page.getByRole("button", { name: /打开文件/ }).first().click();
  const response = await request;
  if (!response.ok()) throw new Error("Open failed: " + response.status());
  documentId = (await response.json()).id;
}
async function editPanel() {
  await page.locator(".react-flow__node").first().dblclick();
  const panel = page.locator("form.generic-config");
  await panel.waitFor();
  return panel;
}
async function saveConfig(panel) {
  const request = page.waitForResponse(response =>
    response.url().endsWith("/config/write") && response.request().method() === "POST");
  await panel.getByRole("button", { name: /保存配置/ }).click();
  const response = await request;
  if (!response.ok()) throw new Error("Config write failed: " + response.status());
}
async function saveDocument() {
  const response = await page.request.post(new URL("api/pipelines/" + documentId + "/save", base));
  if (!response.ok()) throw new Error("Document save failed: " + response.status());
}
async function names() {
  const response = await page.request.post(new URL("api/pipelines/" + documentId + "/config/read", base),
    { data: { nodeId: "table-input" } });
  if (!response.ok()) throw new Error("Config read failed: " + response.status());
  return ((await response.json()).config?.fields?.field ?? []).map(row => row.name);
}
function equal(actual, expected, stage) {
  if (JSON.stringify(actual) !== JSON.stringify(expected))
    throw new Error(stage + ": " + JSON.stringify(actual) + " expected " + JSON.stringify(expected));
}
try {
  await copyFile(path.join(workspace, "table-input.hpl"), fixture);
  await open();
  let panel = await editPanel();
  for (const [index, name] of ["T4_A", "T4_B", "T4_C"].entries()) {
    await panel.getByRole("button", { name: "添加字段行" }).click();
    await panel.getByRole("textbox", { name: new RegExp("第" + (index + 1) + "行 字段名称") }).fill(name);
  }
  await saveConfig(panel); await saveDocument();
  equal(await names(), ["T4_A", "T4_B", "T4_C"], "initial write");
  await open(); panel = await editPanel();
  await panel.getByRole("searchbox", { name: "字段 搜索字段行" }).fill("T4_B");
  const table = panel.locator('table[aria-label="字段原生字段列表"] tbody tr');
  if (await table.count() !== 1) throw new Error("Filter did not narrow to one row");
  if (!await panel.getByRole("button", { name: "字段 上移第2行" }).isDisabled())
    throw new Error("Sorting filtered rows is unsafe");
  await panel.getByRole("textbox", { name: /第2行 字段名称/ }).fill("T4_B_EDITED");
  await saveConfig(panel); await saveDocument();
  equal(await names(), ["T4_A", "T4_B_EDITED", "T4_C"], "filtered edit");
  await open(); panel = await editPanel();
  await panel.getByRole("searchbox", { name: "字段 搜索字段行" }).fill("T4_B_EDITED");
  await panel.getByRole("button", { name: "字段 删除第2行" }).click();
  await saveConfig(panel); await saveDocument();
  equal(await names(), ["T4_A", "T4_C"], "filtered delete");
  if (errors.length) throw new Error("Browser errors: " + errors.join("; "));
  console.log(JSON.stringify({ pass: true, test: "filter, safe index, save and reopen", errors }));
} finally {
  await browser.close();
  await unlink(fixture).catch(() => {});
}
