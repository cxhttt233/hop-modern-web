import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const app=readFileSync(new URL('../src/App.tsx',import.meta.url),'utf8')
const style=readFileSync(new URL('../src/styles.css',import.meta.url),'utf8')
const node=readFileSync(new URL('../src/editor/TransformNode.tsx',import.meta.url),'utf8')

test('initial React Flow fit never magnifies small diagrams beyond native size',()=>{
  assert.match(app,/fitViewOptions=\{\{ maxZoom: 1, padding: 0\.24 \}\}/)
  assert.match(app,/maxZoom=\{1\.5\}/)
  assert.match(style,/\.react-flow__node\.hop-transform-node\s*\{\s*width: 158px;\s*min-height: 46px;/)
  assert.match(style,/\.react-flow__handle\s*\{\s*width: 11px;/)
})

test('embedded sidebar matches actual draggable draft capabilities and avoids duplicate count',()=>{
  assert.match(app,/<small>拖拽组件到画布<\/small>/)
  assert.doesNotMatch(app,/当前为查看模式|查看组件/)
  assert.match(app,/!embedded && !isEmptyEmbeddedGraph && <div className="canvas-chrome"/)
  assert.doesNotMatch(app,/导出草稿|本地草稿 · 未写回 Hop/)
})

test('embedded configure opens real parameter form and preserves independent rename',()=>{
  assert.match(app,/renameDraftTransform\(doc, draftRenameId, draftRenameText\)/)
  assert.match(app,/className="draft-rename-panel"/)
  assert.match(node,/>配置<\/button>/)
  assert.match(node,/>重命名<\/button>/)
  assert.match(app,/readWorkbenchTransformConfig\(pipelinePath, documentRef\.current, nodeId\)/)
  assert.doesNotMatch(app,/节点配置和写回 \.hpl 尚未接入后端。当前支持/)
  assert.match(app,/onClick=\{\(\) => setConfigError\(undefined\)\}/)
  assert.match(style,/\.preview-note\.error-note\{\s*top:auto;bottom:22px;/)
})