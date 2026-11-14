import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
const root = mkdtempSync(path.join(tmpdir(), "hmw-auth-gate-"));
const script = new URL("./check-native-auth-readiness.mjs", import.meta.url).pathname;
const baseline = {schema:1, identitySource:"authenticated-reverse-proxy", principalFrom:"verified-upstream-auth",
 sessionIssuer:"server-only", csrfPolicy:"origin-and-session-bound", workspacePolicy:"principal-and-slot-isolated",
 backendTokenPolicy:"server-only-per-workspace", crossSlotHttpTest:"pass", symlinkHttpTest:"pass", nativeRoundtripHttpTest:"pass"};
try {
  const run = value => { const f = path.join(root,"manifest.json"); writeFileSync(f,JSON.stringify(value)); return spawnSync(process.execPath,[script,f],{encoding:"utf8"}); };
  assert.notEqual(spawnSync(process.execPath,[script],{encoding:"utf8"}).status,0);
  assert.equal(run(baseline).status,0);
  for (const key of Object.keys(baseline)) {
    const changed={...baseline}; delete changed[key];
    assert.notEqual(run(changed).status,0, "missing "+key);
  }
  for (const identitySource of ["slot","client-header","ip-address","none"]) {
    assert.notEqual(run({...baseline,identitySource}).status,0);
  }
  for (const key of ["crossSlotHttpTest","symlinkHttpTest","nativeRoundtripHttpTest"]) {
    assert.notEqual(run({...baseline,[key]:"not-run"}).status,0);
  }
  console.log("NATIVE_AUTH_READINESS_TEST_PASS");
} finally { rmSync(root,{recursive:true,force:true}); }