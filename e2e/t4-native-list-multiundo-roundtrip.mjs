/** Real browser proof: two consecutive native-list deletions undo in reverse order. */
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { readFile, copyFile, unlink } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),"..");
const base=process.env.T4_PREVIEW_URL??"http://127.0.0.1:4196/t4/";
const workspace=process.env.T4_TEST_WORKSPACE??"/home/ubuntu/hmw-agent-lab/workspaces/t4";
const browser=await chromium.launch({headless:true,args:["--no-sandbox"]});
const cases=[
  {plugin:"TableOutput",name:"water-level-archive",file:"tableoutput-native-waterlevel.hpl",
    initialRows:3,listLabel:"字段"},
  {plugin:"InsertUpdate",name:"upsert-water-level",file:"insertupdate-native-waterlevel.hpl",
    initialRows:2,listLabel:"匹配键字段"}
];
async function api(page,id,node){
  const response=await page.request.post(new URL("api/pipelines/"+id+"/config/read",base).href,
    {data:{nodeId:node}});
  assert.equal(response.status(),200,"native config/read");
  return (await response.json()).config;
}
async function open(page,basename){
  await page.getByRole("button",{name:/打开流程/}).first().click();
  await page.getByRole("textbox",{name:/服务器流程路径/}).fill("/workspace/"+basename);
  const opened=page.waitForResponse(r=>r.url().endsWith("/api/pipelines/open") &&
    r.request().method()==="POST");
  await page.getByRole("button",{name:/打开文件/}).first().click();
  const response=await opened;
  assert.equal(response.status(),200,"native open");
  return (await response.json()).id;
}
async function openPanel(page){
  await page.locator(".react-flow__node").first().dblclick();
  const form=page.locator("form.generic-config");
  await form.waitFor();
  return form;
}
async function cycleTwice(form,label,count) {
  const list=form.locator(".config-native-list").filter({
    has:form.locator("table[aria-label="+JSON.stringify(label+"原生字段列表")+"]")});
  assert.equal(await list.count(),1,"native list not found "+label);
  const rows=list.locator("table tbody tr");
  assert.equal(await rows.count(),count,"starting rows");
  const firstSnapshot=await rows.evaluateAll(els=>els.map(e=>Array.from(
    e.querySelectorAll("input")).map(input=>input.type==="checkbox"?input.checked:input.value)));
  for(let i=0;i<2;i++){
    await list.getByRole("button",{name:label+" 删除第1行"}).click();
    assert.equal(await rows.count(),count-i-1,"deletion must remove one row");
  }
  const undo=list.getByRole("button",{name:label+" 撤销上次删除"});
  assert.equal(await undo.count(),1,"LIFO undo must appear");
  for(let i=0;i<2;i++){
    await undo.click();
    assert.equal(await rows.count(),count-1+i,"undo must restore one exact row");
  }
  assert.equal(await undo.count(),0,"undo stack must empty after two restores");
  const restored=await rows.evaluateAll(els=>els.map(e=>Array.from(
    e.querySelectorAll("input")).map(input=>input.type==="checkbox"?input.checked:input.value)));
  assert.deepEqual(restored,firstSnapshot,"full native row order/values not restored");
}
try{
 for(const spec of cases){
  const basename="native-undo-"+randomUUID()+".hpl";
  const target=path.join(workspace,basename);
  const page=await browser.newPage({viewport:{width:1480,height:900}});
  const errors=[],writes=[];
  page.on("pageerror",e=>errors.push(e.message));
  page.on("response",r=>{if(r.url().endsWith("/config/write"))writes.push(r.status());});
  try{
    await copyFile(path.join(root,"e2e/fixtures",spec.file),target);
    const originalBytes=await readFile(target);
    await page.goto(base,{waitUntil:"networkidle"});
    let id=await open(page,basename);
    const original=await api(page,id,spec.name);
    let form=await openPanel(page);
    await cycleTwice(form,spec.listLabel,spec.initialRows);
    await form.getByRole("button",{name:/取消配置/}).click();
    assert.deepEqual(writes,[],"cancel after undo must never call config/write");
    assert.deepEqual(await readFile(target),originalBytes,"cancel after undo modified real .hpl");
    id=await open(page,basename);
    form=await openPanel(page);
    await cycleTwice(form,spec.listLabel,spec.initialRows);
    const configured=page.waitForResponse(r=>r.url().endsWith("/config/write") &&
      r.request().method()==="POST");
    await form.getByRole("button",{name:/保存配置/}).click();
    assert.equal((await configured).status(),200,"native config/write after undo");
    const saved=page.waitForResponse(r=>r.url().endsWith("/save") &&
      r.request().method()==="POST");
    await page.getByRole("button",{name:"保存（Save）"}).click();
    assert.equal((await saved).status(),200,"HPL save after undo");
    id=await open(page,basename);
    assert.deepEqual(await api(page,id,spec.name),original,
      "native Hop config changed despite undoing both deletions");
    assert.deepEqual(writes,[200],"one intentional Hop config write");
    assert.deepEqual(errors,[],"unexpected browser page error");
    console.log("T4_UNDO_SAVED_FILE="+target);
    console.log(JSON.stringify({pass:true,plugin:spec.plugin,originalRows:spec.initialRows,
      twoDeletes:true,twoUndos:true,cancelNoWrite:true,diskReopenNative:true,pageErrors:errors}));
  }finally{
    await page.close();
    if(process.env.T4_KEEP_FIXTURE!=="1")await unlink(target).catch(()=>{});
  }
 }
}finally{await browser.close();}
