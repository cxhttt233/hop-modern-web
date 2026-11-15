/** Native Hop ABORT edit: browser cancel, exact save, injected failure/retry, Java follow-up. */
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { copyFile, readFile, unlink } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";

const isolated = process.env.HMW_ISOLATED_URL;
const live = process.env.T2_LIVE_SHARED_BACKEND === "1";
const workspace = process.env.HMW_ISOLATED_WORKSPACE ??
  process.env.T2_TEST_WORKSPACE ?? "/home/ubuntu/hmw-agent-lab/workspaces/t2";
assert.ok(isolated || live, "isolated JVM or explicit live mode required");
const basename=process.env.T2_FIXTURE_BASENAME ?? ("native-abort-edit-"+randomUUID()+".hwf");
assert.match(basename,/^native-abort-edit-[a-f0-9-]{36}\.hwf$/);
const fixture=path.join(workspace,basename);
const base="http://127.0.0.1:4196/t2/";
const endpoint=live ? new URL("api/workflows/",base).href
  : (isolated??"http://127.0.0.1:8080")+"/api/workflows/";
const browser=await chromium.launch({headless:true,args:["--no-sandbox"]});
const page=await browser.newPage({viewport:{width:1440,height:900}});
const errors=[], calls=[];
page.on("pageerror",e=>errors.push(e.message));
page.on("response",r=>{if(r.url().includes("/api/workflows/"))calls.push({
 op:new URL(r.url()).pathname.split("/").at(-1),status:r.status()});});
