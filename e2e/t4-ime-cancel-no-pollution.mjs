// Native IME editing, Escape rollback, actual Hop .hpl disk roundtrip.
import { chromium } from "playwright";
import { copyFile, unlink } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";

const base = process.env.T4_PREVIEW_URL ?? "http://127.0.0.1:4196/t4/";
const workspace = process.env.T4_TEST_WORKSPACE ?? "/home/ubuntu/hmw-agent-lab/workspaces/t4";
const basename = "ime-roundtrip-" + randomUUID() + ".hpl";
const filename = path.join(workspace, basename);
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = [];
let writes = 0;
page.on("pageerror", error => errors.push(error.message));
page.on("request", request => { if (request.url().includes("/config/write")) writes++; });

function assert(ok, message) { if (!ok) throw new Error(message); }
async function open() {
  await page.goto(base, { waitUntil: "networkidle" });
  await page.getByRole("button", { name: /打开流程/ }).first().click();
  await page.getByRole("textbox", { name: /服务器流程路径/ }).fill("/workspace/" + basename);
  const request = page.waitForResponse(response =>
    response.url().endsWith("/api/pipelines/open") && response.request().method() === "POST");
  await page.getByRole("button", { name: /打开文件/ }).first().click();
  const response = await request;
  assert(response.ok(), "Pipeline open failed with HTTP " + response.status());
  return (await response.json()).id;
}
async function configPanel() {
  await page.locator(".react-flow__node").first().dblclick();
  const panel = page.locator("form.generic-config");
  await panel.waitFor();
  return panel;
}
try {
  await copyFile(path.join(workspace, "select-values.hpl"), filename);
  let id = await open(), form = await configPanel();
  await form.getByRole("button", { name: "添加字段行" }).first().click();
  const name = form.locator('table[aria-label$="原生字段列表"] input[type="text"]').first();
  await name.focus();
  await name.evaluate(element => element.dispatchEvent(new CompositionEvent("compositionstart",
    { bubbles: true, data: "zhong" })));
  await name.fill("zhong");
  assert(writes === 0, "Pinyin composition wrote native config prematurely");
  await name.evaluate(element => element.dispatchEvent(new CompositionEvent("compositionend",
    { bubbles: true, data: "中文" })));
  await name.fill("中文测试");
  await name.press("Tab");
  assert(await name.inputValue() === "中文测试", "Composition commit did not preserve Chinese text");
  await name.fill("应取消");
  await name.press("Escape");
  assert(await name.inputValue() === "中文测试", "Escape did not restore committed Chinese text");
  assert(writes === 0, "Input/cancel accidentally wrote to server");

  const request = page.waitForResponse(response =>
    response.url().endsWith("/config/write") && response.request().method() === "POST");
  await form.getByRole("button", { name: /保存配置/ }).click();
  const savedConfig = await request;
  assert(savedConfig.ok(), "Config save failed with HTTP " + savedConfig.status());
  const savedDisk = await page.request.post(
    new URL("api/pipelines/" + id + "/save", base).href);
  assert(savedDisk.ok(), "HPL disk save failed with HTTP " + savedDisk.status());
  id = await open(); form = await configPanel();
  const actual = await form.locator('table[aria-label$="原生字段列表"] input[type="text"]').first().inputValue();
  assert(actual === "中文测试", "HPL reopen lost Chinese text: " + JSON.stringify(actual));
  assert(writes === 1, "Expected exactly one config write, got " + writes);
  assert(errors.length === 0, "Browser errors: " + errors.join("; "));
  console.log(JSON.stringify({ pass: true, ime: true, escape: true,
    diskRoundtrip: true, savedName: actual, configWrites: writes, errors }));
} finally {
  await browser.close();
  await unlink(filename).catch(() => {});
}
