import { chromium } from "playwright";
import { copyFileSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const fixture = process.env.REAL_HPL_PATH;
if (!fixture) throw new Error("REAL_HPL_PATH is required");
const directory = mkdtempSync(join(tmpdir(), "hop-selectvalues-v9-"));
const realPath = join(directory, "native-selectvalues.hpl");
copyFileSync(fixture, realPath);
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const post = (r, end) => r.request().method() === "POST" &&
  new URL(r.url()).pathname.endsWith(end);
const requests = [];
page.on("request", request => requests.push({ path: new URL(request.url()).pathname, method: request.method() }));
try {
  await page.goto("http://127.0.0.1:5173", { waitUntil: "networkidle" });
  const open = page.waitForResponse(r => post(r, "/api/pipelines/open"));
  await page.getByLabel("Server-visible pipeline path").fill(realPath);
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const loaded = await open;
  if (!loaded.ok()) throw new Error("Pinned Hop SelectValues .hpl could not open: HTTP " + loaded.status());
  await page.locator('.react-flow__node[data-id="select-values"]').click();
  const readPending = page.waitForResponse(r => post(r, "/config/read"));
  await page.getByRole("button", { name: "Configure" }).click();
  const readResponse = await readPending;
  if (!readResponse.ok()) throw new Error("SelectValues Hop config/read failed");
  const read = await readResponse.json();
  const pointerMap = Object.fromEntries((read.descriptor?.wireLists ?? []).map(c => [c.descriptorPath, c.listPointer]));
  if (read.descriptor.className !== "org.apache.hop.pipeline.transforms.selectvalues.SelectValuesMeta" ||
      pointerMap["fields.field"] !== "/fields/field" ||
      pointerMap["fields.remove"] !== "/fields/remove" ||
      pointerMap["fields.meta"] !== "/fields/meta") {
    throw new Error("SelectValues real Hop native three-list contract missing");
  }
  const panel = page.locator("form.generic-config");
  const selected = panel.getByRole("region", { name: "选取与重命名字段" });
  const removed = panel.getByRole("region", { name: "移除字段" });
  const changed = panel.getByRole("region", { name: "转换字段元数据" });
  await selected.getByRole("table").waitFor({ state: "visible" });
  await removed.getByRole("table").waitFor({ state: "visible" });
  await changed.getByRole("table").waitFor({ state: "visible" });
  const writes = () => requests.filter(r => r.method === "POST" && r.path.endsWith("/config/write")).length;
  const before = writes();

  await selected.getByRole("button", { name: "添加字段行" }).click();
  await selected.getByRole("textbox", { name: "选取与重命名字段 第1行 name" }).fill("上游流量");
  await selected.getByRole("textbox", { name: "选取与重命名字段 第1行 rename" }).fill("flow_before");
  if (await selected.getByRole("spinbutton", { name: "选取与重命名字段 第1行 length" }).inputValue() !== "-2") {
    throw new Error("SelectField native undefined length should be -2, not 0 or -1");
  }
  await selected.getByRole("button", { name: "添加字段行" }).click();
  await selected.getByRole("textbox", { name: "选取与重命名字段 第2行 name" }).fill("备用流量");
  await selected.getByRole("button", { name: "选取与重命名字段 上移第2行" }).click();

  await removed.getByRole("button", { name: "添加字段行" }).click();
  await removed.getByRole("textbox", { name: "移除字段 第1行 name" }).fill("临时字段");

  await changed.getByRole("button", { name: "添加字段行" }).click();
  await changed.getByRole("textbox", { name: "转换字段元数据 第1行 name" }).fill("flow_before");
  await changed.getByRole("textbox", { name: "转换字段元数据 第1行 type" }).fill("Number");
  await changed.getByRole("spinbutton", { name: "转换字段元数据 第1行 length" }).fill("12");
  if (await changed.getByRole("spinbutton", { name: "转换字段元数据 第1行 precision" }).inputValue() !== "-1") {
    throw new Error("SelectMetadataChange native undefined precision should be -1");
  }
  await panel.getByRole("checkbox", { name: "同时保留并排序未选择的字段" }).check();
  if (writes() !== before) throw new Error("Editing native SelectValues tables performed premature Hop write");

  const saveConfig = page.waitForResponse(r => post(r, "/config/write"));
  await panel.getByRole("button", { name: "保存配置（Save）" }).click();
  const savedResponse = await saveConfig;
  if (!savedResponse.ok()) throw new Error("Real Hop rejected native SelectValues config: " + savedResponse.status());
  const written = (await savedResponse.json()).config;
  const verify = (value) =>
    value.fields?.field?.[0]?.name === "备用流量" &&
    value.fields?.field?.[1]?.name === "上游流量" &&
    value.fields?.field?.[1]?.rename === "flow_before" &&
    value.fields?.field?.[1]?.length === -2 &&
    value.fields?.remove?.[0]?.name === "临时字段" &&
    value.fields?.meta?.[0]?.name === "flow_before" &&
    value.fields?.meta?.[0]?.type === "Number" &&
    value.fields?.meta?.[0]?.length === 12 &&
    value.fields?.meta?.[0]?.precision === -1 &&
    value.fields?.select_unspecified === true &&
    !Object.hasOwn(value, "field") && !Object.hasOwn(value, "remove") && !Object.hasOwn(value, "meta");
  if (!verify(written)) throw new Error("SelectValues native 3-list write returned wrong Hop JSON shape");

  const diskSave = page.waitForResponse(r => post(r, "/save"));
  await page.getByRole("button", { name: "保存（Save）", exact: true }).click();
  if (!(await diskSave).ok()) throw new Error("SelectValues HPL disk save failed");
  const diskOpen = page.waitForResponse(r => post(r, "/api/pipelines/open"));
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  if (!(await diskOpen).ok()) throw new Error("SelectValues disk reopen failed");
  await page.locator('.react-flow__node[data-id="select-values"]').click();
  const diskRead = page.waitForResponse(r => post(r, "/config/read"));
  await page.getByRole("button", { name: "Configure" }).click();
  const persisted = (await (await diskRead).json()).config;
  if (!verify(persisted)) throw new Error("SelectValues native three-list edit not preserved in actual .hpl XML");
  await selected.getByRole("table").waitFor({ state: "visible" });
  await removed.getByRole("textbox", { name: "移除字段 第1行 name" }).fill("不要保存");
  await panel.getByRole("button", { name: "取消配置（Cancel）" }).click();
  await page.locator('.react-flow__node[data-id="select-values"]').click();
  const canceled = page.waitForResponse(r => post(r, "/config/read"));
  await page.getByRole("button", { name: "Configure" }).click();
  if (!verify((await (await canceled).json()).config) || writes() !== before + 1) {
    throw new Error("SelectValues Cancel changed server native config or performed extra write");
  }
  if (requests.some(r => r.path === "/ui" || r.path.startsWith("/ui/"))) {
    throw new Error("SelectValues native editor entered RAP /ui");
  }
  console.log("V9 native SelectValues proof passed: 3 typed LISTs, row order/defaults, unspecified flag, Hop JSON/XML reopen, and Cancel.");
} finally {
  await browser.close();
  rmSync(directory, { recursive: true, force: true });
}
