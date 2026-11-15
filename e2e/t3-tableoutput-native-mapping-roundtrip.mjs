/** Producer-attested TableOutput mapping: native UI edit/cancel/save, Hop .hpl disk reopen. */
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { copyFile, unlink, readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";

const isolated = process.env.HMW_ISOLATED_URL;
const live = process.env.T3_LIVE_SHARED_BACKEND === "1";
const workspace = process.env.HMW_ISOLATED_WORKSPACE ??
  process.env.T3_TEST_WORKSPACE ?? "/home/ubuntu/hmw-agent-lab/workspaces/t3";
assert.ok(live || isolated, "Run in isolated Java runner or opt in to live shared backend");
const basename = process.env.T3_FIXTURE_BASENAME ?? ("tableoutput-mapping-" + randomUUID() + ".hpl");
assert.match(basename, /^tableoutput-mapping-[a-f0-9-]{36}\.hpl$/, "UUID fixture only");
const fixture = path.join(workspace, basename);
const url = "http://127.0.0.1:4196/t3/";
const backend = live ? url + "api/pipelines" : isolated + "/api/pipelines";
const browser = await chromium.launch({headless:true,args:["--no-sandbox"]});
const page = await browser.newPage({viewport:{width:1440,height:900}});
const errors = [], writes = [];
page.on("pageerror", error => errors.push(error.message));
page.on("response", r => { if (r.url().endsWith("/config/write")) writes.push(r.status()); });
async function api(endpoint, data) {
  const response = await page.request.post(backend + endpoint, {data});
  assert.equal(response.status(),200,endpoint + " must succeed");
  return response.json();
}
async function openFile() {
  await page.getByRole("button",{name:/打开流程/}).first().click();
  await page.getByRole("textbox",{name:/服务器流程路径/}).fill(basename);
  const res = page.waitForResponse(r=>r.url().endsWith("/api/pipelines/open") &&
    r.request().method()==="POST");
  await page.getByRole("button",{name:/打开文件/}).first().click();
  const opened = await res;
  assert.equal(opened.status(),200,"native .hpl open");
  const doc = await opened.json();
  assert.equal(doc.nodes.length,1);
  assert.equal(doc.nodes[0].pluginId,"TableOutput");
  return doc.id;
}
async function panel() {
  await page.locator(".react-flow__node").first().dblclick();
  const form = page.locator("form.generic-config");
  await form.waitFor();
  assert.equal(await form.locator(".config-native-list").count(),1,"native mapping table must render");
  return form;
}
async function changeMapping(form,index,key,value) {
  const label = key==="stream" ? "输入字段" : "目标表列";
  const input = form.getByRole("textbox",{name:"字段 第"+index+"行 "+label,exact:true});
  await input.fill(value);
  await input.press("Tab");
}
async function verify(id,expectedStream,expectedColumn) {
  const payload = await api("/"+id+"/config/read",{nodeId:"water-level-archive"});
  assert.equal(payload.pluginId,"TableOutput");
  const obj=payload.config;
  assert.equal(obj.connection,"Warehouse");
  assert.equal(obj.schema,"public");
  assert.equal(obj.table,"water_level_archive");
  assert.equal(obj.specify_fields,true);
  assert.equal(obj.fields.field.length,3);
  assert.deepEqual([obj.fields.field[1].stream_name,obj.fields.field[1].column_name],
    [expectedStream,expectedColumn]);
  assert.deepEqual(obj.fields.field[0],{column_name:"station_id",stream_name:"station_code"});
  assert.deepEqual(obj.fields.field[2],{column_name:"observation_time",stream_name:"observed_at"});
}
try {
  await copyFile(new URL("./fixtures/tableoutput-native-waterlevel.hpl",import.meta.url),fixture);
  const original = await readFile(fixture);
  // Let initial page load use its existing demo pipeline before attaching the isolated API.
  // Intercepting startup auto-open would point a shared sample at the empty test workspace.
  await page.goto(url,{waitUntil:"networkidle"});
  if (!live) {
    await page.route("**/t3/api/pipelines/**",async route=>{
      const uri=new URL(route.request().url());
      const response=await route.fetch({url:isolated+uri.pathname.replace(/^\/t3/,"")+uri.search});
      await route.fulfill({response});
    });
  }
  let id=await openFile();
  await verify(id,"level_m","water_level_m");
  let form=await panel();
  await changeMapping(form,2,"stream","must_not_save");
  await form.getByRole("button",{name:/取消配置/}).click();
  assert.deepEqual(await readFile(fixture),original,"cancel must not touch real .hpl");
  assert.deepEqual(writes,[],"cancel must not invoke config/write");
  id=await openFile();
  await verify(id,"level_m","water_level_m");
  form=await panel();
  await changeMapping(form,2,"stream","level_m_revised");
  await changeMapping(form,2,"column","water_level_m_verified");
  const configured=page.waitForResponse(r=>r.url().endsWith("/config/write") &&
    r.request().method()==="POST");
  await form.getByRole("button",{name:/保存配置/}).click();
  assert.equal((await configured).status(),200,"native config/write");
  const saved=page.waitForResponse(r=>r.url().endsWith("/save") && r.request().method()==="POST");
  await page.getByRole("button",{name:"保存（Save）"}).click();
  assert.equal((await saved).status(),200,"native pipeline save");
  id=await openFile();
  await verify(id,"level_m_revised","water_level_m_verified");
  assert.deepEqual(writes,[200],"exactly one committed native config/write");
  const xml=await readFile(fixture,"utf8");
  assert.ok(xml.includes("<stream_name>level_m_revised</stream_name>"),"native stream field missing in .hpl");
  assert.ok(xml.includes("<column_name>water_level_m_verified</column_name>"),"native database column missing in .hpl");
  assert.deepEqual(errors,[],"unexpected browser errors");
  console.log("T3_FIXTURE_PATH="+fixture);
  console.log(JSON.stringify({pass:true,plugin:"TableOutput",nativeListRows:3,
    cancelNoWrite:true,producerNativeXml:true,configWrite:200,diskSaveReopen:true,
    mapping:["level_m_revised","water_level_m_verified"],pageErrors:errors}));
} finally {
  await browser.close();
  if(process.env.T3_KEEP_FIXTURE!=="1") await unlink(fixture).catch(()=>{});
}