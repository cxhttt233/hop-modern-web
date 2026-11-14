// Native Apache Hop Workflow Action deletion: browser/UI + real .hwf save/reopen.
// For an unmerged Java PR, use with-isolated-java-branch; otherwise use shared T2.
import { chromium } from "playwright";
import { copyFile, unlink } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";

const root = "/home/ubuntu/hmw-agent-lab";
const base = process.env.T2_PREVIEW_URL ?? "http://127.0.0.1:4196/t2/";
const isolated = process.env.HMW_ISOLATED_URL;
const workspace = process.env.HMW_ISOLATED_WORKSPACE ?? process.env.T2_TEST_WORKSPACE ?? path.join(root,"workspaces/t2");
const basename = "action-delete-" + randomUUID() + ".hwf";
const filename = path.join(workspace,basename);
const documentPath = isolated ? filename : "/workspace/" + basename;
const apiBase = isolated ? isolated + "/api/workflows" : new URL("api/workflows/",base).href.replace(/\/$/,"");
const browser = await chromium.launch({headless:true});
const page = await browser.newPage({viewport:{width:1440,height:900}});
const errors=[];
page.on("pageerror",err=>errors.push(err.message));
page.on("dialog",async d=>{if(d.type()!=="confirm")throw Error("Unexpected modal "+d.type());await d.accept()});
const assert=(condition,message)=>{if(!condition)throw Error(message)};
const post=async(name,data)=>page.request.post(apiBase+"/"+name,{data});
try {
 await copyFile(path.join(root,"workspaces/t2/connected-actions.hwf"),filename);
 const initial=await post("open",{path:documentPath});
 assert(initial.ok(),"original Hop .hwf open HTTP "+initial.status());
 const original=await initial.json();
 assert(original.actions.length===2 && original.hops.length===1,"expected connected two-node fixture");
 const collision=await post("action-delete",{id:original.id,name:"Start",connectedHops:0});
 assert(collision.status()===409,"stale incident-hop count should be HTTP 409");
 if(isolated){
   await page.route("**/t2/api/workflows/**",async route=>{
     const operation=route.request().url().split("/api/workflows/")[1];
     const response=await route.fetch({url:apiBase+"/"+operation});
     await route.fulfill({response});
   });
 }
 await page.goto(base,{waitUntil:"networkidle"});
 await page.getByRole("button",{name:"打开 Workflow（.hwf）"}).click();
 const dialog=page.getByRole("dialog",{name:"Workflow 文件编辑"});
 await dialog.getByLabel("服务器上的 .hwf 路径").fill(documentPath);
 await dialog.getByRole("button",{name:"打开",exact:true}).click();
 await dialog.getByText("工作流节点 · 2").waitFor();
 await dialog.getByRole("button",{name:"删除节点 Start"}).click();
 await dialog.getByText("工作流节点 · 1").waitFor();
 const chain=await dialog.getByRole("region",{name:"工作流执行链路"}).innerText();
 assert(chain.includes("执行链路 · 0 条"),"incident hop still visible "+chain);
 await dialog.getByRole("status").getByText("已应用，尚未保存到磁盘").waitFor();
 await dialog.getByRole("button",{name:"保存 .hwf"}).click();
 await dialog.getByRole("status").getByText("已保存").waitFor();
 await dialog.getByRole("button",{name:"打开",exact:true}).click();
 await dialog.getByText("工作流节点 · 1").waitFor();
 const reopen=await post("open",{path:documentPath});
 assert(reopen.ok(),"reopen Hop .hwf HTTP "+reopen.status());
 const latest=await reopen.json();
 assert(latest.actions.length===1 && latest.actions[0].name==="Second start" && latest.hops.length===0,
  "native .hwf action/hops roundtrip failed: "+JSON.stringify(latest));
 assert(errors.length===0,"browser errors "+errors.join("; "));
 console.log(JSON.stringify({pass:true,staleHops:409,remaining:"Second start",hops:0,nativeDiskRoundtrip:true,errors}));
} finally {
 await browser.close();
 await unlink(filename).catch(()=>{});
}
