/** T5 read-only/nonexecuting external batch UX + native persistence acceptance. */
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { copyFile, readFile, unlink, writeFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";

const source=process.env.T5_NATIVE_BATCH_FIXTURES;
const workspace=process.env.T5_TEST_WORKSPACE??"/home/ubuntu/hmw-agent-lab/workspaces/t5";
const output=process.env.T5_NATIVE_BATCH_REPORT;
assert.ok(source,"missing temporary Hop Java producer folder");
const ids=["CheckSum","ConcatFields","DataGrid","GroupBy","Http","JsonInput","MergeJoin","Rest","SetVariable","StreamLookup"];
const base="http://127.0.0.1:4196/t5/";
const browser=await chromium.launch({headless:true,args:["--no-sandbox"]});
const page=await browser.newPage({viewport:{width:1440,height:920}});
const errors=[];
page.on("pageerror",e=>errors.push(e.message));
const results=[];
async function open(name) {
 await page.getByRole("button",{name:/打开流程/}).first().click();
 await page.getByRole("textbox",{name:/服务器流程路径/}).fill("/workspace/"+name);
 const pending=page.waitForResponse(r=>r.url().endsWith("/api/pipelines/open")&&r.request().method()==="POST");
 await page.getByRole("button",{name:/打开文件/}).first().click();
 const res=await pending;
 if(res.status()!==200)throw Error("open status "+res.status()+" "+(await res.text()).slice(0,180));
 const doc=await res.json();
 if(doc.nodes.length!==1)throw Error("expected one native node, found "+doc.nodes.length);
 return doc;
}
async function nativeRead(doc,id) {
 const r=await page.request.post(new URL("api/pipelines/"+doc.id+"/config/read",base).href,{
    data:{nodeId:"t5-"+id}});
 if(r.status()!==200)throw Error("native config/read "+r.status()+" "+(await r.text()).slice(0,160));
 return (await r.json()).config;
}
try{
 await page.goto(base,{waitUntil:"networkidle"});
 for(const pluginId of ids) {
   const filename="t5-batch-"+pluginId+"-"+randomUUID()+".hpl";
   const target=path.join(workspace,filename);
   const row={pluginId,opened:false,ui:false,fields:0,groupCount:0,
     cancelNoWrite:false,writeStatus:null,saveStatus:null,reopenEqual:false,errors:[]};
   const beforeErrors=errors.length;
   try{
     await copyFile(path.join(source,"native-batch-"+pluginId+".hpl"),target);
     const before=await readFile(target);
     let doc=await open(filename);
     row.opened=true;
     if(doc.nodes[0].pluginId!==pluginId)throw Error("wrong Hop plugin: "+doc.nodes[0].pluginId);
     const original=await nativeRead(doc,pluginId);
     await page.locator(".react-flow__node").first().dblclick();
     const form=page.locator("form.generic-config");await form.waitFor();
     row.ui=true;
     row.groupCount=await form.locator(".config-field-group").count();
     row.fields=await form.locator(".config-field-search small").count()?Number(
       (await form.locator(".config-field-search small").innerText()).match(/\/(?:\s*)(\d+)/)?.[1]??0):0;
     await form.getByRole("button",{name:/取消配置/}).click();
     row.cancelNoWrite=Buffer.compare(before,await readFile(target))===0;
     if(!row.cancelNoWrite)throw Error("cancel unexpectedly modified Hop disk");
     doc=await open(filename);
     await page.locator(".react-flow__node").first().dblclick();
     await form.waitFor();
     // REST is deliberately *not* specially adapted in React. Prove a real
     // business parameter edit on the same generated native form; never execute
     // or contact example.invalid (reserved non-resolving domain).
     const restUrl="https://example.invalid/hmw-native-rest-witness";
     if(pluginId==="Rest"){
       const urlInput=form.locator('label[data-config-key="url"] input');
       await urlInput.fill(restUrl);
       row.editedScalar="url";
       const secretTypes=await form.locator(
         'label[data-config-key="httpPassword"] input, label[data-config-key="trustStorePassword"] input'
       ).evaluateAll(inputs=>inputs.map(input=>input.type));
       row.secretInputsMasked=secretTypes.length===2&&secretTypes.every(type=>type==="password");
     }
     const nativeWrite=page.waitForResponse(r=>r.url().endsWith("/config/write")&&r.request().method()==="POST",{timeout:11000});
     await form.getByRole("button",{name:/保存配置/}).click();
     const response=await nativeWrite;
     row.writeStatus=response.status();
     if(row.writeStatus!==200)throw Error("native config/write status "+row.writeStatus+" "+(await response.text()).slice(0,140));
     const saved=page.waitForResponse(r=>r.url().endsWith("/save")&&r.request().method()==="POST",{timeout:11000});
     await page.getByRole("button",{name:"保存（Save）"}).click();
     const save=await saved;
     row.saveStatus=save.status();
     if(row.saveStatus!==200)throw Error("disk save status "+row.saveStatus);
     doc=await open(filename);
     const reopened=await nativeRead(doc,pluginId);
     const expected=pluginId==="Rest"?{...original,url:"https://example.invalid/hmw-native-rest-witness"}:original;
     row.reopenEqual=JSON.stringify(reopened)===JSON.stringify(expected);
     if(pluginId==="Rest")row.actualEditedUrl=reopened.url;
     if(!row.reopenEqual)throw Error("native decoded config differs after no-op UI save");
   }catch(e){row.issue=String(e.message??e).slice(0,350);}
   finally{
    row.errors=errors.slice(beforeErrors);
    results.push(row);
    console.log("T5_BATCH_RESULT="+JSON.stringify(row));
    await unlink(target).catch(()=>{});
    await page.goto(base,{waitUntil:"networkidle"}).catch(()=>{});
   }
 }
}finally{await browser.close();}
if(output)await writeFile(output,JSON.stringify(results,null,2));
const pass=results.filter(x=>x.opened&&x.ui&&x.cancelNoWrite&&x.writeStatus===200&&x.saveStatus===200&&x.reopenEqual&&x.errors.length===0);
console.log("T5_BATCH_UI_NATIVE_RESULTS attempted="+results.length+" complete="+pass.length+
 " degraded="+(results.length-pass.length));
if(pass.length!==ids.length)process.exitCode=1;
console.log("T5_BATCH_EXTERNAL_REGRESSION_EXECUTED");
