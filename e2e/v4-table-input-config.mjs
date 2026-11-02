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
  // Genuine browser interaction with the resizable right-side configuration surface.
  // Resizing must never issue Hop config/write or disturb the native .hpl draft.
  await page.setViewportSize({ width: 1365, height: 768 });
  const grip = panel.getByRole("separator", { name: "调整配置面板宽度" });
  await grip.waitFor({ state: "visible" });
  const initialPanelBox = await panel.boundingBox();
  const gripBox = await grip.boundingBox();
  if (!initialPanelBox || !gripBox || Math.abs(initialPanelBox.width - 600) > 4) {
    throw new Error("Desktop configuration panel did not open at the expected 600px width");
  }
  const dragX = gripBox.x + gripBox.width / 2;
  const dragY = gripBox.y + gripBox.height / 2;
  await page.mouse.move(dragX, dragY);
  await page.mouse.down();
  await page.mouse.move(dragX - 110, dragY, { steps: 6 });
  await page.mouse.up();
  const widenedBox = await panel.boundingBox();
  if (!widenedBox || widenedBox.width < initialPanelBox.width + 80) {
    throw new Error("Dragging the configuration grip did not expand the real editor");
  }
  await grip.focus();
  await grip.press("ArrowRight");
  const keyboardBox = await panel.boundingBox();
  if (!keyboardBox || Math.abs(keyboardBox.width - (widenedBox.width - 24)) > 3) {
    throw new Error("Keyboard resizing did not shrink the configuration panel by 24px");
  }
  await page.setViewportSize({ width: 760, height: 768 });
  const narrowBox = await panel.boundingBox();
  if (!narrowBox || narrowBox.width > 421 || await grip.isVisible()) {
    throw new Error("Narrow viewport failed to use the safe fixed-width configuration layout");
  }
  await page.setViewportSize({ width: 1365, height: 768 });
  if (requests.some(({ url, method }) => method === "POST" && new URL(url).pathname.endsWith("/config/write"))) {
    throw new Error("Resizing must not write native Hop configuration");
  }
  console.log("V4 config resize proof passed: pointer, keyboard, narrow-screen fallback, no Hop write.");
  const connection = panel.locator(`#config-${connectionDescriptor.javaField}`);
  const sqlField = panel.locator(`#config-${sqlDescriptor.javaField}`);
  if (await connection.inputValue() !== "Warehouse") throw new Error("Authoritative connection not visible");
  if (await sqlField.inputValue() !== "SELECT id, name FROM customers") throw new Error("Authoritative SQL not visible");
  // Search only descriptor labels/keys: real Hop values and secrets must never
  // enter search suggestions. Filtering must not submit or discard an unsaved draft.
  const fieldFinder = panel.getByRole("searchbox", { name: "查找配置字段" });
  const finderWrites = () => requests.filter(({ url, method }) =>
    method === "POST" && new URL(url).pathname.endsWith("/config/write")).length;
  const writesBeforeFinder = finderWrites();
  await fieldFinder.fill("sql");
  if (!await sqlField.isVisible() || await connection.isVisible() ||
      !(await panel.locator(".config-field-search").innerText()).includes("显示 1 /")) {
    throw new Error("Real TableInput SQL field finder did not narrow the configuration form");
  }
  await fieldFinder.press("Enter");
  if (finderWrites() !== writesBeforeFinder) throw new Error("Pressing Enter in field finder submitted a Hop write");
  const temporaryDraft = "SELECT id FROM customers /* 未保存的字段查找草稿 */";
  await sqlField.fill(temporaryDraft);
  await fieldFinder.fill("连接");
  if (!await connection.isVisible() || await sqlField.isVisible()) {
    throw new Error("Chinese field search did not switch visible configuration fields");
  }
  await fieldFinder.fill("__no_such_config_field__");
  if (!await panel.getByRole("status", { name: "没有匹配的配置字段，请更换关键词。" }).isVisible() ||
      await panel.locator("fieldset").count() !== 0) {
    throw new Error("Unmatched config field query must show a safe empty state");
  }
  await panel.getByRole("button", { name: "清除字段查找" }).click();
  if (!await sqlField.isVisible() || !await connection.isVisible() ||
      await sqlField.inputValue() !== temporaryDraft) {
    throw new Error("Clearing field search lost the unsaved real Hop SQL draft");
  }
  await sqlField.fill("SELECT id, name FROM customers");
  if (finderWrites() !== writesBeforeFinder) throw new Error("Field search/draft navigation wrote Hop configuration");
  console.log("V4 config field finder proof passed: Chinese search, empty/clear, draft preservation, Enter no-write.");
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
  // Exercise literal XML metacharacters and an HTML/script-looking SQL string through
  // the real Hop JSON -> .hpl XML -> JSON round trip, without executing the SQL.
  const sql = 'SELECT id AS "黄河编号", name AS "水库名称" FROM customers WHERE name = \'三门峡\' AND note <> \'</textarea><script>window.__hopModernXss = true</script>&\'';
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
  if (await page.evaluate(() => Object.prototype.hasOwnProperty.call(window, "__hopModernXss"))) {
    throw new Error("SQL-like HTML escaped from the configuration textarea into executable script");
  }
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
  if (await page.evaluate(() => Object.prototype.hasOwnProperty.call(window, "__hopModernXss"))) {
    throw new Error("Reopened XML metacharacter SQL executed script-like content in the browser");
  }
  console.log("V4 XML metacharacter and inert HTML-like SQL proof passed: real Hop config/write, disk save, reopen.");
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
