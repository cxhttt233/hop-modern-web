import assert from 'node:assert/strict';
import { authorizeNativeMutation, mintNativeCsrf } from './native-issuer-contract.mjs';
const secret=Buffer.alloc(32,17);
const principal={ authenticated:true,source:'trusted-issuer',subject:'alice',slot:'t1' };
const slot='t1', sessionId='session_A', allowedOrigin='https://hmw.example.test';
const csrf=mintNativeCsrf({verifiedPrincipal:principal,slot,sessionId,csrfSecret:secret});
const request={method:'PUT',origin:allowedOrigin,fetchSite:'same-origin',csrf};
const args={verifiedPrincipal:principal,request,slot,sessionId,csrfSecret:secret,allowedOrigin};
assert.equal(authorizeNativeMutation(args).subject,'alice');
const cases=[
 {verifiedPrincipal:undefined},{verifiedPrincipal:{...principal,authenticated:false}},
 {verifiedPrincipal:{...principal,source:'x-forwarded-user'}},
 {verifiedPrincipal:{...principal,subject:'../alice'}},
 {verifiedPrincipal:{...principal,slot:'t2'}},
 {slot:'t2'},{sessionId:'session_B'},{csrfSecret:Buffer.alloc(32,18)},
 {request:{...request,csrf:'0'.repeat(64)}},
 {request:{...request,origin:'https://evil.example'}},
 {request:{...request,fetchSite:'cross-site'}},
 {request:{...request,method:'GET'}},
 {request:{...request,method:'PUT',csrf:undefined}},
 {allowedOrigin:'http://hmw.example.test'},
 {csrfSecret:Buffer.alloc(2)},
];
for(const changed of cases) assert.throws(()=>authorizeNativeMutation({...args,...changed}),e=>e.status===403);
assert.throws(()=>mintNativeCsrf({verifiedPrincipal:{...principal,source:'header'},slot,sessionId,csrfSecret:secret}),e=>e.status===403);
console.log('NATIVE_ISSUER_CONTRACT_PASS 17 assertions');