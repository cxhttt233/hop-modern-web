import { chromium } from "playwright";

const pipelinePath = process.env.REAL_HPL_PATH;
if (!pipelinePath) throw new Error("REAL_HPL_PATH is required");

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const requests = [];
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

  const rapRequests = requests.filter((url) => {
    const path = new URL(url).pathname;
    return path === "/ui" || path.startsWith("/ui/");
  });
  if (rapRequests.length) throw new Error("RAP /ui must not be entered");
  console.log("V2 proof passed: real .hpl -> /api/pipelines/open -> 2+ nodes / 1+ edge in React Flow; no RAP /ui requests.");
} finally {
  await browser.close();
}
