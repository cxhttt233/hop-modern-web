/** T3 semantic/native group names must render as one professional field section. */
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { randomUUID } from "node:crypto";
import { copyFile, readFile, unlink } from "node:fs/promises";
import path from "node:path";
const workspace=process.env.T3_TEST_WORKSPACE??"/home/ubuntu/hmw-agent-lab/workspaces/t3";
const base=process.env.T3_PREVIEW_URL??"http://127.0.0.1:4196/t3/";
const filename="t3-native-group-"+randomUUID()+".hpl";
const target=path.join(workspace,filename);
const browser=await chromium.launch({headless:true,args:["--no-sandbox"]});
const page=await browser.newPage({viewport:{width:1400,height:930}});
const errors=[];
page.on("pageerror",e=>errors.push(e.message));
try {
  await copyFile(path.join(workspace,"table-input.hpl"),target);
  const untouched=await readFile(target);
  await page.goto(base,{waitUntil:"networkidle"});
  await page.getByRole("button",{name:/打开流程/}).first().click();
  await page.getByRole("textbox",{name:/服务器流程路径/}).fill("/workspace/"+filename);
  const opened=page.waitForResponse(r=>r.url().endsWith("/api/pipelines/open")&&r.request().method()==="POST");
  await page.getByRole("button",{name:/打开文件/}).first().click();
  assert.equal((await opened).status(),200,"native TableInput open");
  await page.locator(".react-flow__node").first().dblclick();
  const form=page.locator("form.generic-config");
  await form.waitFor();
  const titles=await form.locator(".config-field-group legend button span").allTextContents();
  const groups=await form.locator(".config-field-group legend button").allTextContents();
  const normalized=groups.map(s=>s.replace(/[▾▸]/g,"").replace(/\\d+ 项.*/,"").trim());
  assert.equal(normalized.filter(v=>v==="字段配置").length,1,
    "native fields + semantic Fields must be one Chinese group");
  assert.ok(!normalized.includes("fields"),"raw native field group leaked into user UI");
  assert.ok(normalized.includes("SQL 查询")&&normalized.includes("数据库连接"));
  const fields=form.locator(".config-field-group").filter({has:form.locator("legend button")});
  const group=fields.filter({has:form.getByRole("button",{name:/字段配置配置分组/})});
  assert.equal(await group.count(),1,"one field group expected");
  assert.ok((await group.innerText()).includes("3 项"),"group must include inferred and native fields");
  assert.deepEqual(errors,[]);
  await form.getByRole("button",{name:/取消配置/}).click();
  assert.deepEqual(await readFile(target),untouched,"cancel must never mutate Hop disk");
  console.log(JSON.stringify({pass:true,plugin:"TableInput",groups:normalized,mergedFieldCount:3,
    nativeCaseVariantsUnified:true,cancelNoWrite:true,errors}));
  console.log("T3_NATIVE_AND_SEMANTIC_GROUP_REAL_BROWSER_PASS");
} finally {
  await browser.close();
  await unlink(target).catch(()=>{});
}
