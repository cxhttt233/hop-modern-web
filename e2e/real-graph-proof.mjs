import { chromium } from "playwright";
import { fileURLToPath } from "node:url";
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
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
  const recentChoices = recent.getByRole("button", { name: /^填入最近流程：/ });
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

  // Unicode disk write proof: the same Chinese/space-containing server path
  // must survive a real semantic node move, save, and disk reopen. V3/V5
  // exercise ordinary fixture paths; this checks a distinct filesystem path
  // boundary using only the disposable .hpl copy created above.
  const unicodeSource = page.locator('.react-flow__node[data-id="source"]');
  await unicodeSource.waitFor({ state: "visible" });
  const originalSource = unicodeGraph.nodes.find((node) => node.id === "source");
  if (!originalSource) throw new Error("Unicode real .hpl is missing its source transform");
  const iconBox = await unicodeSource.locator(".transform-node-icon").boundingBox();
  if (!iconBox) throw new Error("Unicode source drag handle is not visible");
  const start = { x: iconBox.x + iconBox.width / 2, y: iconBox.y + iconBox.height / 2 };
  const movePending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new RegExp("/api/pipelines/[^/]+/move$").test(new URL(response.url()).pathname));
  await page.mouse.move(start.x, start.y);
  await page.mouse.down();
  await page.mouse.move(start.x + 48, start.y + 32, { steps: 12 });
  await page.mouse.up();
  const moveResponse = await movePending;
  const moved = await moveResponse.json();
  const movedSource = moved.nodes?.find((node) => node.id === "source");
  if (!moveResponse.ok() || !movedSource ||
      (movedSource.x === originalSource.x && movedSource.y === originalSource.y) ||
      moved.revision === unicodeGraph.revision) {
    throw new Error("Real semantic move failed for Unicode-path .hpl");
  }
  await page.locator(".dirty-state.is-dirty").waitFor({ state: "visible" });
  const savePending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new RegExp("/api/pipelines/[^/]+/save$").test(new URL(response.url()).pathname));
  await page.getByRole("button", { name: "保存（Save）", exact: true }).click();
  const saveResponse = await savePending;
  if (!saveResponse.ok()) throw new Error("Real Unicode-path .hpl disk save failed");
  await page.locator(".dirty-state.is-dirty").waitFor({ state: "hidden" });
  const unicodeReopenPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/pipelines/open");
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  if (await page.getByLabel("Server-visible pipeline path").inputValue() !== unicodePath) {
    throw new Error("Unicode path identity changed before disk reopen");
  }
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const unicodeReopenResponse = await unicodeReopenPending;
  const unicodeReopened = await unicodeReopenResponse.json();
  const diskSource = unicodeReopened.nodes?.find((node) => node.id === "source");
  if (!unicodeReopenResponse.ok() || !diskSource ||
      diskSource.x !== movedSource.x || diskSource.y !== movedSource.y ||
      unicodeReopened.edges?.length !== unicodeGraph.edges.length) {
    throw new Error("Unicode-path .hpl disk reopen lost saved node position or hops");
  }
  await unicodeSource.waitFor({ state: "visible" });
  if (requests.filter((url) => url.includes("/api/pipelines/open")).length !== 6 ||
      requests.filter((url) => new RegExp("/api/pipelines/[^/]+/save$").test(new URL(url).pathname)).length !== 1) {
    throw new Error("Unicode-path move/save/reopen issued unexpected backend requests");
  }
  console.log("Unicode disk write proof passed: real .hpl move -> save -> disk reopen with Chinese/spaced path.");

  // The session-only recent-file list has a four-file limit. Open two
  // additional distinct, valid Hop .hpl files and verify the oldest path is
  // evicted, while the other entries remain in true most-recent-first order.
  // These are real fixture copies in the disposable CI directory, not mocks.
  const extraPaths = [
    join(unicodeDirectory, "最近流程 甲.hpl"),
    join(unicodeDirectory, "最近流程 乙.hpl"),
  ];
  for (const extraPath of extraPaths) {
    await copyFile(pipelinePath, extraPath);
    await page.getByRole("button", { name: "Choose pipeline" }).click();
    await page.getByLabel("Server-visible pipeline path").fill(extraPath);
    const extraPending = page.waitForResponse((response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname === "/api/pipelines/open");
    await page.getByRole("button", { name: "Open pipeline" }).click();
    const extraResponse = await extraPending;
    const extraRequest = extraResponse.request().postDataJSON();
    const extraGraph = await extraResponse.json();
    if (!extraResponse.ok() || extraRequest?.path !== extraPath ||
        extraGraph.name !== graph.name ||
        extraGraph.nodes?.length !== graph.nodes.length ||
        extraGraph.edges?.length !== graph.edges.length) {
      throw new Error("Recent-file capacity proof failed to open a distinct real .hpl");
    }
    await page.locator('.file-identity[data-document-name="real-graph-proof"]').waitFor({ state: "visible" });
  }
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  const cappedRecent = page.getByRole("group", { name: "最近打开的流程" });
  const expectedRecent = [extraPaths[1], extraPaths[0], unicodePath, pipelinePath];
  await cappedRecent.getByRole("button", { name: `填入最近流程：${extraPaths[1]}` }).waitFor({ state: "visible" });
  const actualRecent = await cappedRecent.getByRole("button", { name: /^填入最近流程：/ }).evaluateAll((buttons) =>
    buttons.map((button) => button.getAttribute("aria-label")));
  if (JSON.stringify(actualRecent) !== JSON.stringify(expectedRecent.map((path) => `填入最近流程：${path}`)) ||
      actualRecent.some((name) => name?.includes(alternatePath))) {
    throw new Error("Four-entry recent-file capacity or most-recent-first eviction failed");
  }
  if (requests.filter((url) => url.includes("/api/pipelines/open")).length !== 8) {
    throw new Error("Recent-file capacity proof issued unexpected real open requests");
  }
  console.log("Recent-file capacity proof passed: four real .hpl paths in recency order, oldest evicted.");

  // Two different real .hpl paths can share a filename. Their recent shortcuts
  // must show unique visible path suffixes, not indistinguishable basenames.
  const sharedName = "同名流程.hpl";
  const duplicateDirs = [join(unicodeDirectory, "甲目录"), join(unicodeDirectory, "乙目录")];
  const duplicatePaths = duplicateDirs.map((dir) => join(dir, sharedName));
  for (let i = 0; i < duplicatePaths.length; i++) {
    await mkdir(duplicateDirs[i], { recursive: true });
    await copyFile(pipelinePath, duplicatePaths[i]);
    // The preceding capacity assertion left the chooser open. Subsequent
    // successful opens close it, so only toggle for later iterations.
    if (i > 0) await page.getByRole("button", { name: "Choose pipeline" }).click();
    await page.getByLabel("Server-visible pipeline path").fill(duplicatePaths[i]);
    const duplicatePending = page.waitForResponse((response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname === "/api/pipelines/open");
    await page.getByRole("button", { name: "Open pipeline" }).click();
    const duplicateResponse = await duplicatePending;
    const duplicateRequest = duplicateResponse.request().postDataJSON();
    const duplicateGraph = await duplicateResponse.json();
    if (!duplicateResponse.ok() || duplicateRequest?.path !== duplicatePaths[i] ||
        duplicateGraph.name !== graph.name || duplicateGraph.nodes?.length !== graph.nodes.length ||
        duplicateGraph.edges?.length !== graph.edges.length) {
      throw new Error("Duplicate-basename real .hpl open failed");
    }
    await page.locator('.file-identity[data-document-name="real-graph-proof"]').waitFor({ state: "visible" });
  }
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  const duplicateRecent = page.getByRole("group", { name: "最近打开的流程" });
  const duplicateLabels = await duplicateRecent.getByRole("button", { name: /^填入最近流程：/ }).evaluateAll((buttons) =>
    buttons.map((button) => ({ name: button.getAttribute("aria-label"), text: button.textContent?.trim() })));
  if (duplicateLabels[0]?.name !== `填入最近流程：${duplicatePaths[1]}` ||
      duplicateLabels[1]?.name !== `填入最近流程：${duplicatePaths[0]}` ||
      duplicateLabels[0]?.text !== "乙目录 / 同名流程.hpl" ||
      duplicateLabels[1]?.text !== "甲目录 / 同名流程.hpl") {
    throw new Error("Duplicate-basename recent files are not visibly distinguishable");
  }
  const opensBeforeSelection = requests.filter((url) => url.includes("/api/pipelines/open")).length;
  await duplicateRecent.getByRole("button", { name: `填入最近流程：${duplicatePaths[0]}` }).click();
  if (await page.getByLabel("Server-visible pipeline path").inputValue() !== duplicatePaths[0] ||
      requests.filter((url) => url.includes("/api/pipelines/open")).length !== opensBeforeSelection) {
    throw new Error("Duplicate-basename shortcut must only fill the selected exact path");
  }
  const selectedPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/pipelines/open");
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const selectedResponse = await selectedPending;
  const selectedGraph = await selectedResponse.json();
  if (!selectedResponse.ok() || selectedResponse.request().postDataJSON()?.path !== duplicatePaths[0] ||
      selectedGraph.name !== graph.name || selectedGraph.nodes?.length !== graph.nodes.length ||
      requests.filter((url) => url.includes("/api/pipelines/open")).length !== opensBeforeSelection + 1) {
    throw new Error("Duplicate-basename shortcut did not explicitly reopen the chosen real .hpl");
  }
  console.log("Duplicate-basename recent-file proof passed: distinct visible path suffixes, explicit real reopen.");

  // Clearing session-only history is an explicit UI action: it must not close,
  // switch, save or reload the current authoritative Hop graph.
  const beforeClear = requests.filter((url) => url.includes("/api/pipelines/open")).length;
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  const activePathBeforeClear = await page.getByLabel("Server-visible pipeline path").inputValue();
  await page.getByRole("button", { name: "清空最近打开记录" }).click();
  if (await page.getByRole("group", { name: "最近打开的流程" }).count() !== 0 ||
      await page.getByLabel("Server-visible pipeline path").inputValue() !== activePathBeforeClear ||
      requests.filter((url) => url.includes("/api/pipelines/open")).length !== beforeClear ||
      await page.locator(".react-flow__node").count() !== selectedGraph.nodes.length ||
      await page.locator(".react-flow__edge").count() !== selectedGraph.edges.length) {
    throw new Error("Clearing recent history must not alter the current real Hop document or issue backend requests");
  }
  await page.getByRole("button", { name: "取消", exact: true }).click();
  await page.locator('.file-identity[data-document-name="real-graph-proof"]').waitFor({ state: "visible" });
  console.log("Recent-file clear proof passed: session shortcuts removed without changing real Hop graph or network requests.");

  // Disabled Hop links are real native .hpl metadata, not React-only state.
  // The browser must distinguish them from executable links without changing
  // their identities, issuing edit commands, or entering RAP.
  const enabledXml = await readFile(pipelinePath, "utf8");
  const disabledXml = enabledXml.replace("<enabled>Y</enabled>", "<enabled>N</enabled>");
  if (disabledXml === enabledXml) throw new Error("Native enabled Hop fixture not found");
  const disabledPath = join(unicodeDirectory, "停用 Hop 连线.hpl");
  await writeFile(disabledPath, disabledXml);
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByLabel("Server-visible pipeline path").fill(disabledPath);
  const disabledPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/pipelines/open");
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const disabledResponse = await disabledPending;
  if (!disabledResponse.ok()) throw new Error("Native disabled-Hop .hpl open failed");
  const disabledGraph = await disabledResponse.json();
  const disabledEdge = disabledGraph.edges?.find((edge) => edge.source === "source" && edge.target === "sink");
  if (!disabledEdge || disabledEdge.enabled !== false ||
      disabledGraph.nodes?.length !== graph.nodes.length) {
    throw new Error("Hop server did not preserve disabled native connection semantics");
  }
  const disabledEdgeElement = page.locator('.react-flow__edge[data-id="' + disabledEdge.id + '"]');
  await disabledEdgeElement.waitFor({ state: "attached" });
  await page.waitForFunction((edgeId) => {
    const edge = [...document.querySelectorAll(".react-flow__edge")]
      .find((element) => element.getAttribute("data-id") === edgeId);
    const path = edge?.querySelector(".react-flow__edge-path");
    return Boolean(edge?.classList.contains("hop-edge-disabled") &&
      edge.textContent?.includes("已停用") &&
      path && getComputedStyle(path).strokeDasharray.includes("8"));
  }, disabledEdge.id, { timeout: 8000 });
  // Reopening the same real file must preserve the disabled wire flag and
  // visual status. No graph mutation or disk save is involved.
  const disabledReopenPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/pipelines/open");
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const disabledReopenResponse = await disabledReopenPending;
  if (!disabledReopenResponse.ok()) throw new Error("Disabled-Hop disk reopen failed");
  const disabledReopened = await disabledReopenResponse.json();
  if (!disabledReopened.edges?.some((edge) => edge.id === disabledEdge.id && edge.enabled === false) ||
      !(await disabledEdgeElement.evaluate((element) => element.classList.contains("hop-edge-disabled")))) {
    throw new Error("Native disabled-Hop semantics or visual state lost after disk reopen");
  }
  console.log("Disabled-Hop visual proof passed: real XML enabled=N -> authoritative Graph JSON -> dashed Chinese React Flow link -> disk reopen.");

  // Data-integrity regression: a disabled native Hop must remain disabled
  // after a different semantic edit is saved. A read-only reopen alone cannot
  // catch XML writers that accidentally re-enable an existing Hop.
  const disabledSource = page.locator('.react-flow__node[data-id="source"]');
  await disabledSource.waitFor({ state: "visible" });
  const beforeDisabledMove = disabledReopened.nodes.find((node) => node.id === "source");
  if (!beforeDisabledMove) throw new Error("Disabled-Hop graph is missing source transform");
  const disabledIconBox = await disabledSource.locator(".transform-node-icon").boundingBox();
  if (!disabledIconBox) throw new Error("Disabled-Hop source drag handle is not visible");
  const disabledMovePending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new RegExp("/api/pipelines/[^/]+/move$").test(new URL(response.url()).pathname));
  const disabledDragStart = {
    x: disabledIconBox.x + disabledIconBox.width / 2,
    y: disabledIconBox.y + disabledIconBox.height / 2,
  };
  await page.mouse.move(disabledDragStart.x, disabledDragStart.y);
  await page.mouse.down();
  await page.mouse.move(disabledDragStart.x + 46, disabledDragStart.y + 30, { steps: 12 });
  await page.mouse.up();
  const disabledMoveResponse = await disabledMovePending;
  const disabledMovedGraph = await disabledMoveResponse.json();
  const disabledMovedSource = disabledMovedGraph.nodes?.find((node) => node.id === "source");
  if (!disabledMoveResponse.ok() || !disabledMovedSource ||
      (disabledMovedSource.x === beforeDisabledMove.x &&
       disabledMovedSource.y === beforeDisabledMove.y) ||
      !disabledMovedGraph.edges?.some((edge) =>
        edge.id === disabledEdge.id && edge.enabled === false)) {
    throw new Error("Semantic node move altered or lost disabled Hop state");
  }
  await page.locator(".dirty-state.is-dirty").waitFor({ state: "visible" });
  const disabledSavePending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new RegExp("/api/pipelines/[^/]+/save$").test(new URL(response.url()).pathname));
  await page.getByRole("button", { name: "保存（Save）", exact: true }).click();
  const disabledSaveResponse = await disabledSavePending;
  if (!disabledSaveResponse.ok()) throw new Error("Disabled-Hop native disk save failed");
  await page.locator(".dirty-state.is-dirty").waitFor({ state: "hidden" });
  const disabledSavedXml = await readFile(disabledPath, "utf8");
  if (!disabledSavedXml.includes("<enabled>N</enabled>") ||
      disabledSavedXml === disabledXml) {
    throw new Error("Disabled-Hop disk save did not retain native disabled XML and the semantic move");
  }
  const disabledSavedReopenPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/pipelines/open");
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  if (await page.getByLabel("Server-visible pipeline path").inputValue() !== disabledPath) {
    throw new Error("Disabled-Hop file identity changed before saved disk reopen");
  }
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const disabledSavedReopenResponse = await disabledSavedReopenPending;
  const disabledSavedReopened = await disabledSavedReopenResponse.json();
  const disabledDiskSource = disabledSavedReopened.nodes?.find((node) => node.id === "source");
  if (!disabledSavedReopenResponse.ok() || !disabledDiskSource ||
      disabledDiskSource.x !== disabledMovedSource.x ||
      disabledDiskSource.y !== disabledMovedSource.y ||
      !disabledSavedReopened.edges?.some((edge) =>
        edge.id === disabledEdge.id && edge.enabled === false)) {
    throw new Error("Real Hop disk reopen lost moved coordinates or disabled connection");
  }
  await page.waitForFunction((edgeId) => {
    const edge = [...document.querySelectorAll(".react-flow__edge")]
      .find((element) => element.getAttribute("data-id") === edgeId);
    return Boolean(edge?.classList.contains("hop-edge-disabled") &&
      edge.textContent?.includes("已停用"));
  }, disabledEdge.id, { timeout: 8000 });
  console.log("Disabled-Hop edit/save proof passed: real semantic move -> native XML disk save -> reopen preserves disabled Hop and Chinese dashed link.");


  // A real pipeline can contain both executable and disabled Hop links.
  // Verify per-edge isolation: a disabled branch must not gray out an active
  // sibling, and a semantic move/save/reopen must preserve both wire flags.
  const nativeSink = enabledXml.match(/<transform><name>sink<\/name>[\s\S]*?<\/transform>/)?.[0];
  if (!nativeSink) throw new Error("Native sink transform fixture not found");
  const branchSink = nativeSink.replace("<name>sink</name>", "<name>旁路输出</name>")
    .replace("<yloc>160</yloc>", "<yloc>320</yloc>");
  if (branchSink === nativeSink || !branchSink.includes("<name>旁路输出</name>")) {
    throw new Error("Could not construct second native Hop transform");
  }
  const mixedXml = enabledXml
    .replace("</order>", "<hop><from>source</from><to>旁路输出</to><enabled>N</enabled></hop></order>")
    .replace("<transform_error_handling/>", branchSink + "<transform_error_handling/>");
  if (mixedXml === enabledXml || !mixedXml.includes(branchSink)) {
    throw new Error("Could not construct mixed enabled/disabled native Hop fixture");
  }
  const mixedPath = join(unicodeDirectory, "双分支 Hop 状态.hpl");
  await writeFile(mixedPath, mixedXml);
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByLabel("Server-visible pipeline path").fill(mixedPath);
  const mixedOpenPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/pipelines/open");
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const mixedOpenResponse = await mixedOpenPending;
  if (!mixedOpenResponse.ok()) throw new Error("Mixed native Hop pipeline open failed");
  const mixedGraph = await mixedOpenResponse.json();
  const activeLink = mixedGraph.edges?.find((edge) => edge.source === "source" && edge.target === "sink");
  const inactiveLink = mixedGraph.edges?.find((edge) => edge.source === "source" && edge.target === "旁路输出");
  if (mixedGraph.nodes?.length !== 3 || mixedGraph.edges?.length !== 2 ||
      !activeLink || activeLink.enabled === false || !inactiveLink || inactiveLink.enabled !== false) {
    throw new Error("Authoritative graph lost independent enabled/disabled Hop semantics");
  }
  await page.waitForFunction(([activeId, inactiveId]) => {
    const edges = [...document.querySelectorAll(".react-flow__edge")];
    const active = edges.find((element) => element.getAttribute("data-id") === activeId);
    const inactive = edges.find((element) => element.getAttribute("data-id") === inactiveId);
    const activePath = active?.querySelector(".react-flow__edge-path");
    const inactivePath = inactive?.querySelector(".react-flow__edge-path");
    return Boolean(active && inactive && activePath && inactivePath &&
      !active.classList.contains("hop-edge-disabled") &&
      !active.textContent?.includes("已停用") &&
      !getComputedStyle(activePath).strokeDasharray.includes("8") &&
      inactive.classList.contains("hop-edge-disabled") &&
      inactive.textContent?.includes("已停用") &&
      getComputedStyle(inactivePath).strokeDasharray.includes("8"));
  }, [activeLink.id, inactiveLink.id], { timeout: 8000 });

  // Use an independently authoritative add edit. The active and disabled
  // branches must survive an unrelated real Hop mutation before disk save.
  const mixedAddPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new RegExp("/api/pipelines/[^/]+/edit/add$").test(new URL(response.url()).pathname));
  await page.locator(".transform-group").getByRole("button", { name: "TableInput" }).click();
  const mixedAddResponse = await mixedAddPending;
  if (!mixedAddResponse.ok()) throw new Error("Mixed Hop semantic TableInput add failed");
  const editedMixed = await mixedAddResponse.json();
  const addedMixedNode = editedMixed.nodes?.find((node) =>
    node.pluginId === "TableInput" && !mixedGraph.nodes.some((previous) => previous.id === node.id));
  if (!addedMixedNode || editedMixed.nodes?.length !== 4 || editedMixed.edges?.length !== 2 ||
      !editedMixed.edges.some((edge) => edge.id === activeLink.id && edge.enabled !== false) ||
      !editedMixed.edges.some((edge) => edge.id === inactiveLink.id && edge.enabled === false)) {
    throw new Error("Mixed Hop add changed independent Y/N branch identities");
  }
  await page.locator(".dirty-state.is-dirty").waitFor({ state: "visible" });
  const mixedSavePending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new RegExp("/api/pipelines/[^/]+/save$").test(new URL(response.url()).pathname));
  await page.getByRole("button", { name: "保存（Save）", exact: true }).click();
  const mixedSaveResponse = await mixedSavePending;
  if (!mixedSaveResponse.ok()) throw new Error("Mixed Hop native disk save failed");
  await page.locator(".dirty-state.is-dirty").waitFor({ state: "hidden" });
  const savedMixedXml = await readFile(mixedPath, "utf8");
  if ((savedMixedXml.match(/<enabled>Y<\/enabled>/g) ?? []).length !== 1 ||
      (savedMixedXml.match(/<enabled>N<\/enabled>/g) ?? []).length !== 1) {
    throw new Error("Mixed native Hop XML lost independent Y/N flags on disk");
  }
  const mixedReopenPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/pipelines/open");
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const mixedReopenResponse = await mixedReopenPending;
  if (!mixedReopenResponse.ok()) throw new Error("Mixed Hop disk reopen failed");
  const mixedReopened = await mixedReopenResponse.json();
  const diskAdded = mixedReopened.nodes?.find((node) => node.id === addedMixedNode.id);
  if (!diskAdded || diskAdded.pluginId !== "TableInput" ||
      diskAdded.x !== addedMixedNode.x || diskAdded.y !== addedMixedNode.y ||
      mixedReopened.nodes?.length !== 4 || mixedReopened.edges?.length !== 2 ||
      !mixedReopened.edges.some((edge) => edge.id === activeLink.id && edge.enabled !== false) ||
      !mixedReopened.edges.some((edge) => edge.id === inactiveLink.id && edge.enabled === false)) {
    throw new Error("Mixed Hop disk reopen lost added transform or Y/N edge identity");
  }
  await page.waitForFunction(([activeId, inactiveId]) => {
    const edges = [...document.querySelectorAll(".react-flow__edge")];
    const active = edges.find((element) => element.getAttribute("data-id") === activeId);
    const inactive = edges.find((element) => element.getAttribute("data-id") === inactiveId);
    return Boolean(active && inactive && !active.classList.contains("hop-edge-disabled") &&
      !active.textContent?.includes("已停用") &&
      inactive.classList.contains("hop-edge-disabled") && inactive.textContent?.includes("已停用"));
  }, [activeLink.id, inactiveLink.id], { timeout: 8000 });
  console.log("Mixed-Hop native proof passed: active Y and disabled N branches -> React Flow isolation -> real TableInput add -> XML save -> disk reopen.");

  // Native disabled-hop history regression: deleting the disabled branch
  // must not alter an independent enabled branch; undo restores the exact
  // disabled edge, redo removes it, and a final undo must survive XML save.
  const disabledTarget = mixedReopened.nodes.find((node) => node.id === inactiveLink.target);
  if (!disabledTarget) throw new Error("Mixed-Hop disabled target is missing");
  // Reopen responses can arrive before React commits its server-mode state.
  // Wait for the authoritative editor actions before attempting a mutation.
  await page.getByRole("button", { name: "保存（Save）", exact: true }).waitFor({ state: "visible" });
  // Click the non-interactive icon to select the node. Its centered title
  // is itself a Configure button; clicking the node center opens an overlay
  // which can intercept the contextual Delete button in real Chromium.
  await page.locator('.react-flow__node[data-id="' + disabledTarget.id + '"] .transform-node-icon').click();
  const deleteAction = page.getByRole("button", { name: `删除转换：${disabledTarget.name || disabledTarget.id}（Delete）` });
  await deleteAction.waitFor({ state: "visible" });
  const requestsBeforeDelete = requests.length;
  let mixedDeleteResponse;
  try {
    [mixedDeleteResponse] = await Promise.all([
      page.waitForResponse((response) =>
        response.request().method() === "POST" &&
        new RegExp("/api/pipelines/[^/]+/edit/delete$").test(new URL(response.url()).pathname),
        { timeout: 12000 }),
      deleteAction.click({ timeout: 12000 }),
    ]);
  } catch (error) {
    const paths = requests.slice(requestsBeforeDelete).map((url) => new URL(url).pathname);
    const uiError = await page.locator(".error-note").textContent().catch(() => "");
    throw new Error(`Disabled-Hop delete action failed; request paths=${JSON.stringify(paths)}, UI=${uiError || "(none)"}; ${String(error)}`);
  }
  if (!mixedDeleteResponse.ok()) throw new Error("Disabled-branch semantic delete failed");
  const mixedDeleted = await mixedDeleteResponse.json();
  if (mixedDeleted.nodes.some((node) => node.id === disabledTarget.id) ||
      mixedDeleted.edges.some((edge) => edge.id === inactiveLink.id) ||
      !mixedDeleted.edges.some((edge) => edge.id === activeLink.id && edge.enabled !== false)) {
    throw new Error("Disabled-branch delete removed or modified the independent enabled Hop");
  }
  const mixedUndoPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new RegExp("/api/pipelines/[^/]+/edit/undo$").test(new URL(response.url()).pathname));
  await page.getByRole("button", { name: "撤销（Undo）" }).click();
  const mixedUndoResponse = await mixedUndoPending;
  const mixedRestored = await mixedUndoResponse.json();
  if (!mixedUndoResponse.ok() ||
      !mixedRestored.nodes.some((node) => node.id === disabledTarget.id) ||
      !mixedRestored.edges.some((edge) => edge.id === inactiveLink.id && edge.enabled === false) ||
      !mixedRestored.edges.some((edge) => edge.id === activeLink.id && edge.enabled !== false)) {
    throw new Error("Undo did not restore native disabled-Hop identity and state");
  }
  await page.waitForFunction((edgeId) => {
    const edge = [...document.querySelectorAll(".react-flow__edge")]
      .find((element) => element.getAttribute("data-id") === edgeId);
    return Boolean(edge?.classList.contains("hop-edge-disabled") && edge.textContent?.includes("已停用"));
  }, inactiveLink.id, { timeout: 8000 });
  const mixedRedoPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new RegExp("/api/pipelines/[^/]+/edit/redo$").test(new URL(response.url()).pathname));
  await page.getByRole("button", { name: "重做（Redo）" }).click();
  const mixedRedoResponse = await mixedRedoPending;
  const mixedRedone = await mixedRedoResponse.json();
  if (!mixedRedoResponse.ok() ||
      mixedRedone.nodes.some((node) => node.id === disabledTarget.id) ||
      mixedRedone.edges.some((edge) => edge.id === inactiveLink.id) ||
      !mixedRedone.edges.some((edge) => edge.id === activeLink.id && edge.enabled !== false)) {
    throw new Error("Redo disabled-branch deletion altered the independent enabled Hop");
  }
  const mixedFinalUndoPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new RegExp("/api/pipelines/[^/]+/edit/undo$").test(new URL(response.url()).pathname));
  await page.getByRole("button", { name: "撤销（Undo）" }).click();
  const mixedFinalUndoResponse = await mixedFinalUndoPending;
  const mixedFinalRestored = await mixedFinalUndoResponse.json();
  if (!mixedFinalUndoResponse.ok() ||
      !mixedFinalRestored.nodes.some((node) => node.id === disabledTarget.id) ||
      !mixedFinalRestored.edges.some((edge) => edge.id === inactiveLink.id && edge.enabled === false) ||
      !mixedFinalRestored.edges.some((edge) => edge.id === activeLink.id && edge.enabled !== false)) {
    throw new Error("Final undo failed to restore disabled Hop after redo");
  }
  await page.waitForFunction((edgeId) => {
    const edge = [...document.querySelectorAll(".react-flow__edge")]
      .find((element) => element.getAttribute("data-id") === edgeId);
    return Boolean(edge?.classList.contains("hop-edge-disabled") && edge.textContent?.includes("已停用"));
  }, inactiveLink.id, { timeout: 8000 });
  const mixedHistorySavePending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new RegExp("/api/pipelines/[^/]+/save$").test(new URL(response.url()).pathname));
  await page.getByRole("button", { name: "保存（Save）", exact: true }).click();
  const mixedHistorySaved = await mixedHistorySavePending;
  if (!mixedHistorySaved.ok()) throw new Error("Disabled-Hop undo history save failed");
  const historyXml = await readFile(mixedPath, "utf8");
  if ((historyXml.match(/<enabled>Y<\/enabled>/g) ?? []).length !== 1 ||
      (historyXml.match(/<enabled>N<\/enabled>/g) ?? []).length !== 1) {
    throw new Error("Undo-restored disabled Hop lost Y/N flags in native disk XML");
  }
  const mixedHistoryReopenPending = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/pipelines/open");
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const mixedHistoryReopenResponse = await mixedHistoryReopenPending;
  const mixedHistoryReopened = await mixedHistoryReopenResponse.json();
  if (!mixedHistoryReopenResponse.ok() ||
      !mixedHistoryReopened.edges.some((edge) => edge.id === activeLink.id && edge.enabled !== false) ||
      !mixedHistoryReopened.edges.some((edge) => edge.id === inactiveLink.id && edge.enabled === false)) {
    throw new Error("Disk reopen lost disabled Hop restored by semantic undo");
  }
  console.log("Mixed-Hop undo/delete proof passed: disabled branch deletion and undo preserve active Hop, native Y/N XML and disk reopen.");


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
