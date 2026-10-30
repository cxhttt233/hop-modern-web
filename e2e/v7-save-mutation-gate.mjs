import { chromium } from "playwright";
import { copyFileSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const fixture = process.env.REAL_HPL_PATH;
if (!fixture) throw new Error("REAL_HPL_PATH is required");
const workdir = mkdtempSync(join(tmpdir(), "hop-modern-save-gate-"));
const pipelinePath = join(workdir, "save-gate.hpl");
copyFileSync(fixture, pipelinePath);
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const post = (response, suffix) =>
  response.request().method() === "POST" && new URL(response.url()).pathname.endsWith(suffix);
try {
  await page.goto("http://127.0.0.1:5173", { waitUntil: "networkidle" });
  const openedPending = page.waitForResponse(r => post(r, "/api/pipelines/open"));
  await page.getByLabel("Server-visible pipeline path").fill(pipelinePath);
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const openedResponse = await openedPending;
  const opened = await openedResponse.json();
  if (!openedResponse.ok() || !opened.nodes.some(n => n.id === "table-input")) {
    throw new Error("V7 must open a real TableInput .hpl");
  }

  const node = page.locator('.react-flow__node[data-id="table-input"]');
  await node.waitFor({ state: "visible" });
  await node.click();
  const readPending = page.waitForResponse(r => post(r, "/config/read"));
  await page.getByRole("button", { name: "Configure" }).click();
  const initial = await (await readPending).json();
  const sqlProperty = initial.descriptor.properties.find(p => p.key === "sql");
  if (!sqlProperty) throw new Error("Real SQL descriptor missing");
  const panel = page.locator("form.generic-config");
  const sqlField = panel.locator(`#config-${sqlProperty.javaField}`);
  const toolbarSave = page.getByRole("button", { name: "保存（Save）", exact: true });
  await panel.waitFor({ state: "visible" });

  // First successful write makes the opened Pipeline dirty.
  const firstSql = "SELECT 'pending-A' AS marker";
  await sqlField.fill(firstSql);
  const firstWrite = page.waitForResponse(r => post(r, "/config/write"));
  const firstRead = page.waitForResponse(r => post(r, "/config/read"));
  await panel.getByRole("button", { name: "保存配置（Save）", exact: true }).click();
  if (!(await firstWrite).ok() || !(await firstRead).ok()) {
    throw new Error("V7 initial authoritative config mutation failed");
  }
  await page.locator(".dirty-state.is-dirty").waitFor({ state: "visible" });
  await toolbarSave.waitFor({ state: "visible" });
  if (!(await toolbarSave.isEnabled())) throw new Error("Successful config mutation must enable Pipeline Save");

  // Hold a second HTTP mutation while the Pipeline is already dirty. The
  // toolbar must not persist an older snapshot or clear the subsequent dirty state.
  let startedWrite;
  const started = new Promise(resolve => { startedWrite = resolve; });
  let releaseWrite;
  const release = new Promise(resolve => { releaseWrite = resolve; });
  await page.route("**/api/pipelines/**/config/write", async route => {
    startedWrite();
    await release;
    await route.continue();
  });
  let failRefresh = true;
  await page.route("**/api/pipelines/**/config/read", async route => {
    if (failRefresh) {
      failRefresh = false;
      await route.fulfill({ status: 503, contentType: "application/json",
        body: JSON.stringify({ code: "test_refresh_unavailable" }) });
    } else {
      await route.continue();
    }
  });

  const secondSql = "SELECT 'pending-B-黄河' AS marker";
  await sqlField.fill(secondSql);
  const secondWrite = page.waitForResponse(r => post(r, "/config/write"));
  const failedRead = page.waitForResponse(r => post(r, "/config/read") && r.status() === 503);
  await panel.getByRole("button", { name: "保存配置（Save）", exact: true }).click();
  await started;
  await page.waitForFunction(() =>
    document.querySelector('button[aria-label="保存（Save）"]')?.disabled === true);
  if (await toolbarSave.isEnabled()) throw new Error("Pipeline Save enabled during pending config mutation");

  // Closing the form changes its UI generation; the committed server mutation
  // must still mark the document dirty, even when config refresh then fails.
  await panel.getByRole("button", { name: "取消配置（Cancel）", exact: true }).click();
  releaseWrite();
  const writtenResponse = await secondWrite;
  const refreshResponse = await failedRead;
  if (!writtenResponse.ok() || refreshResponse.status() !== 503) {
    throw new Error("V7 did not exercise accepted write plus rejected refresh");
  }
  await page.waitForFunction(() =>
    document.querySelector('button[aria-label="保存（Save）"]')?.disabled === false);
  await page.locator(".dirty-state.is-dirty").waitFor({ state: "visible" });
  const savePending = page.waitForResponse(r => post(r, "/save"));
  await toolbarSave.click();
  if (!(await savePending).ok()) throw new Error("V7 disk save failed");
  await page.locator(".dirty-state.is-dirty").waitFor({ state: "hidden" });

  const reopenedPending = page.waitForResponse(r => post(r, "/api/pipelines/open"));
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  if (!(await reopenedPending).ok()) throw new Error("V7 disk reopen failed");
  await node.waitFor({ state: "visible" });
  await node.click();
  const rereadPending = page.waitForResponse(r => post(r, "/config/read"));
  await page.getByRole("button", { name: "Configure" }).click();
  const authoritative = await (await rereadPending).json();
  if (authoritative.config?.sql !== secondSql) {
    throw new Error("V7 lost config write after Cancel/read failure/save/reopen");
  }
  console.log("V7 save mutation gate passed: no stale save, accepted write survives Cancel and refresh failure, disk reopen preserves SQL.");
} finally {
  await browser.close();
  rmSync(workdir, { recursive: true, force: true });
}
