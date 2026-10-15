import { chromium } from "playwright";
const pipelinePath = process.env.REAL_HPL_PATH;
if (!pipelinePath) throw new Error("REAL_HPL_PATH is required");
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const requests = [];
page.on("request", request => requests.push({ url: request.url(), method: request.method() }));
const match = (response, suffix) => response.request().method() === "POST" && new URL(response.url()).pathname.endsWith(suffix);
try {
  await page.goto("http://127.0.0.1:5173", { waitUntil: "networkidle" });
  const openPromise = page.waitForResponse(r => match(r, "/api/pipelines/open"));
  await page.getByLabel("Server-visible pipeline path").fill(pipelinePath);
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const openResponse = await openPromise;
  const opened = await openResponse.json();
  if (!openResponse.ok() || !opened.nodes?.some(node => node.id === "table-input")) {
    throw new Error(`Authoritative Table Input graph missing: ${JSON.stringify(opened)}`);
  }
  await page.locator(`.file-identity[data-document-name="${opened.name}"]`).waitFor({ state: "visible" });
  await page.locator(".react-flow").waitFor({ state: "visible" });
  const node = page.locator('.react-flow__node[data-id="table-input"]');
  await node.waitFor({ state: "visible" });
  await node.click();
  const initialReadPromise = page.waitForResponse(r => match(r, "/config/read"));
  await page.getByRole("button", { name: "Configure" }).click();
  const initialRead = await initialReadPromise;
  const initial = await initialRead.json();
  if (!initialRead.ok() || initial.config?.connection !== "Warehouse" || initial.config?.sql !== "SELECT id, name FROM customers") throw new Error("Initial authoritative config mismatch");
  const connectionDescriptor = initial.descriptor?.properties?.find(p => p.key === "connection");
  const sqlDescriptor = initial.descriptor?.properties?.find(p => p.key === "sql");
  if (!connectionDescriptor || !sqlDescriptor) throw new Error("Runtime descriptor fields missing");
  const panel = page.locator("form.generic-config");
  await panel.waitFor({ state: "visible" });
  const connection = panel.locator(`#config-${connectionDescriptor.javaField}`);
  const sqlField = panel.locator(`#config-${sqlDescriptor.javaField}`);
  if (await connection.inputValue() !== "Warehouse") throw new Error("Authoritative connection not visible");
  if (await sqlField.inputValue() !== "SELECT id, name FROM customers") throw new Error("Authoritative SQL not visible");
  const sql = "SELECT id, name FROM customers WHERE id > 10";
  await sqlField.fill(sql);
  const writePromise = page.waitForResponse(r => match(r, "/config/write"));
  const rereadPromise = page.waitForResponse(r => match(r, "/config/read"));
  await panel.getByRole("button", { name: "Save" }).click();
  const write = await writePromise;
  const writeBody = write.request().postDataJSON();
  const written = await write.json();
  if (!write.ok() || writeBody.nodeId !== "table-input" || writeBody.config?.sql !== sql || written.config?.sql !== sql) throw new Error("Authoritative config/write mismatch");
  const rereadResponse = await rereadPromise;
  const reread = await rereadResponse.json();
  if (!rereadResponse.ok() || reread.config?.sql !== sql) throw new Error("Authoritative re-read mismatch");
  if (await sqlField.inputValue() !== sql) throw new Error("Authoritative re-read not visible");
  if (await connection.inputValue() !== "Warehouse") throw new Error("Connection not preserved");
  const writes = requests.filter(({url,method}) => method === "POST" && new URL(url).pathname.endsWith("/config/write"));
  if (writes.length !== 1) throw new Error("Expected exactly one config/write");
  const rap = requests.filter(({url}) => { const p = new URL(url).pathname; return p === "/ui" || p.startsWith("/ui/"); });
  if (rap.length) throw new Error("RAP /ui must not be entered");
  console.log("V4 generic config proof passed");
} finally {
  await browser.close();
}
