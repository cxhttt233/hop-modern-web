import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')
const style = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')
const element = readFileSync(new URL('../src/WorkbenchElement.tsx', import.meta.url), 'utf8')

test('embedded editor does not duplicate the Workbench file toolbar', () => {
  assert.match(app, /\{!embedded && <header className="topbar">/)
  assert.match(app, /<main className=\{embedded \? "shell embedded-shell" : "shell"\} onKeyDownCapture=/)
  assert.match(style, /\.shell\.embedded-shell\{height:100%;grid-template-rows:minmax\(0,1fr\)\}/)
})
test('empty graph is an explicit success state with no misleading error banner', () => {
  assert.match(app, /const isEmptyEmbeddedGraph = embedded && isRealGraph && document\.nodes\.length === 0/)
  assert.match(app, /className="empty-graph-state" role="status"/)
  assert.match(app, /此流程暂无转换节点/)
  assert.match(app, /从左侧组件库拖入转换，开始设计流程/)
  assert.doesNotMatch(app, /embedded-readonly-banner/)
  assert.doesNotMatch(element, /embedded-readonly-banner/)
})
test('empty pipeline retains interactive component palette and canvas', () => {
  assert.ok(app.includes('isRealGraph && (\n          <aside className="transform-palette"'))
  assert.ok(app.includes('const canEdit = isRealGraph;'))
  assert.ok(style.includes('.workspace.workspace-empty-graph{grid-template-columns:248px minmax(0,1fr)}'))
  assert.ok(style.includes('.workspace.workspace-empty-graph > .react-flow{grid-column:2}'))
})
test('embedded canvas permits local drag, connect, and removal without draft chrome', () => {
  for(const term of ['addDraftTransform(doc,', 'moveDraftTransform(doc,',
       'connectDraftTransforms(doc,', 'deleteDraftTransform(doc,',
       'onDrop={onCanvasDrop}']) {
    assert.ok(app.includes(term), 'missing '+term)
  }
  assert.doesNotMatch(app, /embedded-readonly-indicator|embedded-draft-toolbar/)
  assert.doesNotMatch(app, /目前改动保存在当前页面内|本地草稿 · 未写回 Hop|导出草稿/)
  assert.ok(!element.includes('embedded-readonly-banner'))
})