async function request(op,data,expected=200){
  const r=await page.request.post(endpoint+op,{data});
  const body=await r.text();
  assert.equal(r.status(),expected,op+" HTTP "+r.status()+" "+body.slice(0,400));
  return JSON.parse(body);
}
async function open(dialog){
  await dialog.locator("#workflow-path").fill(basename);
  const res=page.waitForResponse(r=>r.url().endsWith("/api/workflows/open") &&
    r.request().method()==="POST");
  await dialog.getByRole("button",{name:"打开",exact:true}).click();
  assert.equal((await res).status(),200,"native workflow open");
}
try {
  await copyFile("/home/ubuntu/hmw-agent-lab/workspaces/t2/two-actions.hwf",fixture);
  await page.goto(base,{waitUntil:"networkidle",timeout:20000});
  if(isolated){
    await page.route("**/t2/api/workflows/**",async route=>{
      const uri=new URL(route.request().url());
      const response=await route.fetch({url:isolated+uri.pathname.replace(/^\/t2/,"")+uri.search});
      await route.fulfill({response});
    });
  }
  await page.getByRole("button",{name:/打开 Workflow/}).click();
  const dialog=page.getByRole("dialog",{name:"Workflow 文件编辑"});
  await open(dialog);
  await dialog.getByText("工作流节点 · 2").waitFor();

  const add=dialog.locator('form[aria-label="新增工作流节点"]');
  await add.locator('[name="actionType"]').selectOption("ABORT");
  await add.locator('[name="actionName"]').fill("Stop on alert");
  await add.locator('[name="actionMessage"]').fill("Original water-level alert");
  await add.locator('[name="actionLogLevel"]').selectOption("ERROR");
  await add.getByRole("button",{name:"新增原生节点"}).click();
  await dialog.getByText("工作流节点 · 3").waitFor();
  const link=dialog.locator('form[aria-label="创建工作流连接"]');
  await link.locator('[name="from"]').selectOption("Start");
  await link.locator('[name="to"]').selectOption("Stop on alert");
  await link.getByRole("button",{name:"创建连接"}).click();
  await dialog.getByText("执行链路 · 1 条").waitFor();
  await dialog.getByRole("button",{name:"保存 .hwf"}).click();
  await dialog.getByText("已保存",{exact:true}).waitFor();
  await open(dialog);
  let form=dialog.locator('form[aria-label="编辑 ABORT Stop on alert"]');
  await form.waitFor();
  assert.equal(await form.locator('[name="message"]').inputValue(),"Original water-level alert");
  assert.equal(await form.locator('[name="logLevel"]').inputValue(),"ERROR");
  const savedBaseline=await readFile(fixture);

  // A reset cancels only the unsaved ABORT form, and must not call the server.
  const editsBefore=calls.filter(x=>x.op==="action-abort-edit").length;
  await form.locator('[name="message"]').fill("CANCEL THIS LOCAL CHANGE");
  await form.locator('[name="logLevel"]').selectOption("DEBUG");
  await form.getByRole("button",{name:"取消修改"}).click();
  assert.equal(await form.locator('[name="message"]').inputValue(),"Original water-level alert");
  assert.equal(await form.locator('[name="logLevel"]').inputValue(),"ERROR");
  assert.equal(calls.filter(x=>x.op==="action-abort-edit").length,editsBefore);
  assert.deepEqual(await readFile(fixture),savedBaseline,"cancel edited disk file");

  const opened=await request("open",{path:basename});
  assert.equal(opened.dirty,false);
  assert.equal((await request("action-abort-edit",{id:opened.id,name:"Stop on alert",
    message:"Original water-level alert",logLevel:"ERROR"})).dirty,false,
    "no-op ABORT edit must not mark dirty");
  await request("action-abort-edit",{id:opened.id,name:"Start",
    message:"not allowed",logLevel:"ERROR"},409);
  await request("action-abort-edit",{id:opened.id,name:"Stop on alert",
    message:"invalid level",logLevel:"TRACE"},400);
  await request("action-abort-edit",{id:opened.id,name:"Stop on alert",
    message:"",logLevel:"ERROR"},400);
  assert.deepEqual(await readFile(fixture),savedBaseline,"negative tests edited disk");

  form=dialog.locator('form[aria-label="编辑 ABORT Stop on alert"]');
  await form.locator('[name="message"]').fill("River warning: 8.25 metres; stop propagation");
  await form.locator('[name="logLevel"]').selectOption("DEBUG");
  const updated=page.waitForResponse(r=>r.url().endsWith("/action-abort-edit") &&
    r.request().method()==="POST");
  await form.getByRole("button",{name:"更新 ABORT 参数"}).click();
  assert.equal((await updated).status(),200);
  await dialog.getByText("已应用，尚未保存到磁盘").waitFor();
  form=dialog.locator('form[aria-label="编辑 ABORT Stop on alert"]');
  assert.equal(await form.locator('[name="message"]').inputValue(),
    "River warning: 8.25 metres; stop propagation");
  assert.equal(await form.locator('[name="logLevel"]').inputValue(),"DEBUG");

  await page.route("**/t2/api/workflows/save",async route=>{
    await route.fulfill({status:503,contentType:"application/json",
      body:JSON.stringify({error:"Synthetic disk save failure"})});
  },{times:1});
  const failed=page.waitForResponse(r=>r.url().endsWith("/api/workflows/save") && r.status()===503);
  await dialog.getByRole("button",{name:"保存 .hwf"}).click();
  await failed;
  await dialog.getByRole("alert").getByText("Synthetic disk save failure").waitFor();
  assert.deepEqual(await readFile(fixture),savedBaseline,"failed save overwrote original file");
  await dialog.getByText("已应用，尚未保存到磁盘").waitFor();
  assert.equal(await form.locator('[name="message"]').inputValue(),
    "River warning: 8.25 metres; stop propagation","failed save lost ABORT edit");

  const retried=page.waitForResponse(r=>r.url().endsWith("/api/workflows/save") &&
    r.request().method()==="POST");
  await dialog.getByRole("button",{name:"保存 .hwf"}).click();
  assert.equal((await retried).status(),200);
  await dialog.getByText("已保存",{exact:true}).waitFor();
  const xml=await readFile(fixture,"utf8");
  assert.match(xml,/<type>ABORT<\/type>/);
  assert.ok(xml.includes("River warning: 8.25 metres; stop propagation"));
  assert.ok(xml.includes("<loglevel>Debug</loglevel>"));
  await open(dialog);
  await dialog.getByText("工作流节点 · 3").waitFor();
  await dialog.getByText("执行链路 · 1 条").waitFor();
  form=dialog.locator('form[aria-label="编辑 ABORT Stop on alert"]');
  assert.equal(await form.locator('[name="logLevel"]').inputValue(),"DEBUG");
  assert.equal(await form.locator('[name="message"]').inputValue(),
    "River warning: 8.25 metres; stop propagation");
  assert.deepEqual(errors,[],"browser page errors");
  console.log(JSON.stringify({pass:true,feature:"existing native ABORT edit",cancelNoWrite:true,
    noOpClean:true,nonAbortDenied:409,invalidLevelDenied:400,invalidMessageDenied:400,
    saveFailure:503,saveRetry:200,diskReopen:true,hopsPreserved:true,errors}));
} finally {
  await browser.close();
  if(process.env.T2_KEEP_FIXTURE!=="1")await unlink(fixture).catch(()=>{});
}
