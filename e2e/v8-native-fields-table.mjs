import { chromium } from "playwright";
import { copyFileSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const fixture = process.env.REAL_HPL_PATH;
if (!fixture) throw new Error("REAL_HPL_PATH is required");
const directory = mkdtempSync(join(tmpdir(), "hop-native-list-"));
const realPath = join(directory, "native-fields.hpl");
copyFileSync(fixture, realPath);
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const isPost = (response, suffix) => response.request().method() === "POST" &&
  new URL(response.url()).pathname.endsWith(suffix);
const requests = [];
page.on("request", request => requests.push({ url: request.url(), method: request.method() }));
try {
  await page.goto("http://127.0.0.1:5173", { waitUntil: "networkidle" });
  const open = page.waitForResponse(response => isPost(response, "/api/pipelines/open"));
  await page.getByLabel("Server-visible pipeline path").fill(realPath);
  await page.getByRole("button", { name: "Open pipeline" }).click();
  if (!(await open).ok()) throw new Error("Real Hop TableInput fixture cannot open");
  await page.locator('.react-flow__node[data-id="table-input"]').click();
  const readPending = page.waitForResponse(response => isPost(response, "/config/read"));
  await page.getByRole("button", { name: "Configure" }).click();
  const read = await (await readPending).json();
  const contract = read.descriptor.wireLists?.find(item => item.descriptorPath === "field");
  if (contract?.listPointer !== "/fields/field" ||
      read.descriptor.properties.find(p => p.key === "field")?.shape !== "LIST") {
    throw new Error("Real Hop did not publish authoritative native field wire path");
  }
  const panel = page.locator("form.generic-config");
  const table = panel.getByRole("table", { name: "字段原生字段列表" });
  await table.waitFor({ state: "visible" });
  if (await table.locator("tbody tr").count() !== 0) {
    throw new Error("Native fixture must start with no TableInput field rows");
  }
  const writes = () => requests.filter(request =>
    request.method === "POST" && new URL(request.url).pathname.endsWith("/config/write")).length;
  const before = writes();
  await panel.getByRole("button", { name: "添加字段行" }).click();
  if (await table.locator("tbody tr").count() !== 1 || writes() !== before) {
    throw new Error("Adding a native field must be a local draft only");
  }
  await table.getByRole("textbox", { name: "字段 第1行 name" }).fill("黄河流量");
  await table.getByRole("spinbutton", { name: "字段 第1行 type" }).fill("2");
  await table.getByRole("spinbutton", { name: "字段 第1行 length" }).fill("18");
  await table.getByRole("textbox", { name: "字段 第1行 format" }).fill("0.00");
  if (writes() !== before) throw new Error("Native field edits wrote Hop before Save");
  const write = page.waitForResponse(response => isPost(response, "/config/write"));
  await panel.getByRole("button", { name: "保存配置（Save）" }).click();
  const writeResponse = await write;
  if (!writeResponse.ok()) {
    throw new Error("Hop rejected native field row: HTTP " + writeResponse.status());
  }
  const savedConfig = (await writeResponse.json()).config;
  if (savedConfig.fields?.field?.[0]?.name !== "黄河流量" ||
      savedConfig.fields?.field?.[0]?.type !== 2 ||
      savedConfig.fields?.field?.[0]?.length !== 18 ||
      savedConfig.fields?.field?.[0]?.precision !== -1 ||
      savedConfig.fields?.field?.[0]?.format !== "0.00" ||
      Object.hasOwn(savedConfig, "field") ||
      savedConfig.connection !== read.config.connection ||
      savedConfig.sql !== read.config.sql) {
    throw new Error("Native Hop LIST write mismatch: " + JSON.stringify({
      row: savedConfig.fields?.field?.[0] ?? null,
      nativeFieldCount: savedConfig.fields?.field?.length ?? null,
      rootFieldPresent: Object.hasOwn(savedConfig, "field"),
      connection: savedConfig.connection,
      sql: savedConfig.sql,
    }));
  }
  const save = page.waitForResponse(response => isPost(response, "/save"));
  await page.getByRole("button", { name: "保存（Save）", exact: true }).click();
  if (!(await save).ok()) throw new Error("Native fields disk save failed");
  const reopen = page.waitForResponse(response => isPost(response, "/api/pipelines/open"));
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  if (!(await reopen).ok()) throw new Error("Native fields .hpl disk reopen failed");
  await page.locator('.react-flow__node[data-id="table-input"]').click();
  const reread = page.waitForResponse(response => isPost(response, "/config/read"));
  await page.getByRole("button", { name: "Configure" }).click();
  const persisted = (await (await reread).json()).config;
  if (persisted.fields?.field?.[0]?.name !== "黄河流量" ||
      persisted.fields?.field?.[0]?.type !== 2 ||
      persisted.fields?.field?.[0]?.length !== 18 ||
      persisted.fields?.field?.[0]?.precision !== -1 ||
      persisted.fields?.field?.[0]?.format !== "0.00" ||
      Object.hasOwn(persisted, "field") ||
      persisted.connection !== read.config.connection ||
      persisted.sql !== read.config.sql) {
    throw new Error("Native fields not preserved across actual Hop .hpl disk reopen");
  }
  await table.waitFor({ state: "visible" });
  await table.getByRole("textbox", { name: "字段 第1行 name" }).fill("未保存的草稿");
  await panel.getByRole("button", { name: "取消配置（Cancel）" }).click();
  await page.locator('.react-flow__node[data-id="table-input"]').click();
  const canceledRead = page.waitForResponse(response => isPost(response, "/config/read"));
  await page.getByRole("button", { name: "Configure" }).click();
  if ((await (await canceledRead).json()).config.fields?.field?.[0]?.name !== "黄河流量") {
    throw new Error("Cancel corrupted the authoritative Hop field list");
  }
  if (requests.some(request => {
    const pathname = new URL(request.url).pathname;
    return pathname === "/ui" || pathname.startsWith("/ui/");
  })) throw new Error("Native field list editor entered RAP /ui");
  console.log("V8 native TableInput list proof passed: real serializer pointer -> typed React row -> config/write -> HPL disk reopen; Cancel keeps Hop untouched.");
} finally {
  await browser.close();
  rmSync(directory, { recursive: true, force: true });
}
