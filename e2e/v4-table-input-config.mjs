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
  // Contextual node actions must be discoverable in Chinese while preserving
  // their existing real Hop configure/delete behavior.
  const nodeActions = page.locator(".node-context-actions");
  await nodeActions.waitFor({ state: "visible" });
  const nodeTitle = (await nodeActions.locator(".node-action-label").innerText()).trim();
  if (!nodeTitle ||
      await nodeActions.getByRole("button", { name: `配置转换：${nodeTitle}（Configure）`, exact: true }).count() !== 1 ||
      await nodeActions.getByRole("button", { name: `删除转换：${nodeTitle}（Delete）`, exact: true }).count() !== 1) {
    throw new Error("Real TableInput node lacks Chinese-first contextual action names");
  }
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
  // The real Hop configuration dialog must expose Chinese-first accessible
  // action names while retaining the English fallback for existing clients.
  if (await panel.getByRole("button", { name: "取消配置（Cancel）", exact: true }).count() !== 1 ||
      await panel.getByRole("button", { name: "保存配置（Save）", exact: true }).count() !== 1) {
    throw new Error("Real TableInput configuration dialog lacks Chinese-first action names");
  }
  const connection = panel.locator(`#config-${connectionDescriptor.javaField}`);
  const sqlField = panel.locator(`#config-${sqlDescriptor.javaField}`);
  if (await connection.inputValue() !== "Warehouse") throw new Error("Authoritative connection not visible");
  if (await sqlField.inputValue() !== "SELECT id, name FROM customers") throw new Error("Authoritative SQL not visible");
  // A malformed LIST JSON draft must fail closed in the real Hop editor:
  // no config/write, no enabled Save, and Cancel must restore server state.
  // This tests the existing JSON fallback, not unverified typed LIST editing.
  const listDescriptor = initial.descriptor?.properties?.find(p => p.key === "field" && p.shape === "LIST");
  if (!listDescriptor) throw new Error("Real TableInput LIST descriptor missing");
  const listField = panel.locator(`#config-${listDescriptor.javaField}`);
  const originalListText = await listField.inputValue();
  const configWriteCount = () => requests.filter(({url, method}) =>
    method === "POST" && new URL(url).pathname.endsWith("/config/write")).length;
  const writesBeforeInvalid = configWriteCount();
  await listField.fill('[{"name":');
  await panel.getByRole("alert").waitFor({ state: "visible" });
  if (await panel.getByRole("button", { name: "Save" }).isEnabled() ||
      configWriteCount() !== writesBeforeInvalid) {
    throw new Error("Malformed LIST JSON must disable Save without sending config/write");
  }
  await panel.getByRole("button", { name: "Cancel" }).click();
  const invalidRecoveryPending = page.waitForResponse(r => match(r, "/config/read"));
  await node.click();
  await page.getByRole("button", { name: "Configure" }).click();
  const invalidRecoveryResponse = await invalidRecoveryPending;
  const invalidRecovered = await invalidRecoveryResponse.json();
  if (!invalidRecoveryResponse.ok() ||
      JSON.stringify(invalidRecovered.config) !== JSON.stringify(initial.config)) {
    throw new Error("Cancel after invalid LIST JSON changed authoritative Hop config");
  }
  await panel.waitFor({ state: "visible" });
  if (await listField.inputValue() !== originalListText ||
      await panel.getByRole("alert").count() !== 0 ||
      configWriteCount() !== writesBeforeInvalid) {
    throw new Error("Invalid LIST JSON state leaked into reopened configuration");
  }
  // Chinese aliases and a Chinese literal must survive runtime config and .hpl disk XML.
  const sql = 'SELECT id AS "黄河编号", name AS "水库名称" FROM customers WHERE name = \'三门峡\'';
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
  // An unsaved SQL draft must be discarded by Cancel, without a second
  // authoritative config/write request or a mutation of the real Hop config.
  const canceledSql = 'SELECT name FROM customers WHERE name = \'未保存草稿\'';
  await sqlField.fill(canceledSql);
  await panel.getByRole("button", { name: "Cancel" }).click();
  const cancelReadPending = page.waitForResponse(r => match(r, "/config/read"));
  await node.click();
  await page.getByRole("button", { name: "Configure" }).click();
  const cancelReadResponse = await cancelReadPending;
  const cancelRead = await cancelReadResponse.json();
  if (!cancelReadResponse.ok() || cancelRead.config?.sql !== sql ||
      cancelRead.config?.connection !== "Warehouse") {
    throw new Error("Cancel must discard the unsaved SQL draft on the authoritative server");
  }
  await panel.waitFor({ state: "visible" });
  if (await sqlField.inputValue() !== sql) {
    throw new Error("Reopened configuration displayed canceled SQL rather than saved SQL");
  }
  if (requests.filter(({url,method}) => method === "POST" &&
      new URL(url).pathname.endsWith("/config/write")).length !== 1) {
    throw new Error("Cancel must not issue an additional config/write request");
  }
  // Deleting the sole real Hop transform must reveal the Chinese empty-flow
  // guidance; undo must restore the authoritative node and hide the guidance.
  await panel.getByRole("button", { name: "Cancel" }).click();
  await page.locator(".dirty-state.is-dirty").waitFor({ state: "visible" });
  const diskSavePending = page.waitForResponse(r => match(r, "/save"));
  await page.getByRole("button", { name: "保存（Save）", exact: true }).click();
  const diskSaveResponse = await diskSavePending;
  if (!diskSaveResponse.ok()) throw new Error("Unicode SQL disk save failed");
  await page.locator(".dirty-state.is-dirty").waitFor({ state: "hidden" });
  const diskReopenPending = page.waitForResponse(r => match(r, "/api/pipelines/open"));
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const diskReopenResponse = await diskReopenPending;
  const diskReopened = await diskReopenResponse.json();
  if (!diskReopenResponse.ok() || !diskReopened.nodes?.some(n => n.id === "table-input")) {
    throw new Error("Unicode SQL disk reopen lost the authoritative TableInput node");
  }
  await node.waitFor({ state: "visible" });
  await node.click();
  const diskReadPending = page.waitForResponse(r => match(r, "/config/read"));
  await page.getByRole("button", { name: "Configure" }).click();
  const diskReadResponse = await diskReadPending;
  const diskRead = await diskReadResponse.json();
  if (!diskReadResponse.ok() || diskRead.config?.sql !== sql || diskRead.config?.connection !== "Warehouse") {
    throw new Error("Chinese SQL or unchanged connection was corrupted by .hpl disk serialization");
  }
  await page.locator("form.generic-config").getByRole("button", { name: "Cancel" }).click();
  await node.click();
  const deletePending = page.waitForResponse(r => match(r, "/edit/delete"));
  await page.getByRole("button", { name: "Delete" }).click();
  const deleteResponse = await deletePending;
  const deleted = await deleteResponse.json();
  if (!deleteResponse.ok() || deleted.nodes?.length !== 0) {
    throw new Error("Deleting the sole real transform did not yield an empty graph");
  }
  const hint = page.locator(".empty-graph-hint");
  await hint.waitFor({ state: "visible" });
  if (!(await hint.innerText()).includes("从左侧拖入组件") ||
      await page.locator(".react-flow__node").count() !== 0) {
    throw new Error("Real empty graph lacks actionable Chinese guidance");
  }
  const undoPending = page.waitForResponse(r => match(r, "/edit/undo"));
  await page.getByRole("button", { name: "Undo" }).click();
  const undoResponse = await undoPending;
  const restored = await undoResponse.json();
  if (!undoResponse.ok() || !restored.nodes?.some(n => n.id === "table-input")) {
    throw new Error("Undo failed to restore the real TableInput transform");
  }
  await node.waitFor({ state: "visible" });
  await hint.waitFor({ state: "hidden" });

  const rap = requests.filter(({url}) => { const p = new URL(url).pathname; return p === "/ui" || p.startsWith("/ui/"); });
  if (rap.length) throw new Error("RAP /ui must not be entered");
  console.log("V4 generic config proof passed");
} finally {
  await browser.close();
}
