import { chromium } from "playwright";

const pipelinePath = process.env.REAL_HPL_PATH;
if (!pipelinePath) throw new Error("REAL_HPL_PATH is required");

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const requests = [];
page.on("request", request => requests.push({ url: request.url(), method: request.method() }));

const post = suffix =>
  page.waitForResponse(response =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname.endsWith(suffix));
const rf = id => page.locator('.react-flow__node[data-id="' + id + '"]');

try {
  await page.goto("http://127.0.0.1:5173", { waitUntil: "networkidle" });
  let pending = post("/api/pipelines/open");
  await page.getByLabel("Server-visible pipeline path").fill(pipelinePath);
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const openResponse = await pending;
  let graph = await openResponse.json();
  if (!openResponse.ok() || !graph.name || graph.nodes?.length < 2 || graph.edges?.length < 1) {
    throw new Error(`Unexpected authoritative Palette graph: ${JSON.stringify(graph)}`);
  }
  await page.locator(`.file-identity[data-document-name="${graph.name}"]`).waitFor({ state: "visible" });
  await page.locator(".react-flow").waitFor({ state: "visible" });

  // The complete P0 Palette must be usable in Chinese on a real opened Hop
  // graph. Assert every plugin identity, visible Chinese name, and category;
  // do not infer availability from a mocked catalog or offline helper.
  const expectedPalette = {
    "输入与接入": [["DataGrid", "数据网格"], ["Http", "HTTP 客户端"], ["JsonInput", "JSON 输入"], ["Rest", "REST 客户端"], ["TableInput", "表输入"]],
    "输出与写入": [["ExecSql", "执行 SQL"], ["InsertUpdate", "插入 / 更新"], ["SetVariable", "设置变量"], ["TableOutput", "表输出"]],
    "字段处理": [["CheckSum", "校验和"], ["ConcatFields", "字段拼接"], ["ReplaceString", "字符串替换"], ["ScriptValueMod", "脚本计算"], ["SelectValues", "选择字段"], ["StringCut", "字符串截取"]],
    "流程与关联": [["FilterRows", "过滤记录"], ["GroupBy", "分组汇总"], ["MergeJoin", "合并连接"], ["StreamLookup", "流查询"], ["UniqueRowsByHashSet", "记录去重"]],
  };
  const palette = page.locator("aside.transform-palette");
  const recent = palette.locator(".recent-transform-section");
  if (await recent.count() !== 0) throw new Error("Recent transforms must start empty before any successful Hop add");
  const groups = palette.locator(".transform-group");
  if (await groups.count() !== Object.keys(expectedPalette).length) {
    throw new Error("Real Hop Palette lacks the four Chinese P0 groups");
  }
  for (const [groupName, entries] of Object.entries(expectedPalette)) {
    const group = groups.filter({ has: page.getByText(groupName, { exact: true }) });
    if (await group.count() !== 1 || await group.locator(".transform-items button").count() !== entries.length) {
      throw new Error(`Palette group ${groupName} does not contain its expected P0 transforms`);
    }
    for (const [pluginId, chineseName] of entries) {
      const button = group.getByRole("button", { name: `${chineseName}（${pluginId}）`, exact: true });
      if (await button.count() !== 1 ||
          await button.locator(".palette-item-copy strong").innerText() !== chineseName ||
          await button.locator(".palette-item-copy small").innerText() !== pluginId ||
          await button.getAttribute("draggable") !== "true") {
        throw new Error(`P0 Palette identity/localization/drag mismatch: ${groupName}/${pluginId}`);
      }
    }
  }
  console.log("V6 full P0 Chinese Palette proof passed: 20 distinct plugins in four categories on real Hop graph.");


  const canvas = page.locator(".react-flow__pane");
  const added = [];
  const plugins = ["TableInput", "SelectValues", "FilterRows"];
  const chineseNames = { TableInput: "表输入", SelectValues: "选择字段", FilterRows: "过滤记录" };
  const accessibleName = pluginId => `${chineseNames[pluginId]}（${pluginId}）`;
  const assertRecentOrder = async ids => {
    const actual = await recent.locator(".transform-items button").evaluateAll(buttons =>
      buttons.map(button => button.getAttribute("aria-label")));
    const expected = ids.map(id => `最近使用：${chineseNames[id]}（${id}）`);
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
      throw new Error(`Recent Hop Palette order mismatch: ${JSON.stringify({ actual, expected })}`);
    }
  };
  const positions = [{ x: 560, y: 260 }, { x: 760, y: 360 }, { x: 960, y: 260 }];

  for (let i = 0; i < plugins.length; i++) {
    const before = new Set(graph.nodes.map(node => node.id));
    pending = post("/edit/add");
    await page.getByRole("button", { name: accessibleName(plugins[i]), exact: true }).dragTo(canvas, { targetPosition: positions[i] });
    const response = await pending;
    if (!response.ok()) throw new Error(`add ${plugins[i]} HTTP ${response.status()}: ${await response.text()}`);
    graph = await response.json();
    const node = graph.nodes.find(candidate => !before.has(candidate.id));
    if (!node || node.pluginId !== plugins[i]) {
      throw new Error(`authoritative add mismatch for ${plugins[i]}: ${JSON.stringify(node)}`);
    }
    if (!node.id || before.has(node.id)) throw new Error(`node id not unique for ${plugins[i]}`);
    added.push(node);
    await rf(node.id).waitFor();

    // The authoritative node must be visually centered on the actual drop
    // point, not positioned with its top-left corner under the cursor.
    // Measure the real rendered React Flow node after the server has replied.
    const paneBox = await canvas.boundingBox();
    const nodeBox = await rf(node.id).boundingBox();
    if (!paneBox || !nodeBox) throw new Error("Palette drop lacks visible canvas/node geometry");
    const dropX = paneBox.x + positions[i].x;
    const dropY = paneBox.y + positions[i].y;
    const nodeCenterX = nodeBox.x + nodeBox.width / 2;
    const nodeCenterY = nodeBox.y + nodeBox.height / 2;
    // The transform footprint is 176px wide and about 50px high; the
    // placement uses a 176x64 anchor, leaving a small vertical difference.
    if (Math.abs(nodeCenterX - dropX) > 18 || Math.abs(nodeCenterY - dropY) > 22) {
      throw new Error(
        `Palette drop center mismatch for ${plugins[i]}: drop=(${dropX},${dropY}), nodeCenter=(${nodeCenterX},${nodeCenterY})`
      );
    }
  }

  // Successful real drag-to-add operations populate a deduplicated, bounded
  // session-only recent Palette; the authoritative 20-plugin catalog stays intact.
  await recent.waitFor({ state: "visible" });
  await assertRecentOrder(["FilterRows", "SelectValues", "TableInput"]);
  if (await groups.count() !== Object.keys(expectedPalette).length) {
    throw new Error("Recent Palette section must not replace the four canonical groups");
  }

  async function connect(from, to) {
    pending = post("/edit/connect");
    const sourceHandle = rf(from).locator(".react-flow__handle.source");
    const targetHandle = rf(to).locator(".react-flow__handle.target");
    await sourceHandle.waitFor({ state: "visible" });
    await targetHandle.waitFor({ state: "visible" });
    await sourceHandle.hover({ force: true });
    await page.mouse.down();
    await page.waitForTimeout(80);
    await targetHandle.hover({ force: true });
    await page.waitForTimeout(120);
    await page.mouse.up();
    const response = await pending;
    const body = await response.text();
    if (!response.ok()) throw new Error(`connect HTTP ${response.status()}: ${body}`);
    graph = JSON.parse(body);
    if (!graph.edges.some(edge => edge.source === from && edge.target === to)) {
      throw new Error(`authoritative hop missing for ${from}->${to}`);
    }
  }

  await connect(added[0].id, added[1].id);
  await connect(added[1].id, added[2].id);

  // A newly drawn Hop must be a semantic edit, not merely a rendered SVG.
  // Undo only the second connection, preserve the first, then redo and
  // confirm the restored hop survives the existing disk save/reopen below.
  const firstHop = { from: added[0].id, to: added[1].id };
  const lastHop = { from: added[1].id, to: added[2].id };
  const hasHop = (document, hop) => document.edges.some(edge =>
    edge.source === hop.from && edge.target === hop.to);
  const lastEdge = graph.edges.find(edge =>
    edge.source === lastHop.from && edge.target === lastHop.to);
  if (!lastEdge || !hasHop(graph, firstHop)) throw new Error("Both authoritative Palette hops must exist before undo");
  await page.locator(`.react-flow__edge[data-id="${lastEdge.id}"]`).waitFor({ state: "attached" });

  const undoPending = post("/edit/undo");
  await page.getByRole("button", { name: "撤销（Undo）", exact: true }).click();
  const undoResponse = await undoPending;
  if (!undoResponse.ok()) throw new Error(`Palette hop undo HTTP ${undoResponse.status()}`);
  const undone = await undoResponse.json();
  if (!hasHop(undone, firstHop) || hasHop(undone, lastHop) ||
      undone.edges.length !== graph.edges.length - 1) {
    throw new Error("Undo did not remove exactly the last authoritative Hop connection");
  }
  await page.locator(`.react-flow__edge[data-id="${lastEdge.id}"]`).waitFor({ state: "detached" });

  const redoPending = post("/edit/redo");
  await page.getByRole("button", { name: "重做（Redo）", exact: true }).click();
  const redoResponse = await redoPending;
  if (!redoResponse.ok()) throw new Error(`Palette hop redo HTTP ${redoResponse.status()}`);
  const redone = await redoResponse.json();
  if (!hasHop(redone, firstHop) || !hasHop(redone, lastHop) ||
      redone.edges.length !== graph.edges.length) {
    throw new Error("Redo did not restore both authoritative Hop connections");
  }
  const restoredEdge = redone.edges.find(edge =>
    edge.source === lastHop.from && edge.target === lastHop.to);
  await page.locator(`.react-flow__edge[data-id="${restoredEdge.id}"]`).waitFor({ state: "attached" });
  graph = redone;

  pending = post("/config/read");
  await rf(added[0].id).dblclick();
  const initialRead = await pending;
  const initial = await initialRead.json();
  if (!initialRead.ok() || initial.pluginId !== "TableInput") {
    throw new Error("new TableInput did not open authoritative GenericConfigPanel");
  }
  const sqlDescriptor = initial.descriptor?.properties?.find(property => property.key === "sql");
  if (!sqlDescriptor) throw new Error("TableInput sql descriptor missing");

  const panel = page.locator("form.generic-config");
  await panel.waitFor({ state: "visible" });
  const sqlField = panel.locator(`#config-${sqlDescriptor.javaField}`);
  const sql = "SELECT 42 AS answer";
  await sqlField.fill(sql);

  const writePending = post("/config/write");
  const rereadPending = post("/config/read");
  await panel.getByRole("button", { name: "Save" }).click();
  const writeResponse = await writePending;
  const written = await writeResponse.json();
  if (!writeResponse.ok() || written.config?.sql !== sql) throw new Error("config/write did not persist sql");
  const rereadResponse = await rereadPending;
  const reread = await rereadResponse.json();
  if (!rereadResponse.ok() || reread.config?.sql !== sql) throw new Error("config re-read did not return sql");
  await panel.getByRole("button", { name: "Cancel" }).click();

  pending = post("/save");
  await page.getByRole("button", { name: "Save" }).click();
  const saved = await (await pending).json();
  for (const node of added) {
    if (!saved.nodes.some(candidate => candidate.id === node.id && candidate.pluginId === node.pluginId)) {
      throw new Error(`save lost ${node.pluginId}`);
    }
  }
  if (!saved.edges.some(edge => edge.source === added[0].id && edge.target === added[1].id) ||
      !saved.edges.some(edge => edge.source === added[1].id && edge.target === added[2].id)) {
    throw new Error("save lost Palette hops");
  }

  pending = post("/api/pipelines/open");
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const reopened = await (await pending).json();
  for (const node of added) {
    if (!reopened.nodes.some(candidate => candidate.id === node.id && candidate.pluginId === node.pluginId)) {
      throw new Error(`disk reopen lost ${node.pluginId}`);
    }
  }
  if (!reopened.edges.some(edge => edge.source === added[0].id && edge.target === added[1].id) ||
      !reopened.edges.some(edge => edge.source === added[1].id && edge.target === added[2].id)) {
    throw new Error("disk reopen lost Palette hops");
  }

  pending = post("/config/read");
  await rf(added[0].id).dblclick();
  const reopenedConfig = await (await pending).json();
  if (reopenedConfig.config?.sql !== sql) throw new Error("disk reopen lost authoritative config");

  // Native Palette buttons also support pointer clicks and keyboard Enter.
  // A Chinese search must find the localized transform and the result must
  // still add the original technical pluginId through the real Hop API.
  // Each activation must make exactly one authoritative add request and
  // survive a real disk save/reopen, not merely create a local canvas node.
  await page.locator("form.generic-config").getByRole("button", { name: "Cancel" }).click();
  const paletteSearch = page.getByRole("textbox", { name: "Search transforms" });
  await paletteSearch.fill("表输入");
  if (!(await page.getByRole("button", { name: accessibleName("TableInput"), exact: true }).isVisible()) ||
      await page.getByRole("button", { name: accessibleName("SelectValues"), exact: true }).count() !== 0) {
    throw new Error("Chinese Palette search did not filter to TableInput");
  }
  let activationGraph = reopened;
  const activated = [];
  for (const [pluginId, method] of [["TableInput", "click"], ["SelectValues", "Enter"]]) {
    const beforeIds = new Set(activationGraph.nodes.map(node => node.id));
    const countAdds = () => requests.filter(({ url, method: verb }) =>
      verb === "POST" && new URL(url).pathname.endsWith("/edit/add")).length;
    const beforeCount = countAdds();
    pending = post("/edit/add");
    const button = page.getByRole("button", { name: accessibleName(pluginId), exact: true });
    if (method === "click") await button.click();
    else {
      await button.focus();
      await page.keyboard.press("Enter");
    }
    const response = await pending;
    if (!response.ok()) throw new Error(`Palette ${method} add ${pluginId} HTTP ${response.status()}`);
    activationGraph = await response.json();
    const node = activationGraph.nodes.find(candidate => !beforeIds.has(candidate.id));
    if (!node || node.pluginId !== pluginId || activationGraph.nodes.length !== beforeIds.size + 1) {
      throw new Error(`Palette ${method} did not add exactly one real ${pluginId} node`);
    }
    await rf(node.id).waitFor({ state: "visible" });
    if (countAdds() !== beforeCount + 1) {
      throw new Error(`Palette ${method} issued duplicate /edit/add requests for ${pluginId}`);
    }
    activated.push(node);
    // Restore the full Palette before the next keyboard activation.
    if (method === "click") await paletteSearch.fill("");
  }

  pending = post("/save");
  await page.getByRole("button", { name: "保存（Save）", exact: true }).click();
  const activatedSaved = await (await pending).json();
  pending = post("/api/pipelines/open");
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const activatedReopened = await (await pending).json();
  for (const node of activated) {
    if (!activatedSaved.nodes.some(candidate => candidate.id === node.id && candidate.pluginId === node.pluginId) ||
        !activatedReopened.nodes.some(candidate => candidate.id === node.id && candidate.pluginId === node.pluginId)) {
      throw new Error(`Palette click/Enter node ${node.pluginId} missing after real save/reopen`);
    }
  }

  // Two real TableInput transforms share the same plugin descriptor but have
  // different document identities. The Chinese config header must identify the
  // selected node so edits cannot be mistaken for a neighboring TableInput.
  // Opening/canceling these panels must not issue any config/write requests.
  const tableNodes = [added[0], activated.find(node => node.pluginId === "TableInput")];
  if (tableNodes.some(node => !node) || tableNodes[0].id === tableNodes[1].id) {
    throw new Error("Two distinct real TableInput nodes are required for config identity proof");
  }
  const configRequestCount = suffix => requests.filter(({ url, method }) =>
    method === "POST" && new URL(url).pathname.endsWith(suffix)).length;
  // The real file-open response can arrive before React has switched from
  // loading to the new graph; wait for the server-backed editor identity.
  await page.locator(`.file-identity[data-document-name="${activatedReopened.name}"]`).waitFor({ state: "visible" });
  const readsBeforeIdentity = configRequestCount("/config/read");
  const writesBeforeIdentity = configRequestCount("/config/write");
  for (const node of tableNodes) {
    const target = rf(node.id);
    await target.waitFor({ state: "visible" });
    const visibleName = (await target.locator(".transform-node-copy strong").innerText()).trim();
    // Native keyboard activation remains possible when auto-placed nodes
    // visually overlap: focus the actual node-name button and press Enter.
    // This must invoke the same real Hop config/read as pointer activation.
    const configure = target.getByRole("button", { name: `配置转换：${visibleName}`, exact: true });
    await configure.focus();
    pending = post("/config/read");
    await page.keyboard.press("Enter");
    const response = await pending;
    const config = await response.json();
    if (!response.ok() || config.nodeId !== node.id || config.pluginId !== "TableInput") {
      throw new Error("Config identity read did not target the selected real TableInput: " + node.id);
    }

    const configPanel = page.locator("form.generic-config");
    await configPanel.waitFor({ state: "visible" });
    const heading = (await configPanel.locator("header strong").innerText()).trim();
    const detail = (await configPanel.locator("header small").innerText()).trim();
    if (heading !== `转换配置：${visibleName}` ||
        !detail.startsWith(`节点：${node.id} · `) ||
        !detail.includes(config.descriptor.className)) {
      throw new Error("Config panel mislabeled selected TableInput identity: " +
        JSON.stringify({ nodeId: node.id, heading, detail }));
    }
    await configPanel.getByRole("button", { name: "取消配置（Cancel）", exact: true }).click();
    await configPanel.waitFor({ state: "hidden" });
  }
  if (configRequestCount("/config/read") !== readsBeforeIdentity + 2 ||
      configRequestCount("/config/write") !== writesBeforeIdentity) {
    throw new Error("Inspecting two TableInput identities must read twice without writing config");
  }
  console.log("V6 config identity proof passed: two distinct real TableInput nodes, Chinese target labels, no config writes.");

  // Native click and keyboard additions also update the same recent section;
  // reopening a real file must not discard session-only component shortcuts.
  await assertRecentOrder(["SelectValues", "TableInput", "FilterRows"]);
  const beforeRecentAdd = new Set(activatedReopened.nodes.map(node => node.id));
  const addRequestsBefore = requests.filter(({ url, method }) =>
    method === "POST" && new URL(url).pathname.endsWith("/edit/add")).length;
  pending = post("/edit/add");
  await recent.getByRole("button", { name: "最近使用：表输入（TableInput）", exact: true }).click();
  const recentAddResponse = await pending;
  if (!recentAddResponse.ok()) throw new Error(`Recent Palette click HTTP ${recentAddResponse.status()}`);
  const recentAddedGraph = await recentAddResponse.json();
  const recentAdded = recentAddedGraph.nodes.find(node => !beforeRecentAdd.has(node.id));
  if (!recentAdded || recentAdded.pluginId !== "TableInput" ||
      recentAddedGraph.nodes.length !== beforeRecentAdd.size + 1) {
    throw new Error("Recent Palette click did not create exactly one authoritative TableInput");
  }
  await rf(recentAdded.id).waitFor({ state: "visible" });
  await assertRecentOrder(["TableInput", "SelectValues", "FilterRows"]);
  const addRequestsAfter = requests.filter(({ url, method }) =>
    method === "POST" && new URL(url).pathname.endsWith("/edit/add")).length;
  if (addRequestsAfter !== addRequestsBefore + 1) throw new Error("Recent Palette shortcut issued duplicate add requests");
  pending = post("/save");
  await page.getByRole("button", { name: "保存（Save）", exact: true }).click();
  const recentSaved = await (await pending).json();
  if (!recentSaved.nodes.some(node => node.id === recentAdded.id)) throw new Error("Recent shortcut add missing after disk save");
  pending = post("/api/pipelines/open");
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const recentReopened = await (await pending).json();
  if (!recentReopened.nodes.some(node => node.id === recentAdded.id && node.pluginId === "TableInput")) {
    throw new Error("Recent shortcut add missing after real .hpl disk reopen");
  }
  await assertRecentOrder(["TableInput", "SelectValues", "FilterRows"]);
  console.log("V6 recent Palette proof passed: successful drag/click/Enter recency, 3-item dedupe, shortcut real add, disk reopen.");

  // REST is visible in the P0 Chinese catalog, but a real Hop runtime may
  // reject creating this plugin (currently HTTP 422). Verify the actual
  // response instead of treating a rendered Palette entry as runnable.
  // If T3 later enables REST, prove the successful path against disk too.
  const restBeforeNodes = new Set(recentReopened.nodes.map(node => node.id));
  const restBeforeEdges = recentReopened.edges.map(edge => edge.id).sort();
  // A real open response can precede the React Flow commit. The REST failure
  // proof must compare against the fully rendered reopened graph, not against
  // a transient canvas from the preceding document generation.
  await page.waitForFunction(({ nodeCount, edgeCount }) =>
    document.querySelectorAll(".react-flow__node").length === nodeCount &&
    document.querySelectorAll(".react-flow__edge").length === edgeCount,
    { nodeCount: restBeforeNodes.size, edgeCount: restBeforeEdges.length },
    { timeout: 10000 });
  const restBeforeRecent = await recent.locator(".transform-items button").evaluateAll(buttons =>
    buttons.map(button => button.getAttribute("aria-label")));
  const restAddCount = () => requests.filter(({ url, method }) =>
    method === "POST" && new URL(url).pathname.endsWith("/edit/add")).length;
  const restAddsBefore = restAddCount();
  const restPending = post("/edit/add");
  await palette.getByRole("button", { name: "REST 客户端（Rest）", exact: true }).click();
  const restResponse = await restPending;
  if (restAddCount() !== restAddsBefore + 1) {
    throw new Error("REST Palette activation issued duplicate or missing real add requests");
  }
  if (restResponse.status() === 422) {
    await page.locator(".preview-note.error-note[role=alert]").waitFor({ state: "visible" });
    // Failed edits reconcile React Flow nodes from the authoritative graph.
    // Edge DOM elements may briefly unmount while the unchanged nodes are
    // remeasured; assert the settled UI, not that intermediate render frame.
    // This must still time out if any real edge or node remains missing.
    await page.waitForFunction(({ nodeCount, edgeCount }) =>
      document.querySelectorAll(".react-flow__node").length === nodeCount &&
      document.querySelectorAll(".react-flow__edge").length === edgeCount,
      { nodeCount: restBeforeNodes.size, edgeCount: restBeforeEdges.length },
      { timeout: 10000 });
    const recentAfterReject = await recent.locator(".transform-items button").evaluateAll(buttons =>
      buttons.map(button => button.getAttribute("aria-label")));
    const actualNodes = await page.locator(".react-flow__node").count();
    const actualEdges = await page.locator(".react-flow__edge").count();
    const saveEnabled = await page.getByRole("button", { name: "保存（Save）", exact: true }).isEnabled();
    const recentUnchanged = JSON.stringify(recentAfterReject) === JSON.stringify(restBeforeRecent);
    if (actualNodes !== restBeforeNodes.size || actualEdges !== restBeforeEdges.length ||
        saveEnabled || !recentUnchanged) {
      // Keep diagnostics to non-sensitive counts and booleans only.
      throw new Error("Rejected REST add changed editor state: " + JSON.stringify({
        expectedNodes: restBeforeNodes.size, actualNodes,
        expectedEdges: restBeforeEdges.length, actualEdges,
        saveEnabled, recentUnchanged,
      }));
    }
    const rejectedReopenPending = post("/api/pipelines/open");
    await page.getByRole("button", { name: "Choose pipeline" }).click();
    await page.getByRole("button", { name: "Open pipeline" }).click();
    const rejectedReopenResponse = await rejectedReopenPending;
    if (!rejectedReopenResponse.ok()) throw new Error("Real Hop reopen after REST rejection failed");
    const rejectedReopened = await rejectedReopenResponse.json();
    if (rejectedReopened.nodes.length !== restBeforeNodes.size ||
        !rejectedReopened.nodes.every(node => restBeforeNodes.has(node.id)) ||
        JSON.stringify(rejectedReopened.edges.map(edge => edge.id).sort()) !== JSON.stringify(restBeforeEdges)) {
      throw new Error("REST rejection changed the authoritative disk graph");
    }
    console.log("V6 REST runtime rejection proof passed: real HTTP 422, no phantom node/recent item, clean disk reopen.");
  } else if (restResponse.ok()) {
    const restGraph = await restResponse.json();
    const restAdded = restGraph.nodes.filter(node => !restBeforeNodes.has(node.id));
    if (restAdded.length !== 1 || restAdded[0].pluginId !== "Rest" ||
        await recent.getByRole("button", { name: "最近使用：REST 客户端（Rest）", exact: true }).count() !== 1) {
      throw new Error("Successful REST add lacks exactly one authoritative Rest node and recent shortcut");
    }
    const restSavePending = post("/save");
    await page.getByRole("button", { name: "保存（Save）", exact: true }).click();
    const restSaveResponse = await restSavePending;
    if (!restSaveResponse.ok()) throw new Error("Real REST disk save failed after successful add");
    const restReopenPending = post("/api/pipelines/open");
    await page.getByRole("button", { name: "Choose pipeline" }).click();
    await page.getByRole("button", { name: "Open pipeline" }).click();
    const restReopenResponse = await restReopenPending;
    const restReopened = await restReopenResponse.json();
    if (!restReopenResponse.ok() ||
        !restReopened.nodes.some(node => node.id === restAdded[0].id && node.pluginId === "Rest")) {
      throw new Error("Real REST add did not survive authoritative disk reopen");
    }
    console.log("V6 REST runtime success proof passed: real add, recent shortcut, disk save and reopen.");
  } else {
    throw new Error("Unexpected REST runtime HTTP status " + restResponse.status());
  }


  // The P0 catalog alone cannot prove runtime support. Probe two additional
  // categories through real browser commands. Some native Hop plugins reject
  // default construction with HTTP 422; verify fail-closed behavior rather
  // than claiming that a visible Chinese Palette item is executable.
  const additionalOpenPending = post("/api/pipelines/open");
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const additionalOpenResponse = await additionalOpenPending;
  if (!additionalOpenResponse.ok()) throw new Error("P0 cross-category baseline reopen failed");
  let additionalGraph = await additionalOpenResponse.json();
  const baselineEdges = additionalGraph.edges.map(edge => edge.id).sort();
  const acceptedCategoryNodes = [];
  const rejectedCategoryPlugins = [];
  const awaitSettledGraph = async graphToWaitFor => page.waitForFunction(({ nodeCount, edgeCount }) =>
    document.querySelectorAll(".react-flow__node").length === nodeCount &&
    document.querySelectorAll(".react-flow__edge").length === edgeCount,
    { nodeCount: graphToWaitFor.nodes.length, edgeCount: graphToWaitFor.edges.length },
    { timeout: 10000 });
  await awaitSettledGraph(additionalGraph);
  for (const [pluginId, chineseName] of [["DataGrid", "数据网格"], ["TableOutput", "表输出"]]) {
    const beforeIds = new Set(additionalGraph.nodes.map(node => node.id));
    const beforeRecent = await recent.locator(".transform-items button").evaluateAll(buttons =>
      buttons.map(button => button.getAttribute("aria-label")));
    const countAdds = () => requests.filter(({ url, method }) =>
      method === "POST" && new URL(url).pathname.endsWith("/edit/add")).length;
    const beforeAddCount = countAdds();
    const categoryAddPending = post("/edit/add");
    await palette.getByRole("button", { name: `${chineseName}（${pluginId}）`, exact: true }).click();
    const categoryAddResponse = await categoryAddPending;
    if (categoryAddResponse.request().postDataJSON()?.pluginId !== pluginId ||
        countAdds() !== beforeAddCount + 1) {
      throw new Error(`Real P0 ${pluginId} Palette issued the wrong or duplicate backend add`);
    }
    if (categoryAddResponse.status() === 422) {
      await page.locator(".preview-note.error-note[role=alert]").waitFor({ state: "visible" });
      await awaitSettledGraph(additionalGraph);
      const afterRecent = await recent.locator(".transform-items button").evaluateAll(buttons =>
        buttons.map(button => button.getAttribute("aria-label")));
      if (JSON.stringify(afterRecent) !== JSON.stringify(beforeRecent) ||
          await page.getByRole("button", { name: "保存（Save）", exact: true }).isEnabled()) {
        throw new Error(`Rejected P0 ${pluginId} add created a phantom recent shortcut or dirty state`);
      }
      const rejectedReopenPending = post("/api/pipelines/open");
      await page.getByRole("button", { name: "Choose pipeline" }).click();
      await page.getByRole("button", { name: "Open pipeline" }).click();
      const rejectedReopenResponse = await rejectedReopenPending;
      if (!rejectedReopenResponse.ok()) throw new Error(`P0 ${pluginId} rejection disk reopen failed`);
      const rejectedGraph = await rejectedReopenResponse.json();
      if (rejectedGraph.nodes.length !== beforeIds.size ||
          !rejectedGraph.nodes.every(node => beforeIds.has(node.id)) ||
          JSON.stringify(rejectedGraph.edges.map(edge => edge.id).sort()) !== JSON.stringify(baselineEdges)) {
        throw new Error(`Rejected P0 ${pluginId} add changed authoritative disk graph`);
      }
      additionalGraph = rejectedGraph;
      await awaitSettledGraph(additionalGraph);
      rejectedCategoryPlugins.push(pluginId);
      continue;
    }
    if (!categoryAddResponse.ok()) {
      throw new Error(`Unexpected P0 ${pluginId} add HTTP ${categoryAddResponse.status()}`);
    }
    const nextGraph = await categoryAddResponse.json();
    const addedNodes = nextGraph.nodes.filter(node => !beforeIds.has(node.id));
    if (addedNodes.length !== 1 || addedNodes[0].pluginId !== pluginId ||
        nextGraph.nodes.length !== beforeIds.size + 1 ||
        JSON.stringify(nextGraph.edges.map(edge => edge.id).sort()) !== JSON.stringify(baselineEdges)) {
      throw new Error(`Successful P0 ${pluginId} add changed the wrong authoritative graph identity`);
    }
    await rf(addedNodes[0].id).waitFor({ state: "visible" });
    if (await rf(addedNodes[0].id).locator(".transform-node-content").getAttribute("data-plugin-id") !== pluginId ||
        await recent.getByRole("button", { name: `最近使用：${chineseName}（${pluginId}）`, exact: true }).count() !== 1) {
      throw new Error(`Successful P0 ${pluginId} lacks matching node identity or recent shortcut`);
    }
    await page.locator(".dirty-state.is-dirty").waitFor({ state: "visible" });
    const categorySavePending = post("/save");
    await page.getByRole("button", { name: "保存（Save）", exact: true }).click();
    if (!(await categorySavePending).ok()) throw new Error(`Real P0 ${pluginId} disk save failed`);
    await page.locator(".dirty-state.is-dirty").waitFor({ state: "hidden" });
    const categoryReopenPending = post("/api/pipelines/open");
    await page.getByRole("button", { name: "Choose pipeline" }).click();
    await page.getByRole("button", { name: "Open pipeline" }).click();
    const categoryReopenResponse = await categoryReopenPending;
    if (!categoryReopenResponse.ok()) throw new Error(`Real P0 ${pluginId} disk reopen failed`);
    additionalGraph = await categoryReopenResponse.json();
    if (!additionalGraph.nodes.some(node => node.id === addedNodes[0].id && node.pluginId === pluginId) ||
        additionalGraph.nodes.length !== nextGraph.nodes.length ||
        JSON.stringify(additionalGraph.edges.map(edge => edge.id).sort()) !== JSON.stringify(baselineEdges)) {
      throw new Error(`Real P0 ${pluginId} disk reopen lost the saved transform`);
    }
    await awaitSettledGraph(additionalGraph);
    acceptedCategoryNodes.push({ id: addedNodes[0].id, pluginId });
  }
  if (!acceptedCategoryNodes.every(({ id, pluginId }) =>
      additionalGraph.nodes.some(node => node.id === id && node.pluginId === pluginId))) {
    throw new Error("Later P0 runtime probe discarded an earlier successfully saved transform");
  }
  console.log("V6 cross-category P0 runtime proof passed: " +
    acceptedCategoryNodes.map(node => node.pluginId).join(",") + " persisted; " +
    rejectedCategoryPlugins.join(",") + " returned real HTTP 422 without phantom edits.");

  // A failed P0 add must not erase a legitimate unsaved edit. This differs
  // from the clean-graph rejection checks above: the dirty graph must remain
  // savable and persist the preceding successful transform to real .hpl.
  const dirtyBaselineIds = new Set(additionalGraph.nodes.map(node => node.id));
  const dirtyAddPending = post("/edit/add");
  await palette.getByRole("button", { name: "表输入（TableInput）", exact: true }).click();
  const dirtyAddResponse = await dirtyAddPending;
  if (!dirtyAddResponse.ok()) throw new Error(`Dirty-rejection setup TableInput HTTP ${dirtyAddResponse.status()}`);
  const dirtyGraph = await dirtyAddResponse.json();
  const dirtyAdded = dirtyGraph.nodes.filter(node => !dirtyBaselineIds.has(node.id));
  if (dirtyAdded.length !== 1 || dirtyAdded[0].pluginId !== "TableInput") {
    throw new Error("Dirty-rejection setup must add exactly one real TableInput");
  }
  await rf(dirtyAdded[0].id).waitFor({ state: "visible" });
  await page.locator(".dirty-state.is-dirty").waitFor({ state: "visible" });
  const dirtyRecent = await recent.locator(".transform-items button").evaluateAll(buttons =>
    buttons.map(button => button.getAttribute("aria-label")));
  const dirtyRestPending = post("/edit/add");
  await palette.getByRole("button", { name: "REST 客户端（Rest）", exact: true }).click();
  const dirtyRestResponse = await dirtyRestPending;
  let expectedDirtyGraph = dirtyGraph;
  if (dirtyRestResponse.status() === 422) {
    await page.locator(".preview-note.error-note[role=alert]").waitFor({ state: "visible" });
    await awaitSettledGraph(dirtyGraph);
    const dirtyRecentAfter = await recent.locator(".transform-items button").evaluateAll(buttons =>
      buttons.map(button => button.getAttribute("aria-label")));
    if (JSON.stringify(dirtyRecentAfter) !== JSON.stringify(dirtyRecent) ||
        await page.locator(".dirty-state.is-dirty").count() !== 1 ||
        !(await page.getByRole("button", { name: "保存（Save）", exact: true }).isEnabled())) {
      throw new Error("Rejected REST add erased or contaminated an unsaved real TableInput edit");
    }
    console.log("V6 dirty-rejection preservation proof passed: real REST HTTP 422 retained unsaved TableInput and Save.");
  } else if (dirtyRestResponse.ok()) {
    expectedDirtyGraph = await dirtyRestResponse.json();
    const newlyAcceptedRest = expectedDirtyGraph.nodes.filter(node =>
      !dirtyGraph.nodes.some(before => before.id === node.id));
    if (newlyAcceptedRest.length !== 1 || newlyAcceptedRest[0].pluginId !== "Rest") {
      throw new Error("Newly supported REST add must create exactly one authoritative Rest node");
    }
  } else {
    throw new Error(`Unexpected dirty-graph REST HTTP ${dirtyRestResponse.status()}`);
  }
  const dirtySavePending = post("/save");
  await page.getByRole("button", { name: "保存（Save）", exact: true }).click();
  const dirtySaveResponse = await dirtySavePending;
  if (!dirtySaveResponse.ok()) throw new Error("Dirty graph failed to save after REST runtime response");
  const dirtySaved = await dirtySaveResponse.json();
  if (dirtySaved.nodes.length !== expectedDirtyGraph.nodes.length ||
      !dirtySaved.nodes.some(node => node.id === dirtyAdded[0].id && node.pluginId === "TableInput")) {
    throw new Error("Dirty graph save lost the previously accepted TableInput");
  }
  await page.locator(".dirty-state.is-dirty").waitFor({ state: "hidden" });
  const dirtyReopenPending = post("/api/pipelines/open");
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const dirtyReopenResponse = await dirtyReopenPending;
  if (!dirtyReopenResponse.ok()) throw new Error("Dirty graph disk reopen failed");
  const dirtyReopened = await dirtyReopenResponse.json();
  if (dirtyReopened.nodes.length !== expectedDirtyGraph.nodes.length ||
      !dirtyReopened.nodes.some(node => node.id === dirtyAdded[0].id && node.pluginId === "TableInput") ||
      JSON.stringify(dirtyReopened.edges.map(edge => edge.id).sort()) !==
        JSON.stringify(expectedDirtyGraph.edges.map(edge => edge.id).sort())) {
    throw new Error("Dirty graph disk reopen lost a previously successful edit after REST runtime response");
  }
  console.log("V6 dirty-graph disk proof passed: prior successful edit survived REST response, save, and real .hpl reopen.");

  const rap = requests.filter(({ url }) => {
    const pathname = new URL(url).pathname;
    return pathname === "/ui" || pathname.startsWith("/ui/");
  });
  if (rap.length) throw new Error("RAP /ui entered");

  console.log("V6 Palette authoritative proof passed: full 20-plugin Chinese catalog; drag TableInput/SelectValues/FilterRows; click TableInput; Enter SelectValues; real disk reopen");
} finally {
  await browser.close();
}
