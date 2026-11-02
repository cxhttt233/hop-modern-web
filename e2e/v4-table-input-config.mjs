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

  // Native Hop configuration groups may be collapsed by mouse or keyboard.
  // Searching must reveal a matching control without losing the user's group
  // preference, and neither collapse nor expansion may mutate Hop configuration.
  const sqlGroup = sqlField.locator("xpath=ancestor::fieldset[1]");
  const groupToggle = sqlGroup.locator(".config-group-toggle");
  const groupWrites = () => requests.filter(({ url, method }) =>
    method === "POST" && new URL(url).pathname.endsWith("/config/write")).length;
  const writesBeforeGroup = groupWrites();
  const originalGroupSql = await sqlField.inputValue();
  const groupDraftSql = "SELECT id FROM customers /* 分组折叠未保存草稿 */";
  if (await groupToggle.getAttribute("aria-expanded") !== "true" ||
      !await sqlField.isVisible()) throw new Error("Native SQL group must start expanded");
  await sqlField.fill(groupDraftSql);
  await groupToggle.focus();
  await groupToggle.press("Enter");
  if (await groupToggle.getAttribute("aria-expanded") !== "false" ||
      await sqlField.isVisible() || groupWrites() !== writesBeforeGroup) {
    throw new Error("Keyboard collapse must hide SQL without writing native Hop config");
  }
  const groupFinder = panel.getByRole("searchbox", { name: "查找配置字段" });
  await groupFinder.fill("sql");
  if (!await sqlField.isVisible() || !await groupToggle.isDisabled() ||
      await groupToggle.getAttribute("aria-expanded") !== "true" ||
      await sqlField.inputValue() !== groupDraftSql) {
    throw new Error("Searching a collapsed group must reveal and retain its unsaved SQL draft");
  }
  await panel.getByRole("button", { name: "清除字段查找" }).click();
  if (await sqlField.isVisible() || await groupToggle.getAttribute("aria-expanded") !== "false") {
    throw new Error("Clearing config search must restore the user's collapsed group");
  }
  await groupToggle.click();
  if (!await sqlField.isVisible() || await sqlField.inputValue() !== groupDraftSql ||
      await groupToggle.getAttribute("aria-expanded") !== "true") {
    throw new Error("Reopening a group lost the unsaved native SQL draft");
  }
  await sqlField.fill(originalGroupSql);
  if (groupWrites() !== writesBeforeGroup) {
    throw new Error("Group collapse, search, and restore must not write Hop configuration");
  }
  console.log("V4 config group collapse proof passed: keyboard toggle, search reveal, draft retained, no Hop write.");

  // Count only actual unsaved field changes, independently of search filtering.
  // Never show native Hop values or send a config/write until the user saves.
  const changeSummary = panel.locator(".config-change-count[role=status]");
  const countWrites = () => requests.filter(({ url, method }) =>
    method === "POST" && new URL(url).pathname.endsWith("/config/write")).length;
  const writesBeforeCount = countWrites();
  if (await changeSummary.count() !== 0) throw new Error("Fresh native Hop config must not show unsaved changes");
  await sqlField.fill("SELECT id FROM customers /* 未保存计数 */");
  if (await changeSummary.innerText() !== "已修改 1 项（未保存）") {
    throw new Error("First edited native SQL field was not counted");
  }
  await connection.fill("Warehouse_临时");
  if (await changeSummary.innerText() !== "已修改 2 项（未保存）") {
    throw new Error("Second edited native connection field was not counted");
  }
  await panel.getByRole("searchbox", { name: "查找配置字段" }).fill("sql");
  if (await connection.isVisible() || await changeSummary.innerText() !== "已修改 2 项（未保存）") {
    throw new Error("Filtering fields must not hide unsaved changes outside the search");
  }
  // A user can review exactly which native fields are unsaved even when the
  // field finder hides one of them. Never render values or write Hop data.
  const review = panel.locator(".config-change-review");
  await review.locator("summary").click();
  const changedNames = await review.locator('ul[aria-label="未保存字段清单"] li').allInnerTexts();
  if (changedNames.length !== 2 || !changedNames.includes("连接") || !changedNames.includes("SQL") ||
      (await review.innerText()).includes("Warehouse_临时") ||
      (await review.innerText()).includes("SELECT id FROM customers") ||
      countWrites() !== writesBeforeCount) {
    throw new Error("Unsaved field review must list only native field labels, including filtered fields");
  }
  await panel.getByRole("button", { name: "清除字段查找" }).click();
  await connection.fill("Warehouse");
  if (await changeSummary.innerText() !== "已修改 1 项（未保存）" ||
      JSON.stringify(await review.locator("li").allInnerTexts()) !== JSON.stringify(["SQL"])) {
    throw new Error("Restoring native connection did not remove it from unsaved field review");
  }
  await sqlField.fill("SELECT id, name FROM customers");
  if (await changeSummary.count() !== 0 || await review.count() !== 0 ||
      countWrites() !== writesBeforeCount) {
    throw new Error("Restored native SQL draft must clear review and count without writing Hop");
  }
  console.log("V4 unsaved config count proof passed: actual field differences, filtered draft retention, revert, no values or Hop write.");
  console.log("V4 unsaved field review proof passed: labels only, filtered fields retained, live revert, no Hop write.");

  // Native TableInput prioritizes a nonempty HopVfs SQL file, but both sources
  // must remain untouched until the user explicitly saves the configuration.
  const sqlFileDescriptor = initial.descriptor?.properties?.find(p => p.key === "sql_from_file");
  if (!sqlFileDescriptor || !initial.descriptor.className.endsWith(".TableInputMeta")) {
    throw new Error("Authoritative TableInput SQL source descriptor missing");
  }
  const sqlFileField = panel.locator(`#config-${sqlFileDescriptor.javaField}`);
  const sourceNotice = panel.locator(".config-sql-source-notice[role=status]");
  const originalSqlFile = await sqlFileField.inputValue();
  const originalInlineSql = await sqlField.inputValue();
  const writesBeforeSource = countWrites();
  if ((await sourceNotice.count() > 0) !== (originalSqlFile.trim().length > 0)) {
    throw new Error("SQL source notice disagrees with the native Hop file source");
  }
  await sqlFileField.fill("   ");
  if (await sourceNotice.count() !== 0) {
    throw new Error("Whitespace-only SQL file draft must not activate file precedence");
  }
  const unsavedSqlFile = "/tmp/未保存 SQL 来源.sql";
  await sqlFileField.fill(unsavedSqlFile);
  const noticeText = await sourceNotice.innerText();
  if (!await sourceNotice.isVisible() || !noticeText.includes("文件内容优先") ||
      noticeText.includes(unsavedSqlFile) || noticeText.includes(originalInlineSql) ||
      countWrites() !== writesBeforeSource) {
    throw new Error("Native SQL file precedence guidance leaked values or wrote Hop");
  }
  const inlineSourceDraft = "SELECT id FROM customers /* 保留内联 SQL 草稿 */";
  await sqlField.fill(inlineSourceDraft);
  if (!await sourceNotice.isVisible() || await sqlField.inputValue() !== inlineSourceDraft ||
      await sqlFileField.inputValue() !== unsavedSqlFile || countWrites() !== writesBeforeSource) {
    throw new Error("File source guidance discarded an unsaved inline SQL draft");
  }
  await sqlField.fill(originalInlineSql);
  // The native fixture omits sql_from_file: its undefined wire value displays
  // as an empty input, but filling "" is not a wire-level revert. Cancel/reopen
  // proves the authoritative original is retained without changing that contract.
  await panel.getByRole("button", { name: "Cancel" }).click();
  const sourceRecoveryPending = page.waitForResponse(r => match(r, "/config/read"));
  await node.click();
  await page.getByRole("button", { name: "Configure" }).click();
  const sourceRecoveryResponse = await sourceRecoveryPending;
  const sourceRecovered = await sourceRecoveryResponse.json();
  if (!sourceRecoveryResponse.ok() ||
      JSON.stringify(sourceRecovered.config) !== JSON.stringify(initial.config)) {
    throw new Error("Canceling SQL file source draft changed authoritative Hop config");
  }
  await panel.waitFor({ state: "visible" });
  if ((await sourceNotice.count() > 0) !== (originalSqlFile.trim().length > 0) ||
      await sqlFileField.inputValue() !== originalSqlFile ||
      await sqlField.inputValue() !== originalInlineSql ||
      await changeSummary.count() !== 0 || countWrites() !== writesBeforeSource) {
    throw new Error("Cancel/reopen failed to restore original SQL sources without Hop writes");
  }
  console.log("V4 native SQL source notice proof passed: whitespace, file precedence, inline draft retained, no value leak or Hop write.");


  // Developer-only identifiers must not crowd the Chinese configuration heading.
  // This is a disclosure of schema keys, never of the native Hop configuration values.
  const developerDetails = panel.locator("details.config-developer-details");
  const developerSummary = developerDetails.locator(":scope > summary");
  const javaClass = developerDetails.locator("code").filter({ hasText: initial.descriptor.className }).first();
  const developerWrites = () => requests.filter(({ url, method }) =>
    method === "POST" && new URL(url).pathname.endsWith("/config/write")).length;
  const writesBeforeDeveloper = developerWrites();
  if (await developerDetails.evaluate(element => element.open) || await javaClass.isVisible()) {
    throw new Error("Raw Hop class details must be collapsed by default");
  }
  await developerSummary.click();
  if (!await javaClass.isVisible() ||
      !await developerDetails.getByText("table-input", { exact: true }).isVisible()) {
    throw new Error("Developer disclosure did not reveal real Hop class and node identity");
  }
  const rawKeys = developerDetails.locator("details.config-property-keys");
  await rawKeys.locator("summary").click();
  const displayedKeys = await rawKeys.locator("code").allTextContents();
  if (displayedKeys.length !== initial.descriptor.properties.length ||
      !displayedKeys.includes("sql") || displayedKeys.includes("Warehouse") ||
      displayedKeys.some(key => key.includes("SELECT id, name FROM customers"))) {
    throw new Error("Developer disclosure must list only authoritative descriptor keys, never Hop values");
  }
  const developerDraft = "SELECT id FROM customers /* 开发者详情草稿 */";
  await sqlField.fill(developerDraft);
  await developerSummary.focus();
  await developerSummary.press("Enter");
  if (await developerDetails.evaluate(element => element.open) ||
      await sqlField.inputValue() !== developerDraft ||
      developerWrites() !== writesBeforeDeveloper) {
    throw new Error("Keyboard developer disclosure changed a real Hop draft or wrote configuration");
  }
  await sqlField.fill("SELECT id, name FROM customers");
  console.log("V4 developer disclosure proof passed: collapsed raw identifiers, keyboard toggle, key-only metadata, draft preserved, no Hop write.");

  // Search only descriptor labels/keys: real Hop values and secrets must never
  // enter search suggestions. Filtering must not submit or discard an unsaved draft.
  const fieldFinder = panel.getByRole("searchbox", { name: "查找配置字段" });
  const finderWrites = () => requests.filter(({ url, method }) =>
    method === "POST" && new URL(url).pathname.endsWith("/config/write")).length;
  const writesBeforeFinder = finderWrites();
  await fieldFinder.fill("sql");
  const sqlMatches = await panel.locator("fieldset label").count();
  // TableInput exposes both sql and sql_from_file; the match count is not one.
  if (!await sqlField.isVisible() || await connection.isVisible() ||
      sqlMatches === 0 || sqlMatches >= initial.descriptor.properties.length) {
    throw new Error("Real TableInput SQL field finder did not narrow the configuration form");
  }
  // Search Enter focuses the first matching native control; the explicit
  // navigation action cycles every visible result without writing to Hop.
  const matchingControls = panel.locator("fieldset label input, fieldset label textarea, fieldset label select");
  const isFocused = async (control) => control.evaluate(element => element === document.activeElement);
  await fieldFinder.press("Enter");
  if (!await isFocused(matchingControls.first()) || finderWrites() !== writesBeforeFinder) {
    throw new Error("Search Enter must focus the first matching control without a Hop write");
  }
  const nextMatch = panel.getByRole("button", { name: "定位下一匹配字段" });
  for (let index = 1; index <= sqlMatches; index++) {
    await nextMatch.click();
    if (!await isFocused(matchingControls.nth(index % sqlMatches))) {
      throw new Error("Field finder next-result navigation lost visible descriptor order");
    }
  }
  const temporaryDraft = "SELECT id FROM customers /* 未保存的字段查找草稿 */";
  await sqlField.fill(temporaryDraft);
  await fieldFinder.fill("连接");
  if (!await connection.isVisible() || await sqlField.isVisible()) {
    throw new Error("Chinese field search did not switch visible configuration fields");
  }
  await nextMatch.click();
  if (!await isFocused(connection)) throw new Error("Chinese connection field cannot be focused from the finder");
  await fieldFinder.fill("__no_such_config_field__");
  if (!await nextMatch.isDisabled() ||
      !await panel.locator(".config-search-empty[role=status]").isVisible() ||
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
  console.log("V4 config field finder navigation proof passed: Enter focus, ordered next/wrap, empty disabled, draft preserved, no Hop write.");
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
  if (await changeSummary.count() !== 0) throw new Error("Saved authoritative SQL must clear the unsaved field count");
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
