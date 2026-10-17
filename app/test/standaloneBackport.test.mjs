import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const app = readFileSync(new URL("../src/App.tsx",import.meta.url),"utf8");
const node = readFileSync(new URL("../src/editor/TransformNode.tsx",import.meta.url),"utf8");
const catalog = readFileSync(new URL("../src/transformCatalog.ts",import.meta.url),"utf8");
test("standalone palette has searchable Chinese categories and true drag/drop",()=>{
  assert.match(app,/CATEGORY_ORDER\.map/);
  assert.match(app,/P0_TRANSFORMS\.filter/);
  assert.match(app,/dataTransfer\.setData\("application\/x-hop-transform"/);
  assert.match(app,/screenToFlowPosition/);
  assert.match(app,/addPipelineTransform\(document\.id/);
  assert.match(catalog,/JSON 输入/);
  assert.match(catalog,/HTTP 客户端/);
});
test("custom nodes keep real React Flow handles and config, deletion controls",()=>{
  assert.match(node,/Handle/);
  assert.match(node,/actions\?\.configure\(id\)/);
  assert.match(node,/actions\?\.remove\(id\)/);
  assert.match(app,/onNodeDoubleClick/);
});
test("standalone does not acquire any Workbench bridge or write implementation",()=>{
  assert.doesNotMatch(app,/workbenchTransport|workbenchFetch|workbench:hop:edit|\/hop-ui/);
  assert.match(app,/savePipeline\(document\.id\)/);
  assert.match(app,/readPipelineTransformConfig\(document\.id/);
});
test("read failure does not present fake example nodes as an actual file",()=>{
  assert.match(app,/setDocument\(\{ id: "", name: "", revision: "", nodes: \[\], edges: \[\] \}\)/);
  assert.match(app,/流程打开失败/);
});
