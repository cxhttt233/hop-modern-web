#!/usr/bin/env node
// Read-only runtime probe. Never grants access or reads service credentials.
import { execFileSync } from 'node:child_process';
import http from 'node:http';
const url = process.env.HMW_PREVIEW_URL || 'http://127.0.0.1:4196/t1/api/native-connections';
const nginx = process.env.HMW_NGINX_CONFIG_FILE
  ? (await import('node:fs')).readFileSync(process.env.HMW_NGINX_CONFIG_FILE, 'utf8')
  : execFileSync(process.env.HMW_NGINX_BIN || 'sudo', process.env.HMW_NGINX_BIN ? ['-T'] : ['-n','/usr/sbin/nginx','-T'], { encoding: 'utf8', stdio: ['ignore','pipe','ignore'], timeout: 5000 });
// A proxy-authenticated principal requires a verified upstream auth mechanism;
// auth_basic alone does not establish a principal contract for the bridge.
const hasAuth = /\bauth_request\s+\S+\s*;/.test(nginx) || /\bauth_basic\s+(?!off\b)[^;]+;/.test(nginx);
const request = (headers = {}) => new Promise((resolve,reject) => {
  const req = http.get(url, { headers, timeout: 5000 }, res => { res.resume(); res.on('end',()=>resolve(res.statusCode)); });
  req.on('timeout',()=>req.destroy(new Error('timeout'))); req.on('error',reject);
});
const probes = [
  {}, { 'x-authenticated-user':'forged-user', 'x-forwarded-user':'forged-user' },
  { 'x-hmw-principal':'forged-user', 'x-hmw-slot':'t1', 'x-forwarded-proto':'https' }
];
try {
  const statuses = await Promise.all(probes.map(request));
  const denied = statuses.every(s => [401,403,404,503].includes(s));
  console.log(JSON.stringify({ nginxAuthConfigured: hasAuth, forgedHeaderStatuses: statuses,
    routeFailClosed: denied, trustedIdentityAvailable: false }));
  if (!denied || !hasAuth) process.exitCode = 1;
  // Deliberately never returns readiness=true: upstream principal verification
  // and per-user workspace mapping require separate end-to-end proof.
} catch (e) { console.error('NATIVE_AUTH_INGRESS_PROBE_FAILED:'+e.message); process.exitCode = 1; }
