/** Actual producer-native InsertUpdate: nested two-list UI + safe Hop .hpl persistence. */
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { copyFile, unlink, readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";

const isolated=process.env.HMW_ISOLATED_URL;
const live=process.env.T3_LIVE_SHARED_BACKEND==="1";
assert.ok(isolated||live,"Run with isolated Java runner or live backend opt-in");
const workspace=process.env.HMW_ISOLATED_WORKSPACE ??
  process.env.T3_TEST_WORKSPACE ?? "/home/ubuntu/hmw-agent-lab/workspaces/t3";
const basename=process.env.T3_FIXTURE_BASENAME??("insertupdate-edit-"+randomUUID()+".hpl");
assert.match(basename,/^insertupdate-edit-[a-f0-9-]{36}\.hpl$/);
const file=path.join(workspace,basename);
const base="http://127.0.0.1:4196/t3/";
const backend=live?new URL("api/pipelines",base).href:isolated+"/api/pipelines";
const browser=await chromium.launch({headless:true,args:["--no-sandbox"]});
const page=await browser.newPage({viewport:{width:1500,height:940}});
const errors=[],writes=[];
page.on("pageerror",e=>errors.push(e.message));
page.on("response",r=>{if(r.url().endsWith("/config/write"))writes.push(r.status());});
async function api(s,data,expected=200){
  const r=await page.request.post(backend+s,{data});
  const body=await r.text();
  assert.equal(r.status(),expected,s+" HTTP "+r.status()+": "+body.slice(0,550));
  return JSON.parse(body);
}
async function open(){
  await page.getByRole("button",{name:/打开流程/}).first().click();
  await page.getByRole("textbox",{name:/服务器流程路径/}).fill(live?"/workspace/"+basename:basename);
  const openResponse=page.waitForResponse(r=>r.url().endsWith("/api/pipelines/open") &&
    r.request().method()==="POST");
  await page.getByRole("button",{name:/打开文件/}).first().click();
  const result=await openResponse;
  assert.equal(result.status(),200,"Hop InsertUpdate open");
  const data=await result.json();
  assert.equal(data.nodes.length,1);
  assert.equal(data.nodes[0].pluginId,"InsertUpdate");
  return data.id;
}
async function panel(){
  await page.locator(".react-flow__node").first().dblclick();
  const p=page.locator("form.generic-config");await p.waitFor();
  assert.equal(await p.locator(".config-native-list").count(),2,"two native nested lists must render");
  assert.equal(await p.locator("textarea").count(),0,"native InsertUpdate must not default to raw JSON");
  return p;
}
async function editCell(p,title,index,column,next){
  const input=p.getByRole("textbox",{name:title+" 第"+index+"行 "+column,exact:true});
  await input.fill(next);
  await input.press("Tab");
}
async function verify(id,stream,quality,schema,table){
  const data=await api("/"+id+"/config/read",{nodeId:"upsert-water-level"});
  assert.equal(data.pluginId,"InsertUpdate");
  const o=data.config;
  assert.equal(o.connection,"Warehouse");
  assert.equal(o.commit,"250");
  assert.equal(o.update_bypassed,false);
  assert.equal(o.lookup.schema,schema);
  assert.equal(o.lookup.table,table);
  assert.equal(o.lookup.key.length,2);
  assert.equal(o.lookup.value.length,2);
  assert.deepEqual(o.lookup.key[0],{name:stream,field:"station_id",condition:"=",name2:null});
  assert.deepEqual(o.lookup.key[1],
    {name:"observed_at",field:"observation_time",condition:"=",name2:null});
  assert.deepEqual(o.lookup.value[0],{name:"water_level_m",rename:"level_m",update:true});
  assert.deepEqual(o.lookup.value[1],{name:"quality_flag",rename:quality,update:true});
  return o;
}
try{
  await copyFile(new URL("./fixtures/insertupdate-native-waterlevel.hpl",import.meta.url),file);
  const pristine=await readFile(file);
  await page.goto(base,{waitUntil:"networkidle"});
  if(isolated){
    await page.route("**/t3/api/pipelines/**",async route=>{
      const uri=new URL(route.request().url());
      const response=await route.fetch({url:isolated+uri.pathname.replace(/^\/t3/,"")+uri.search});
      await route.fulfill({response});
    });
  }
  let id=await open();
  await verify(id,"station_code","quality_status","public","water_level_archive");
  let form=await panel();
  await editCell(form,"匹配键字段",1,"输入匹配字段","SHOULD_NOT_SAVE");
  await form.getByRole("button",{name:/取消配置/}).click();
  assert.deepEqual(writes,[],"Cancel must not call native config/write");
  assert.deepEqual(await readFile(file),pristine,"Cancel modified native HPL disk");
  id=await open();
  await verify(id,"station_code","quality_status","public","water_level_archive");

  form=await panel();
  await editCell(form,"匹配键字段",1,"输入匹配字段","station_code_checked");
  await editCell(form,"更新字段映射",2,"输入数据流字段","quality_status_checked");
  await form.getByRole("textbox",{name:"InsertUpdate Schema"}).fill("research");
  await form.getByRole("textbox",{name:"InsertUpdate 目标表"}).fill("water_level_archive_checked");
  assert.equal(await form.locator(".config-native-list").count(),2);
  const configured=page.waitForResponse(r=>r.url().endsWith("/config/write") &&
    r.request().method()==="POST");
  await form.getByRole("button",{name:/保存配置/}).click();
  assert.equal((await configured).status(),200,"native InsertUpdate config/write");
  const saved=page.waitForResponse(r=>r.url().endsWith("/save") &&
    r.request().method()==="POST");
  await page.getByRole("button",{name:"保存（Save）"}).click();
  assert.equal((await saved).status(),200,"native HPL disk save");
  id=await open();
  await verify(id,"station_code_checked","quality_status_checked","research","water_level_archive_checked");
  const xml=await readFile(file,"utf8");
  for(const word of ["station_code_checked","quality_status_checked","water_level_archive_checked","<schema>research</schema>"]){
    assert.ok(xml.includes(word),"Hop XML lost "+word);
  }
  assert.deepEqual(writes,[200],"expected one native config write");
  assert.deepEqual(errors,[],"browser page errors");
  console.log("T3_FIXTURE_PATH="+file);
  console.log(JSON.stringify({pass:true,plugin:"InsertUpdate",nativeLists:2,
    cancelNoWrite:true,configWrite:200,diskSaveReopen:true,keys:2,values:2,errors}));
}finally{
  await browser.close();
  if(process.env.T3_KEEP_FIXTURE!=="1")await unlink(file).catch(()=>{});
}
