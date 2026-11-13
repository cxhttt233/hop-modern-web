/** Run on preview host: HMW_T3_WORKSPACE=/home/ubuntu/hmw-agent-lab/workspaces/t3 node e2e/select-values-browser-roundtrip.mjs */
import assert from 'node:assert/strict';
import { copyFile, unlink } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { chromium } from 'playwright';

const here = dirname(fileURLToPath(import.meta.url));
const workspace = process.env.HMW_T3_WORKSPACE;
if (!workspace) throw Error('Set HMW_T3_WORKSPACE to the isolated T3 API workspace directory');
const filename = `t3-select-values-roundtrip-${process.pid}.hpl`;
const target = join(workspace, filename);
const baseUrl = process.env.HMW_T3_URL ?? 'http://127.0.0.1:4196/t3/';
await copyFile(join(here, 'fixtures/v9-select-values-proof.hpl'), target);
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const response = await page.goto(baseUrl, { waitUntil: 'networkidle' });
  assert.equal(response.status(), 200);
  await page.getByRole('button', { name: '打开流程（Choose pipeline）' }).click();
  await page.getByRole('textbox', { name: '服务器流程路径（Server-visible pipeline path）' }).fill(`/workspace/${filename}`);
  await page.getByRole('button', { name: '打开文件（Open pipeline）' }).click();
  await page.locator('.react-flow__node').first().dblclick();
  const json = page.locator('textarea').first();
  // Current server descriptor falls back to JSON; test the real supported editor.
  assert.match(await json.inputValue(), /select_unspecified/);
  await json.fill('{"remove":[],"meta":[],"field":[],"select_unspecified":true}');
  await page.getByRole('button', { name: '保存配置（Save）' }).click();
  await page.getByRole('button', { name: '保存（Save）' }).click();
  await page.getByRole('button', { name: '重新打开当前流程（Reload pipeline）' }).click();
  await page.locator('.react-flow__node').first().dblclick();
  assert.equal(JSON.parse(await page.locator('textarea').first().inputValue()).select_unspecified, true);
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({ result: 'PASS', file: filename, operation: 'edit config -> save .hpl -> reload', editor: 'JSON fallback' }));
} finally {
  await browser.close();
  await unlink(target);
}
