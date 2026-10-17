import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const app = readFileSync(new URL('../src/App.tsx', import.meta.url),'utf8')
const css = readFileSync(new URL('../src/styles.css', import.meta.url),'utf8')

test('workbench canvas shows no redundant top-right draft banner or export toolbar', () => {
  assert.doesNotMatch(app,/embedded-draft-toolbar|draft-status|本地草稿 · 未写回 Hop|导出草稿/)
  assert.doesNotMatch(css,/\.embedded-draft-toolbar/)
})
test('empty pipeline retains only useful prompt without the bottom draft disclaimer', () => {
  assert.match(app,/此流程暂无转换节点/)
  assert.match(app,/从左侧组件库拖入转换，开始设计流程/)
  assert.doesNotMatch(app,/目前改动保存在当前页面内|可导出 JSON 草稿留存/)
})
test('editing capabilities and keyboard undo redo remain available', () => {
  assert.match(app,/onDrop=\{onCanvasDrop\}/)
  assert.match(app,/onConnect=\{connectNodes\}/)
  assert.match(app,/deleteDraftTransform\(doc, nodeId\)/)
  assert.match(app,/onKeyDownCapture=/)
  assert.match(app,/travelHistory\(key === "y" \|\| event\.shiftKey \? "redo" : "undo"\)/)
})