import { chromium } from 'playwright';

// Live browser proof against an isolated Hop test workspace. The fixture is modified only
// when T3_ALLOW_DISK_SAVE=1, so the default run never overwrites a shared .hpl.
const base = process.env.T3_URL ?? 'http://127.0.0.1:4196/t3/';
const fixture = process.env.T3_HPL ?? '/workspace/select-values.hpl';
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = [];
page.on('pageerror', error => errors.push(error.message));
const post = (response, suffix) => response.request().method() === 'POST' &&
  new URL(response.url()).pathname.endsWith(suffix);
try {
  await page.goto(base, { waitUntil: 'networkidle' });
  async function openPipeline() {
    await page.getByRole('button', { name: /打开流程/ }).first().click();
    await page.getByRole('textbox', { name: /服务器流程路径/ }).fill(fixture);
    const pending = page.waitForResponse(r => post(r, '/api/pipelines/open'));
    await page.getByRole('button', { name: /打开文件/ }).first().click();
    if (!(await pending).ok()) throw Error('Pipeline open failed');
  }
  async function openConfig() {
    await page.locator('.react-flow__node').first().dblclick();
    const panel = page.locator('form.generic-config');
    await panel.waitFor();
    return panel;
  }
  await openPipeline();
  let panel = await openConfig();
  const read = await page.request.get(base);
  if (!read.ok()) throw Error('T3 preview not reachable');
  const sections = ['选取与重命名字段', '移除字段', '转换字段元数据'];
  for (const title of sections) await panel.getByRole('region', { name: title }).getByRole('table').waitFor();
  const selected = panel.getByRole('region', { name: sections[0] });
  const removed = panel.getByRole('region', { name: sections[1] });
  const metadata = panel.getByRole('region', { name: sections[2] });
  await selected.getByRole('button', { name: '添加字段行' }).click();
  await selected.getByRole('textbox', { name: /第1行 字段名称/ }).fill('T3_roundtrip_field');
  await removed.getByRole('button', { name: '添加字段行' }).click();
  await removed.getByRole('textbox', { name: /第1行 字段名称/ }).fill('T3_discard');
  await metadata.getByRole('button', { name: '添加字段行' }).click();
  await metadata.getByRole('textbox', { name: /第1行 字段名称/ }).fill('T3_roundtrip_field');
  const saveConfig = page.waitForResponse(r => post(r, '/config/write'));
  await panel.getByRole('button', { name: /保存配置/ }).click();
  const response = await saveConfig;
  if (!response.ok()) throw Error('Config write HTTP ' + response.status());
  const config = (await response.json()).config;
  if (config.fields?.field?.[0]?.name !== 'T3_roundtrip_field' ||
      config.fields?.remove?.[0]?.name !== 'T3_discard' ||
      config.fields?.meta?.[0]?.name !== 'T3_roundtrip_field') throw Error('Three-list write mismatch');
  let diskVerified = false;
  if (process.env.T3_ALLOW_DISK_SAVE === '1') {
    const saveDisk = page.waitForResponse(r => post(r, '/save'));
    await page.getByRole('button', { name: /^保存$/ }).click();
    if (!(await saveDisk).ok()) throw Error('Disk save failed');
    await openPipeline();
    panel = await openConfig();
    for (const title of sections) await panel.getByRole('region', { name: title }).getByRole('table').waitFor();
    const text = await panel.innerText();
    if (!text.includes('T3_roundtrip_field') || !text.includes('T3_discard')) throw Error('Reopened .hpl missing edits');
    diskVerified = true;
  }
  if (errors.length) throw Error('Browser errors: ' + errors.join('; '));
  console.log(JSON.stringify({ result: 'PASS', sections, configWrite: response.status(), diskVerified }));
} finally { await browser.close(); }
