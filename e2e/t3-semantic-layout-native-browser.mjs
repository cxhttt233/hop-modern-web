/** Actual browser: T0 semantic groups are visible without per-Transform UI code. */
import assert from "node:assert/strict";
import {chromium} from "playwright";
import {copyFile,readFile,unlink} from "node:fs/promises";
import {randomUUID} from "node:crypto";
import path from "node:path";

const workspace=process.env.T3_TEST_WORKSPACE??"/home/ubuntu/hmw-agent-lab/workspaces/t3";
const base=process.env.T3_PREVIEW_URL??"http://127.0.0.1:4196/t3/";
const name="semantic-layout-"+randomUUID()+".hpl";
const file=path.join(workspace,name);
const browser=await chromium.launch({headless:true,args:["--no-sandbox"]});
const page=await browser.newPage({viewport:{width:1440,height:920}});
const errors=[];
page.on("pageerror",e=>errors.push(e.message));
try {
  await copyFile(path.join(workspace,"table-input.hpl"),file);
  const original=await readFile(file);
  await page.goto(base,{waitUntil:"networkidle"});
  await page.getByRole("button",{name:/打开流程/}).first().click();
  await page.getByRole("textbox",{name:/服务器流程路径/}).fill("/workspace/"+name);
  const opened=page.waitForResponse(r=>r.url().endsWith("/api/pipelines/open")
    &&r.request().method()==="POST");
  await page.getByRole("button",{name:/打开文件/}).first().click();
  const response=await opened;
  assert.equal(response.status(),200,"native TableInput open HTTP");
  const doc=await response.json();
  assert.equal(doc.nodes.length,1);
  assert.equal(doc.nodes[0].pluginId,"TableInput");
  await page.locator(".react-flow__node").first().dblclick();
  const form=page.locator("form.generic-config");
  await form.waitFor();
  const text=await form.innerText();
  assert.ok(text.includes("SQL 查询"),"no semantic SQL group header");
  assert.ok(text.includes("数据库连接"),"no semantic Connection group header");
  assert.ok(text.includes("专业 SQL 编辑器"),"no generic SQL editor workbench");
  assert.ok(text.includes("保存"),"no native save action");
  await form.getByRole("button",{name:/取消配置/}).click();
  assert.deepEqual(await readFile(file),original,"cancel changed native TableInput disk bytes");
  assert.deepEqual(errors,[],"browser page errors");
  console.log(JSON.stringify({pass:true,plugin:"TableInput",semanticSQL:true,
    semanticConnection:true,genericSQLEditor:true,cancelNoWrite:true,errors}));
  console.log("T3_T0_SEMANTIC_LAYOUT_REAL_BROWSER_PASS");
}finally{
 await browser.close();
 await unlink(file).catch(()=>{});
}
