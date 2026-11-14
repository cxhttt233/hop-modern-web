import { chromium } from '/home/ubuntu/hmw-agent-lab/source/node_modules/playwright/index.mjs';
import { randomUUID } from 'node:crypto';
import { basename, join } from 'node:path';
import { unlink } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
const workspace=process.env.HMW_ISOLATED_WORKSPACE, backend=process.env.HMW_ISOLATED_URL;
if(!workspace||!backend) throw Error('Use with-isolated-java-branch');
const file=join(workspace,`t2-${randomUUID()}.hwf`);
const runtime='/home/ubuntu/hop-modern-preview/runtime/server';
const browser=await chromium.launch({headless:true});
try {
 execFileSync('java',['-cp',`${runtime}/classes:${runtime}/lib/*`,'/home/ubuntu/hmw-agent-lab/CreateWorkflowFixture.java',file]);
 const page=await browser.newPage(); const errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/workflows/**',async route=>{const path=new URL(route.request().url()).pathname;await route.fulfill({response:await route.fetch({url:backend+path})});});
 await page.goto('http://127.0.0.1:4196/t2/',{waitUntil:'networkidle'});
 await page.getByRole('button',{name:/打开 Workflow/}).click();
 await page.getByLabel('服务器上的 .hwf 路径').fill(basename(file));
 await page.getByRole('button',{name:'打开',exact:true}).click();
 const form=page.locator('section[aria-label="新增原生工作流节点"] form');
 for(const [name,x] of [['First',100],['Second',320]]) {await form.locator('[name=name]').fill(name);await form.locator('[name=x]').fill(String(x));await form.getByRole('button',{name:'新增节点'}).click();await page.getByText(`工作流节点 · ${name==='First'?1:2}`).waitFor();}
 const hop=page.locator('form[aria-label="创建工作流连接"]');
 await hop.locator('[name=from]').selectOption('First');await hop.locator('[name=to]').selectOption('Second');await hop.getByRole('button',{name:'创建连接'}).click();
 await page.getByText('执行链路 · 1 条').waitFor();
 await page.getByRole('button',{name:/保存.*hwf/}).click();
 await page.getByRole('button',{name:'打开',exact:true}).click();
 await page.getByText('工作流节点 · 2').waitFor();await page.getByText('执行链路 · 1 条').waitFor();
 if(errors.length) throw Error(errors.join(';'));console.log('BROWSER_NATIVE_ACTION_ROUNDTRIP_OK');
} finally {await browser.close();await unlink(file).catch(()=>{});}