import { chromium } from "playwright";
import { copyFile, readFile } from "node:fs/promises";
import path from "node:path";
import assert from "node:assert/strict";

const isolated = process.env.HMW_ISOLATED_URL;
const workspace = process.env.HMW_ISOLATED_WORKSPACE;
assert.ok(isolated && workspace, "requires isolated JVM");
const fixture = path.join(workspace, "native-ui-action.hwf");
await copyFile("/home/ubuntu/hmw-agent-lab/workspaces/t2/two-actions.hwf", fixture);
const browser = await chromium.launch({headless:true, args:["--no-sandbox"]});
const page = await browser.newPage({ viewport:{width:1440,height:900} });
const errors = [];
page.on("pageerror",e => errors.push(e.message));
const calls = [];
await page.route("**/t2/api/workflows/**", async route => {
  const uri = new URL(route.request().url());
  const nextUrl = isolated + uri.pathname.replace(/^\/t2/, "") + uri.search;
  try {
    const response = await route.fetch({url:nextUrl});
    calls.push({endpoint:uri.pathname, method:route.request().method(), status:response.status()});
    await route.fulfill({response});
  } catch(e) { await route.abort(); throw e; }
});
try {
  await page.goto("http://127.0.0.1:4196/t2/", {waitUntil:"networkidle",timeout:20000});
  await page.getByRole("button", {name:/打开 Workflow/}).click();
  const dialog = page.getByRole("dialog", {name:"Workflow 文件编辑"});
  await dialog.locator("#workflow-path").fill("native-ui-action.hwf");
  await dialog.getByRole("button",{name:"打开",exact:true}).click();
  await dialog.getByText("工作流节点 · 2").waitFor();
  const add = dialog.locator('form[aria-label="新增工作流节点"]');
  await add.locator('[name="actionName"]').fill("Native UI proof");
  await add.getByRole("button", {name:"新增原生节点"}).click();
  await dialog.getByText("工作流节点 · 3").waitFor();
  const linkForm = dialog.locator('form[aria-label="创建工作流连接"]');
  await linkForm.locator('[name="from"]').selectOption("Start");
  await linkForm.locator('[name="to"]').selectOption("Native UI proof");
  await linkForm.getByRole("button", {name:"创建连接"}).click();
  await dialog.getByText("执行链路 · 1 条").waitFor();

  const abortForm = dialog.locator('form[aria-label="新增工作流节点"]');
  await abortForm.locator('[name="actionType"]').selectOption("ABORT");
  await abortForm.locator('[name="actionName"]').fill("Abort on failure");
  await abortForm.locator('[name="actionMessage"]').fill("Water level threshold exceeded");
  await abortForm.locator('[name="actionLogLevel"]').selectOption("ERROR");
  await abortForm.getByRole("button", {name:"新增原生节点"}).click();
  await dialog.getByText("工作流节点 · 4").waitFor();
  await linkForm.locator('[name="from"]').selectOption("Native UI proof");
  await linkForm.locator('[name="to"]').selectOption("Abort on failure");
  await linkForm.getByRole("button", {name:"创建连接"}).click();
  await dialog.getByText("执行链路 · 2 条").waitFor();

  await dialog.getByRole("button", {name:"保存 .hwf"}).click();
  await dialog.getByText("已保存",{exact:true}).waitFor();
  const xml=await readFile(fixture,"utf8");
  assert.ok(xml.includes("<type>DUMMY</type>"),"not native Hop DUMMY XML");
  assert.ok(xml.includes("<type>ABORT</type>"),"not native Hop ABORT XML");
  assert.ok(xml.includes("Water level threshold exceeded"),"ABORT message not serialized");
  await dialog.getByRole("button",{name:"打开",exact:true}).click();
  await dialog.getByText("工作流节点 · 4").waitFor();
  assert.ok(await dialog.getByText("Native UI proof", {exact:false}).count()>0);
  assert.ok(await dialog.getByText("Abort on failure", {exact:false}).count()>0);
  assert.equal(errors.length,0,"browser errors");
  for (const name of ["open","action-add","hop-create","save"])
    assert.ok(calls.some(c=>c.endpoint.endsWith("/"+name)&&c.status===200), "API missing "+name);
  assert.equal(calls.filter(c=>c.endpoint.endsWith("/action-add")&&c.status===200).length,2,"both native action types must be created");
  console.log(JSON.stringify({pass:true, nativeActions:["DUMMY","ABORT"], uiAdd:true, connected:true, diskReopen:true,
      http:calls, browserErrors:errors}));
} finally { await browser.close(); }