// Reproducible browser regression for native number drafts and real Hop persistence.
// Writes only a disposable copy of an existing T4 TableInput test fixture.
import { chromium } from "playwright";
import { copyFile, unlink } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";

const base = process.env.T4_PREVIEW_URL ?? "http://127.0.0.1:4196/t4/";
const workspace = process.env.T4_TEST_WORKSPACE ?? "/home/ubuntu/hmw-agent-lab/workspaces/t4";
const basename = "number-draft-" + randomUUID() + ".hpl";
const fixture = path.join(workspace, basename);
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = []; let writes = 0;
page.on("pageerror", error => errors.push(error.message));
page.on("request", request => { if (request.url().includes("/config/write")) writes++; });
const check = (ok, message) => { if (!ok) throw new Error(message); };
async function open() {
  await page.goto(base, { waitUntil: "networkidle" });
  await page.getByRole("button", { name: /打开流程/ }).first().click();
  await page.getByRole("textbox", { name: /服务器流程路径/ }).fill("/workspace/" + basename);
  const request = page.waitForResponse(response =>
    response.url().endsWith("/api/pipelines/open") && response.request().method() === "POST");
  await page.getByRole("button", { name: /打开文件/ }).first().click();
  const response = await request;
  check(response.ok(), "open HTTP " + response.status());
  return (await response.json()).id;
}
async function panel() {
  await page.locator(".react-flow__node").first().dblclick();
  const form = page.locator("form.generic-config");
  await form.waitFor();
  return form;
}
try {
  await copyFile(path.join(workspace, "table-input.hpl"), fixture);
  let id = await open(), form = await panel();
  await form.getByRole("button", { name: "添加字段行" }).click();
  await form.getByRole("textbox", { name: /第1行 字段名称/ }).fill("NUM_E2E");
  const numeric = form.getByRole("textbox", { name: /第1行 长度/ });
  const original = await numeric.inputValue();
  check(original === "-1", "unexpected native length default: " + original);
  await numeric.fill("-");
  check(await numeric.inputValue() === "-", "incomplete minus changed");
  await numeric.press("Escape");
  check(await numeric.inputValue() === original, "Escape must restore original");
  await numeric.fill("1.");
  check(await numeric.inputValue() === "1.", "decimal intermediate changed");
  await numeric.press("Escape");
  check(await numeric.inputValue() === original, "Escape failed on decimal");
  await numeric.fill("abc"); await numeric.press("Tab");
  check(await numeric.inputValue() === original, "invalid number did not revert");
  await numeric.fill("-12.5"); await numeric.press("Tab");
  check(await numeric.inputValue() === "-12.5", "negative decimal not accepted in editor");
  await numeric.fill("-42"); await numeric.press("Tab");
  check(await numeric.inputValue() === "-42", "negative integer commit failed");
  await numeric.fill("-99"); await numeric.press("Escape");
  check(await numeric.inputValue() === "-42", "Escape after commit failed");
  check(writes === 0, "numeric interactions wrote config prematurely");
  const request = page.waitForResponse(response =>
    response.url().endsWith("/config/write") && response.request().method() === "POST");
  await form.getByRole("button", { name: /保存配置/ }).click();
  const response = await request;
  check(response.ok(), "config write HTTP " + response.status());
  const saved = await page.request.post(new URL("api/pipelines/" + id + "/save", base).href);
  check(saved.ok(), "document save HTTP " + saved.status());
  id = await open(); form = await panel();
  const persisted = await form.getByRole("textbox", { name: /第1行 长度/ }).inputValue();
  check(persisted === "-42", "disk reopen lost numeric value: " + persisted);
  check(errors.length === 0, "JavaScript errors: " + errors.join("; "));
  console.log(JSON.stringify({ pass: true, incompleteDrafts: true, escape: true,
    invalidReverts: true, decimalCommit: true, savedLength: persisted, writes, errors }));
} finally {
  await browser.close();
  await unlink(fixture).catch(() => {});
}
