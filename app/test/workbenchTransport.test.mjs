import { test } from 'node:test'
import assert from 'node:assert/strict'
import { workbenchFetch, enableWorkbenchApi } from '../src/workbenchTransport.ts'

test('non-pipeline standalone calls remain unchanged', async () => {
  const hits=[]
  globalThis.window={fetch:async(...args)=>{hits.push(args);return new Response('{}',{status:200})}}
  await workbenchFetch('/api/standalone/health',{method:'POST',body:'{}'})
  assert.equal(hits[0][0],'/api/standalone/health')
})

enableWorkbenchApi()
globalThis.document={cookie:'theme=dark; Admin-Token=valid-session-token'}

test('embedded Workbench opens through authenticated product API, never Hop Web',async()=>{
  const calls=[]
  globalThis.window={fetch:async(...args)=>{calls.push(args);return new Response('{}',{status:200,headers:{'content-type':'application/json'}})}}
  const r=await workbenchFetch('/api/pipelines/open',{method:'POST',body:JSON.stringify({path:'Demo/flows/demo.hpl'})})
  assert.equal(r.status,200)
  assert.equal(calls[0][0],'/api/workbench/modern-editor/open')
  assert.equal(calls[0][1].headers.Authorization,'Bearer valid-session-token')
  assert.deepEqual(JSON.parse(calls[0][1].body),{project:'Demo',file:'flows/demo.hpl'})
  assert.equal(calls[0][1].method,'POST')
  assert.equal(calls[0][1].credentials,'same-origin')
})

test('save uses Workbench authorization and revision, without forwarding untrusted headers',async()=>{
  const calls=[]
  globalThis.window={fetch:async(...args)=>{calls.push(args);return new Response('{}',{status:200,headers:{'content-type':'application/json'}})}}
  const resp=await workbenchFetch('/api/pipelines/id/save',{method:'POST',
    headers:{Authorization:'Bearer evil'},
    body:JSON.stringify({path:'Demo/file.hpl',expectedRevision:'a'.repeat(64),nodes:[],edges:[]})})
  assert.equal(resp.status,200)
  assert.equal(calls[0][0],'/api/workbench/modern-editor/save')
  assert.equal(calls[0][1].headers.Authorization,'Bearer valid-session-token')
  assert.equal(calls[0][1].headers['X-Hop-Workbench-Edit'],undefined)
  assert.equal(JSON.parse(calls[0][1].body).expectedRevision,'a'.repeat(64))
})

test('invalid project paths are rejected before network',async()=>{
  let count=0
  globalThis.window={fetch:async()=>{count++;throw new Error('must not fetch')}}
  for(const path of ['/etc/passwd.hpl','Demo/../secret.hpl','Demo/a.hwf','Demo/a\\\\bad.hpl','Demo//name.hpl']){
    const response=await workbenchFetch('/api/pipelines/open',{method:'POST',body:JSON.stringify({path})})
    assert.equal(response.status,400,path)
  }
  assert.equal(count,0)
})
test('unknown routes fail closed',async()=>{
  const r=await workbenchFetch('/api/pipelines/doc/config/write',{method:'POST',body:'{}'})
  assert.equal(r.status,501)
})
test('missing Workbench token rejects both open and save',async()=>{
  globalThis.document={cookie:'Other=abc'}
  const open=await workbenchFetch('/api/pipelines/open',{method:'POST',body:JSON.stringify({path:'Demo/a.hpl'})})
  assert.equal(open.status,401)
  const save=await workbenchFetch('/api/pipelines/abc/save',{method:'POST',
    body:JSON.stringify({path:'Demo/a.hpl',expectedRevision:'a'.repeat(64),nodes:[],edges:[]})})
  assert.equal(save.status,401)
  globalThis.document={cookie:'Admin-Token=valid-session-token'}
})
test('unexpected HTML 200 cannot be mistaken for saved JSON',async()=>{
  globalThis.window={fetch:async()=>new Response('<html>Login</html>',{status:200,headers:{'content-type':'text/html'}})}
  const response=await workbenchFetch('/api/pipelines/open',{method:'POST',body:JSON.stringify({path:'Demo/a.hpl'})})
  assert.equal(response.status,502)
})

test('RuoYi HTTP 200 / code 401 cannot masquerade as a graph',async()=>{
  globalThis.window={fetch:async()=>new Response(JSON.stringify({code:401,msg:'认证失败'}),{
    status:200,headers:{'content-type':'application/json;charset=utf-8'}
  })}
  const response=await workbenchFetch('/api/pipelines/open',{
    method:'POST',body:JSON.stringify({path:'Demo/example.hpl'})
  })
  assert.equal(response.status,401)
  assert.deepEqual(await response.json(),{code:'workbench_login_required'})
})
test('RuoYi permission denial remains a permission failure',async()=>{
  globalThis.window={fetch:async()=>new Response(JSON.stringify({code:500,msg:'没有权限'}),{
    status:200,headers:{'content-type':'application/json;charset=utf-8'}
  })}
  const response=await workbenchFetch('/api/pipelines/demo/save',{
    method:'POST',body:JSON.stringify({path:'Demo/example.hpl',expectedRevision:'a'.repeat(64),nodes:[],edges:[]})
  })
  assert.equal(response.status,403)
})