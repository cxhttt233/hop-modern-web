/** Browser regression: real Pipeline save must not overwrite external .hpl edits.
 * Runs against active T5 UI + slot bridge + Java backend; never touches shared fixtures.
 */
import { chromium } from "playwright";
import { copyFile, readFile, writeFile, unlink } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";

const base = process.env.T5_PREVIEW_URL ?? "http://127.0.0.1:4196/t5/";
const workspace = process.env.T5_TEST_WORKSPACE ?? "/home/ubuntu/hmw-agent-lab/workspaces/t5";
const name = "t5-external-conflict-" + randomUUID() + ".hpl";
const file = path.join(workspace, name);
const check = (ok, detail) => { if (!ok) throw Error(detail); };
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = [];
page.on("pageerror", (error) => errors.push(error.message));

try {
  await copyFile(path.join(workspace, "example.hpl"), file);
  const original = await readFile(file);
  await page.goto(base, { waitUntil: "networkidle" });
  await page.getByRole("button", { name: /打开流程/ }).first().click();
  await page.getByRole("textbox", { name: /服务器流程路径/ }).fill("/workspace/" + name);
  const opened = page.waitForResponse((r) => r.url().endsWith("/api/pipelines/open") && r.request().method() === "POST");
  await page.getByRole("button", { name: /打开文件/ }).first().click();
  check((await opened).status() === 200, "Could not open actual Hop pipeline");
  await page.locator(".react-flow__node").first().waitFor();
  const beforeCount = await page.locator(".react-flow__node").count();
  await page.locator(".transform-list button").first().click();
  const save = page.getByRole("button", { name: "保存（Save）" });
  await page.waitForFunction(() => !document.querySelector('button[aria-label="保存（Save）"]')?.disabled);
  const externallyUpdated = Buffer.concat([original, Buffer.from("\n<!-- changed outside browser -->\n")]);
  await writeFile(file, externallyUpdated);
  const conflict = page.waitForResponse((r) => /\/api\/pipelines\/[a-f0-9]{64}\/save$/.test(new URL(r.url()).pathname));
  await save.click();
  check((await conflict).status() === 409, "External write must produce 409");
  await page.getByRole("button", { name: "重试保存当前流程" }).waitFor();
  check((await readFile(file)).equals(externallyUpdated), "External file was overwritten");
  await writeFile(file, original);
  const retryResponse = page.waitForResponse((r) => /\/api\/pipelines\/[a-f0-9]{64}\/save$/.test(new URL(r.url()).pathname));
  await page.getByRole("button", { name: "重试保存当前流程" }).click();
  check((await retryResponse).status() === 200, "Restored file retry must save");
  await page.getByText("已保存到服务器").waitFor();
  const reloadResponse = page.waitForResponse((r) =>
    r.url().endsWith("/api/pipelines/open") && r.request().method() === "POST");
  await page.getByRole("button", { name: /重新打开当前流程/ }).click();
  const diskOpen = await reloadResponse;
  check(diskOpen.status() === 200, "Disk reopen returned " + diskOpen.status());
  const diskGraph = await diskOpen.json();
  check(diskGraph.nodes?.length === beforeCount + 1,
    "Server reopened " + (diskGraph.nodes?.length ?? "unknown") + " nodes, expected " + (beforeCount + 1));
  await page.waitForFunction((before) =>
    document.querySelectorAll(".react-flow__node").length === before + 1, beforeCount, { timeout: 8000 });
  const afterCount = await page.locator(".react-flow__node").count();
  check(afterCount === beforeCount + 1, "Hop .hpl save/reopen lost new Transform");
  check(errors.length === 0, "Browser JS errors: " + errors.join("; "));
  console.log(JSON.stringify({ pass: true, conflict: 409, retry: 200, beforeCount, afterCount, diskReopened: true, errors }));
} finally {
  await browser.close();
  await unlink(file).catch(() => {});
}