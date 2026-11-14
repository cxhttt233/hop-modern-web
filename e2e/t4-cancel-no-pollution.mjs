import { chromium } from "playwright";

const baseURL = process.env.T4_PREVIEW_URL ?? "http://127.0.0.1:4196/t4/";
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const writes = [];
const errors = [];
page.on("pageerror", error => errors.push(error.message));
page.on("request", request => {
  if (request.method() !== "GET" && /\/(config\/write|api\/pipelines\/save|save)(\?|$)/.test(new URL(request.url()).pathname))
    writes.push(request.url());
});
try {
  await page.goto(baseURL, { waitUntil: "networkidle" });
  const nodes = page.locator(".react-flow__node");
  if (await nodes.count() === 0) throw new Error("No pipeline nodes in T4 preview");
  const candidate = nodes.filter({ hasText: "选择字段" }).first();
  if (await candidate.count() === 0) throw new Error("T4 fixture needs an existing SelectValues node");
  await candidate.dblclick();
  const panel = page.locator("form.generic-config");
  await panel.waitFor({ state: "visible" });
  const selected = panel.getByRole("region", { name: "选取与重命名字段" });
  await selected.getByRole("table").waitFor({ state: "visible" });
  const before = await selected.getByRole("row").count();
  await selected.getByRole("button", { name: "添加字段行" }).click();
  const after = await selected.getByRole("row").count();
  if (after !== before + 1) throw new Error(`Add row failed: ${before} -> ${after}`);
  await panel.getByRole("button", { name: /取消/ }).click();
  await candidate.dblclick();
  await selected.getByRole("table").waitFor({ state: "visible" });
  const restored = await selected.getByRole("row").count();
  if (restored !== before) throw new Error(`Cancel leaked unsaved row: ${before} -> ${restored}`);
  if (writes.length) throw new Error("Cancel triggered persistence requests: " + writes.join(", "));
  if (errors.length) throw new Error("Browser errors: " + errors.join("; "));
  console.log(JSON.stringify({ pass: true, action: "add row then cancel and reopen", before, after, restored, writes: writes.length, errors }));
} finally {
  await browser.close();
}
