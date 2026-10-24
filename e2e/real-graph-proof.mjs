import { chromium } from "playwright";
import { fileURLToPath } from "node:url";
import { copyFile, mkdtemp, rm } from "node:fs/promises";
import { dirname, join } from "node:path";

const pipelinePath = process.env.REAL_HPL_PATH;
if (!pipelinePath) throw new Error("REAL_HPL_PATH is required");

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const requests = [];
let unicodeDirectory;
page.on("request", (request) => requests.push(request.url()));

try {
  await page.goto("http://127.0.0.1:5173", { waitUntil: "networkidle" });
  await page.getByLabel("Server-visible pipeline path").fill(pipelinePath);
  const openPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/pipelines/open");
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const openResponse = await openPending;
  if (!openResponse.ok()) throw new Error(`Real pipeline open failed: HTTP ${openResponse.status()}`);
  const graph = await openResponse.json();
  if (graph.name !== "real-graph-proof" || graph.nodes?.length < 2 || graph.edges?.length < 1) {
    throw new Error(`Unexpected authoritative graph payload: ${JSON.stringify(graph)}`);
  }
  await page.locator('.file-identity[data-document-name="real-graph-proof"]').waitFor({ state: "visible" });
  await page.locator(".react-flow").waitFor({ state: "visible" });
  const nodes = page.locator(".react-flow__node");
  const edges = page.locator(".react-flow__edge");
  await nodes.nth(1).waitFor({ state: "visible" });
  await edges.first().waitFor({ state: "attached" });
  if ((await nodes.count()) < 2) throw new Error("Expected at least two real graph nodes");
  if ((await edges.count()) < 1) throw new Error("Expected at least one real graph edge");
  // The Chinese product labels must come from the real Hop graph without
  // changing persisted node IDs or plugin IDs.
  for (const [id, label] of [["source", "输入"], ["sink", "输出"]]) {
    const node = page.locator('.react-flow__node[data-id="' + id + '"]');
    const displayed = await node.locator(".transform-node-copy strong").innerText();
    const pluginId = await node.locator(".transform-node-content").getAttribute("data-plugin-id");
    if (displayed !== label || pluginId !== "Dummy") {
      throw new Error("Real Hop node localization changed identity: " + id);
    }
  }
  const palette = page.locator('.transform-palette[aria-label="Transform palette"]');
  const categories = await palette.locator(".transform-group-title > span").allTextContents();
  const expectedCategories = ["输入与接入", "输出与写入", "字段处理", "流程与关联"];
  if (JSON.stringify(categories) !== JSON.stringify(expectedCategories)) {
    throw new Error("Chinese Palette categories do not match the real editor");
  }
  const tableInputPalette = await palette.getByRole("button", { name: "TableInput" }).innerText();
  if (!tableInputPalette.includes("表输入") || !tableInputPalette.includes("TableInput")) {
    throw new Error("Chinese Palette must retain the immutable Hop plugin ID");
  }
  const openRequests = requests.filter((url) => url.includes("/api/pipelines/open"));
  if (openRequests.length !== 1) throw new Error("Expected exactly one real pipeline open request");
  // Opening the same real .hpl again must frame its nodes, even after the
  // user has zoomed in. This exercises automatic open/reopen navigation,
  // not the separate manual Fit canvas action.
  const viewportScale = () => page.locator(".react-flow__viewport").evaluate((element) =>
    new DOMMatrixReadOnly(getComputedStyle(element).transform).a);
  const zoomIn = page.locator(".react-flow__controls-zoomin");
  for (let attempt = 0; attempt < 8 && (await viewportScale()) <= 1.2; attempt++) {
    await zoomIn.click();
    await page.waitForTimeout(240);
  }
  if ((await viewportScale()) <= 1.2) throw new Error("Could not zoom real graph before reopen");
  const reopenPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/pipelines/open");
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const reopenedResponse = await reopenPending;
  if (!reopenedResponse.ok()) throw new Error("Real pipeline reopen failed");
  const reopened = await reopenedResponse.json();
  if (reopened.name !== graph.name || reopened.nodes.length !== graph.nodes.length) {
    throw new Error("Reopen changed the authoritative real graph");
  }
  await page.waitForFunction(() => {
    const viewport = document.querySelector(".react-flow__viewport");
    const file = document.querySelector('.file-identity[data-document-name="real-graph-proof"]');
    return Boolean(viewport && file &&
      new DOMMatrixReadOnly(getComputedStyle(viewport).transform).a <= 1.01);
  }, null, { timeout: 8000 });
  const paneBox = await page.locator(".react-flow__pane").boundingBox();
  if (!paneBox) throw new Error("No visible pane after real reopen");
  for (const node of reopened.nodes) {
    const box = await page.locator('.react-flow__node[data-id="' + node.id + '"]').boundingBox();
    if (!box || box.x < paneBox.x - 2 || box.y < paneBox.y - 2 ||
        box.x + box.width > paneBox.x + paneBox.width + 2 ||
        box.y + box.height > paneBox.y + paneBox.height + 2) {
      throw new Error("Reopened node outside visible pane: " + node.id);
    }
  }
  if (requests.filter((url) => url.includes("/api/pipelines/open")).length !== 2) {
    throw new Error("Expected exactly one additional real open on reopen");
  }

  // Switching between two different real .hpl files must replace the graph
  // and document identity, not leak nodes or hops from the previous file.
  const alternatePath = fileURLToPath(new URL("./fixtures/v4-table-input-proof.hpl", import.meta.url));
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByLabel("Server-visible pipeline path").fill(alternatePath);
  const alternatePending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/pipelines/open");
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const alternateResponse = await alternatePending;
  if (!alternateResponse.ok()) throw new Error("Alternate real pipeline open failed");
  const alternate = await alternateResponse.json();
  if (alternate.name !== "v4-table-input-proof" || alternate.nodes?.length !== 1 ||
      alternate.edges?.length !== 0) {
    throw new Error("Unexpected authoritative alternate pipeline graph");
  }
  await page.locator('.file-identity[data-document-name="v4-table-input-proof"]').waitFor({ state: "visible" });
  await page.locator('.react-flow__node[data-id="' + alternate.nodes[0].id + '"]').waitFor({ state: "visible" });
  const alternateNode = page.locator('.react-flow__node[data-id="' + alternate.nodes[0].id + '"]');
  if (await alternateNode.locator(".transform-node-copy strong").innerText() !== "表输入" ||
      await alternateNode.locator(".transform-node-content").getAttribute("data-plugin-id") !== "TableInput") {
    throw new Error("Real TableInput node lost its Chinese display name or Hop plugin identity");
  }
  if (await page.locator(".react-flow__node").count() !== 1 ||
      await page.locator(".react-flow__edge").count() !== 0) {
    throw new Error("Switching files retained stale nodes or hops");
  }
  for (const previous of graph.nodes) {
    if (await page.locator('.react-flow__node[data-id="' + previous.id + '"]').count()) {
      throw new Error("Previous pipeline node leaked after file switch");
    }
  }

  // The Chinese recent-file chooser remembers only successful real .hpl
  // opens. Choosing a shortcut fills the path; the user still explicitly opens.
  // Switching back must recover the original disk graph, not in-memory state.
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  const recent = page.getByRole("group", { name: "最近打开的流程" });
  const recentChoices = recent.getByRole("button");
  if (await recentChoices.count() !== 2) {
    throw new Error("Recent real pipelines must contain two distinct successful opens");
  }
  await recent.getByRole("button", { name: `填入最近流程：${pipelinePath}` }).click();
  if (await page.getByLabel("Server-visible pipeline path").inputValue() !== pipelinePath) {
    throw new Error("Recent pipeline selection did not fill the original server path");
  }
  const returnPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/pipelines/open");
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const returnResponse = await returnPending;
  if (!returnResponse.ok()) throw new Error("Original real pipeline return failed");
  const returned = await returnResponse.json();
  if (returned.name !== graph.name || returned.nodes?.length !== graph.nodes.length ||
      returned.edges?.length !== graph.edges.length) {
    throw new Error("Original disk graph changed after switching files");
  }
  await page.locator('.file-identity[data-document-name="real-graph-proof"]').waitFor({ state: "visible" });
  for (const original of graph.nodes) {
    await page.locator('.react-flow__node[data-id="' + original.id + '"]').waitFor({ state: "visible" });
  }
  if (await page.locator(".react-flow__node").count() !== graph.nodes.length ||
      await page.locator(".react-flow__edge").count() !== graph.edges.length ||
      requests.filter((url) => url.includes("/api/pipelines/open")).length !== 4) {
    throw new Error("Real file-switch round trip failed to restore the authoritative graph");
  }

  // A Chinese filename containing spaces must survive the browser JSON request,
  // the server-side real .hpl open, and React Flow rendering without path mangling.
  // Use a disposable copy of the real fixture, not a mock graph or Hop Core edit.
  unicodeDirectory = await mkdtemp(join(dirname(pipelinePath), "黄河 路径-"));
  const unicodePath = join(unicodeDirectory, "中文 流程.hpl");
  await copyFile(pipelinePath, unicodePath);
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByLabel("Server-visible pipeline path").fill(unicodePath);
  const unicodePending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/pipelines/open");
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const unicodeResponse = await unicodePending;
  const unicodeRequest = unicodeResponse.request().postDataJSON();
  const unicodeGraph = await unicodeResponse.json();
  if (!unicodeResponse.ok() || unicodeRequest?.path !== unicodePath ||
      unicodeGraph.name !== graph.name ||
      unicodeGraph.nodes?.length !== graph.nodes.length ||
      unicodeGraph.edges?.length !== graph.edges.length ||
      !unicodeGraph.nodes.every((node) => graph.nodes.some(
        (original) => original.id === node.id && original.pluginId === node.pluginId))) {
    throw new Error("Chinese and space-containing .hpl path did not round-trip through real Hop");
  }
  if (requests.filter((url) => url.includes("/api/pipelines/open")).length !== 5) {
    throw new Error("Unicode real .hpl path must issue exactly one additional open request");
  }

  const rapRequests = requests.filter((url) => {
    const path = new URL(url).pathname;
    return path === "/ui" || path.startsWith("/ui/");
  });
  if (rapRequests.length) throw new Error("RAP /ui must not be entered");
  console.log("V2 proof passed: real .hpl -> /api/pipelines/open -> 2+ nodes / 1+ edge in React Flow; no RAP /ui requests.");
} finally {
  await browser.close();
  if (unicodeDirectory) await rm(unicodeDirectory, { recursive: true, force: true });
}
