/** Browser + isolated Hop JVM witness for FILE_EXISTS without making a parallel T3 form. */
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { copyFile, readFile, unlink } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";

const isolated=process.env.HMW_ISOLATED_URL;
assert.ok(isolated && isolated.includes("127.0.0.1:"),"FILE_EXISTS test requires isolated loopback JVM");
const workspace=process.env.HMW_ISOLATED_WORKSPACE;
assert.ok(workspace,"isolated test workspace required");
const basename=process.env.T2_FIXTURE_BASENAME??("t2-fileexists-"+randomUUID()+".hwf");
assert.match(basename,/^t2-fileexists-[a-f0-9-]{36}\.hwf$/);
const file=path.join(workspace,basename);
const base="http://127.0.0.1:4196/t2/";
const browser=await chromium.launch({headless:true,args:["--no-sandbox"]});
const page=await browser.newPage({viewport:{width:1450,height:920}});
const errors=[];
page.on("pageerror",e=>errors.push(e.message));
const api=async(route,body)=>page.evaluate(async ({route,body})=>{
  const response=await fetch("/t2/api/workflows/"+route,{
    method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)
  });
  const content=await response.text();
  try{return {status:response.status, data:JSON.parse(content)}}catch{
    return {status:response.status,data:content.slice(0,300)}
  }
},{route,body});
const create={type:"FILE_EXISTS",name:"Check input",filename:"inputs/flow.csv",x:280,y:180};
async function open(dialog) {
  await dialog.locator("#workflow-path").fill(basename);
  const res=page.waitForResponse(r=>r.url().endsWith("/api/workflows/open") &&
    r.request().method()==="POST");
  await dialog.getByRole("button",{name:"打开",exact:true}).click();
  const response=await res;
  assert.equal(response.status(),200,"native workflow open");
  return (await response.json()).id;
}
try{
  await copyFile("/home/ubuntu/hmw-agent-lab/workspaces/t2/two-actions.hwf",file);
  const original=await readFile(file);
  await page.goto(base,{waitUntil:"networkidle",timeout:25000});
  await page.route("**/t2/api/workflows/**",async route=>{
    const uri=new URL(route.request().url());
    const response=await route.fetch({url:isolated+uri.pathname.slice(3)+uri.search});
    await route.fulfill({response});
  });
  await page.getByRole("button",{name:/打开 Workflow/}).click();
  const dialog=page.getByRole("dialog",{name:"Workflow 文件编辑"});
  let id=await open(dialog);
  await dialog.getByText("工作流节点 · 2").waitFor();

  // Unsaved creation must be discarded by reopening the same disk fixture.
  let response=await api("action-add",{id,...create});
  assert.equal(response.status,200,JSON.stringify(response).slice(0,300));
  assert.equal(response.data.dirty,true);
  assert.deepEqual(await readFile(file),original,"unsaved native Action modified disk");
  id=await open(dialog);
  await dialog.getByText("工作流节点 · 2").waitFor();
  assert.deepEqual(await readFile(file),original,"discarded FILE_EXISTS change modified disk");

  response=await api("action-add",{id,...create});
  assert.equal(response.status,200);
  assert.equal(response.data.dirty,true);
  response=await api("hop-create",{id,from:"Start",to:"Check input"});
  assert.equal(response.status,200);
  assert.equal(response.data.hops.length,1);
  assert.deepEqual(await readFile(file),original,"pre-save action/hop modified disk");

  // Browser-originated save is intercepted: synthetic 503 must not clear draft.
  await page.route("**/t2/api/workflows/save",async route=>
    route.fulfill({status:503,contentType:"application/json",
      body:JSON.stringify({error:"Synthetic FILE_EXISTS save failure"})}),{times:1});
  response=await api("save",{id});
  assert.equal(response.status,503,"simulated workflow write failure");
  assert.deepEqual(await readFile(file),original,"failed save overwrote native file");
  response=await api("save",{id});
  assert.equal(response.status,200,"retry after 503 must succeed");
  assert.equal(response.data.dirty,false);
  const xml=await readFile(file,"utf8");
  assert.ok(xml.includes("<type>FILE_EXISTS</type>") &&
    xml.includes("inputs/flow.csv"),"Hop FILE_EXISTS XML not persisted");
  id=await open(dialog);
  await dialog.getByText("工作流节点 · 3").waitFor();
  await dialog.getByText("执行链路 · 1 条").waitFor();
  const full=await api("open",{path:basename});
  assert.equal(full.status,200);
  assert.ok(full.data.actions.some(a=>a.name==="Check input"&&a.pluginId==="FILE_EXISTS"&&
    a.filename==="inputs/flow.csv"),"reopened native FILE_EXISTS metadata missing");
  assert.ok(full.data.hops.some(h=>h.from==="Start"&&h.to==="Check input"),
    "native Hop action relationship missing");
  assert.deepEqual(errors,[],"browser errors");
  console.log("T2_FILE_EXISTS_HWF="+file);
  console.log("T2_FILE_EXISTS_BROWSER_DISCARD_503_RETRY_REOPEN_PASS");
}finally{
  await browser.close();
  if(process.env.T2_KEEP_FIXTURE!=="1")await unlink(file).catch(()=>{});
}
