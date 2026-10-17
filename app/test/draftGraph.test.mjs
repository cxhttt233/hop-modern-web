import { test } from 'node:test'
import assert from 'node:assert/strict'
const { addDraftTransform, moveDraftTransform, connectDraftTransforms,
  deleteDraftTransform, renameDraftTransform, validateDraftGraph } = await import('../src/editor/draftGraph.ts')

const blank = () => ({id:'default/filename.hpl',name:'新建 Pipeline',
  revision:'server-rev-1',nodes:[],edges:[]})
const a = { id:'a',kind:'transform',pluginId:'TableInput',name:'表输入',x:10,y:20 }
const b = { id:'b',kind:'transform',pluginId:'TableOutput',name:'表输出',x:200,y:20 }

test('empty .hpl can take drag-added transforms without changing source object', () => {
  const original = blank()
  const created = addDraftTransform(original,a)
  assert.equal(original.nodes.length,0)
  assert.equal(created.nodes.length,1)
  assert.equal(created.nodes[0].pluginId,'TableInput')
  assert.equal(created.revision,'server-rev-1')
  assert.strictEqual(addDraftTransform(created,a),created)
})
test('nodes can move and connect in a local draft', () => {
  let doc = addDraftTransform(addDraftTransform(blank(),a),b)
  doc = moveDraftTransform(doc,'a',42,76)
  assert.deepEqual([doc.nodes[0].x,doc.nodes[0].y],[42,76])
  doc = connectDraftTransforms(doc,'a','b')
  assert.equal(doc.edges.length,1)
  assert.deepEqual([doc.edges[0].source,doc.edges[0].target],['a','b'])
  assert.strictEqual(connectDraftTransforms(doc,'a','b'),doc)
  assert.strictEqual(connectDraftTransforms(doc,'b','b'),doc)
  assert.ok(validateDraftGraph(doc))
})
test('delete removes node and incident connections but does not affect original', () => {
  const original = addDraftTransform(addDraftTransform(blank(),a),b)
  const connected = connectDraftTransforms(original,'a','b')
  const deleted = deleteDraftTransform(connected,'a')
  assert.deepEqual(deleted.nodes.map(n=>n.id),['b'])
  assert.equal(deleted.edges.length,0)
  assert.equal(connected.edges.length,1)
  assert.strictEqual(deleteDraftTransform(deleted,'missing'),deleted)
})
test('draft rejects invalid positions, references and duplicate IDs', () => {
  const doc = addDraftTransform(blank(),a)
  assert.strictEqual(addDraftTransform(doc,{...b,x:Number.NaN}),doc)
  assert.strictEqual(connectDraftTransforms(doc,'a','missing'),doc)
  assert.strictEqual(moveDraftTransform(doc,'a',Infinity,0),doc)
  assert.equal(validateDraftGraph({ ...doc, nodes:[a,a] }),false)
  assert.equal(validateDraftGraph({ ...doc, edges:[{id:'bad',source:'a',target:'missing',enabled:true}] }),false)
})

test('rename changes only draft node name; rejects duplicates and empty names', () => {
  const doc = addDraftTransform(addDraftTransform(blank(),a),b)
  const modified = renameDraftTransform(doc,'a','表输入（源数据）')
  assert.equal(modified.nodes[0].name,'表输入（源数据）')
  assert.equal(doc.nodes[0].name,'表输入')
  assert.equal(modified.nodes[0].pluginId,'TableInput')
  assert.strictEqual(renameDraftTransform(doc,'a','表输出'),doc)
  assert.strictEqual(renameDraftTransform(doc,'a','  '),doc)
  assert.strictEqual(renameDraftTransform(doc,'absent','new name'),doc)
})