import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const element=readFileSync(new URL('../src/WorkbenchElement.tsx',import.meta.url),'utf8')
const graph=readFileSync(new URL('../src/editor/graphProvider.ts',import.meta.url),'utf8')
const app=readFileSync(new URL('../src/App.tsx',import.meta.url),'utf8')

test('Workbench save contract includes SHA revision and reports external edit conflict',()=>{
  assert.match(graph,/expectedRevision: document\.revision/)
  assert.match(graph,/response\.status === 409/)
  assert.match(graph,/response\.status === 403/)
  assert.match(graph,/saveWorkbenchGraph\([\s\S]*?path: string, document: HopGraphDocument/)
})
test('embedded custom element connects Workbench save button to the same graph document',()=>{
  assert.match(element,/save\(\): void \{ this\.saveHandler\?\.\(\); \}/)
  assert.match(element,/onRegisterSave=/)
  assert.match(element,/workbench-modern-save-result/)
  assert.match(app,/saveWorkbenchGraph\(pipelinePath, documentRef\.current, configEditsRef\.current\)/)
  assert.match(app,/onRegisterSave\?\.\(saveDocument\)/)
  assert.match(app,/onSaveResult\?\.\(\{ ok: true/)
  assert.match(app,/onSaveResult\?\.\(\{ ok: false/)
})

test('Workbench config is an actual editor panel, not a rename-only proxy',()=>{
  assert.match(app,/readWorkbenchTransformConfig\(pipelinePath, documentRef\.current, nodeId\)/)
  assert.match(app,/configEditsRef\.current\[nodeId\]/)
  assert.match(app,/setDirty\(true\)/)
  const node=readFileSync(new URL('../src/editor/TransformNode.tsx',import.meta.url),'utf8')
  assert.match(node,/>配置<\/button>/)
  assert.match(node,/>重命名<\/button>/)
})