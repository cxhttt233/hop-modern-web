import { chromium } from "playwright";

const pipelinePath = process.env.REAL_HPL_PATH;
if (!pipelinePath) throw new Error("REAL_HPL_PATH is required");

const initialSql = "SELECT id, name FROM customers";
const committedSql = "SELECT id, name, status FROM customers";
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const requests = [];
page.on("request", (request) => requests.push({ method: request.method(), url: request.url() }));

const configPath = (kind) => new RegExp(`/api/pipelines/[^/]+/config/${kind}$`);

async function readEditorValue() {
  return page.locator(".monaco-editor textarea").inputValue();
}

try {
  await page.goto("http://127.0.0.1:5173", { waitUntil: "networkidle" });
  await page.getByLabel("Server-visible pipeline path").fill(pipelinePath);

  const openResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname === "/api/pipelines/open"
  );
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const openResponse = await openResponsePromise;
  if (!openResponse.ok()) throw new Error(`Pipeline open failed with ${openResponse.status()}`);

  await page.getByText("Server graph").waitFor({ state: "visible" });
  const node = page.locator('.react-flow__node[data-id="table-input"]');
  await node.waitFor({ state: "visible" });
  await node.click();

  const firstReadPromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      configPath("read").test(new URL(response.url()).pathname)
  );
  await page.getByRole("button", { name: "Configure" }).click();
  const firstRead = await firstReadPromise;
  if (!firstRead.ok()) throw new Error(`Initial config read failed with ${firstRead.status()}`);
  const initial = await firstRead.json();
  if (initial.config.connection !== "Warehouse") {
    throw new Error(`Expected authoritative connection Warehouse, got ${initial.config.connection}`);
  }
  if (initial.config.sql !== initialSql) {
    throw new Error(`Expected authoritative SQL "${initialSql}", got "${initial.config.sql}"`);
  }

  await page.getByLabel("table-input configuration").waitFor({ state: "visible" });
  await page.getByText("Warehouse", { exact: true }).waitFor({ state: "visible" });
  const editor = page.locator(".monaco-editor textarea");
  await editor.waitFor({ state: "visible" });
  if ((await readEditorValue()) !== initialSql) {
    throw new Error("Browser did not hydrate the authoritative initial SQL");
  }

  await editor.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type(committedSql);

  const writePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      configPath("write").test(new URL(response.url()).pathname)
  );
  await page.getByRole("button", { name: "Apply" }).click();
  const writeResponse = await writePromise;
  if (!writeResponse.ok()) throw new Error(`Config write failed with ${writeResponse.status()}`);
  const written = await writeResponse.json();
  if (written.config.sql !== committedSql) {
    throw new Error(`Write response was not authoritative: ${written.config.sql}`);
  }

  await page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      configPath("read").test(new URL(response.url()).pathname) &&
      response.ok()
  );
  await page.waitForFunction(
    (sql) => {
      const textarea = document.querySelector(".monaco-editor textarea");
      return textarea && textarea.value === sql;
    },
    committedSql
  );

  const writes = requests.filter(
    (request) => request.method === "POST" && configPath("write").test(new URL(request.url).pathname)
  );
  if (writes.length !== 1) throw new Error(`Expected exactly one config/write, saw ${writes.length}`);

  await page.getByRole("button", { name: "Close configuration" }).click();

  const reopenReadPromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      configPath("read").test(new URL(response.url()).pathname)
  );
  await page.getByRole("button", { name: "Configure" }).click();
  const reopenRead = await reopenReadPromise;
  if (!reopenRead.ok()) throw new Error(`Reopen config read failed with ${reopenRead.status()}`);
  const reopened = await reopenRead.json();
  if (reopened.config.sql !== committedSql) {
    throw new Error(`Reopen did not return committed authoritative SQL: ${reopened.config.sql}`);
  }
  await page.waitForFunction(
    (sql) => {
      const textarea = document.querySelector(".monaco-editor textarea");
      return textarea && textarea.value === sql;
    },
    committedSql
  );

  const rapRequests = requests.filter((request) => {
    const path = new URL(request.url).pathname;
    return path === "/ui" || path.startsWith("/ui/");
  });
  if (rapRequests.length) throw new Error("RAP /ui must not be entered");

  console.log(
    "V4 proof passed: real Table Input hydrated authoritative connection/sql; one config/write committed SQL; authoritative re-read/reopen and browser showed committed SQL; no RAP /ui."
  );
} finally {
  await browser.close();
}
