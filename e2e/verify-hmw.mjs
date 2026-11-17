#!/usr/bin/env node
/** HMW user-journey E2E gate: real Chromium + per-slot bridge + native Hop Java.
 * No production database contact, no shared fixture mutation, no hidden retries.
 */
import { spawn, execFileSync } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID } from "node:crypto";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const cases = [
  { id: "t2-workflow-fileexists", slot: "t2",
    file: "t2-fileexists-browser-save-retry.mjs",
    goal: "Workflow Action, 503, retry and native .hwf reopen",
    env: { T2_LIVE_SHARED_BACKEND: "1", T2_TEST_WORKSPACE: "t2" } },
  { id: "t3-filterrows-composite", slot: "t3",
    file: "t3-filterrows-composite-roundtrip.mjs",
    goal: "FilterRows AND/OR numeric constant, cancel and native .hpl reopen",
    env: { T3_TEST_WORKSPACE: "t3" } },
  { id: "t4-execsql-sql", slot: "t4",
    file: "t4-execsql-sql-workbench-roundtrip.mjs",
    goal: "SQL Monaco/textarea sync, cancel and native .hpl reopen",
    env: { T4_TEST_WORKSPACE: "t4" } },
  { id: "t5-save-conflict", slot: "t5",
    file: "t5-external-save-conflict-roundtrip.mjs",
    goal: "External file conflict 409, retry 200, disk reopen",
    env: { T5_TEST_WORKSPACE: "t5" } },
];
const args = process.argv.slice(2);
const option = (flag) => args.find(x => x.startsWith(flag + "="))?.slice(flag.length + 1);
if (args.includes("--list")) { cases.forEach(x => console.log(x.id + " | " + x.goal)); process.exit(0); }
if (args.includes("--help")) {
  console.log("node e2e/verify-hmw.mjs [--case=ID] [--trace=on-failure|all] [--timeout=90] [--out=/path]");
  process.exit(0);
}
const selected = option("--case") ? cases.filter(c => c.id === option("--case")) : cases;
if (!selected.length) throw Error("Unknown case; use --list");
const traceMode = option("--trace") ?? "on-failure";
if (!["on-failure", "all"].includes(traceMode)) throw Error("Invalid trace mode");
const seconds = Number(option("--timeout") ?? "90");
if (!Number.isSafeInteger(seconds) || seconds < 15 || seconds > 300) throw Error("Timeout range 15..300 seconds");
const lab = process.env.HMW_LAB_ROOT ?? "/home/ubuntu/hmw-agent-lab";
const workspaces = process.env.HMW_E2E_WORKSPACE_ROOT ?? path.join(lab, "workspaces");
const outBase = path.resolve(option("--out") ?? path.join(root, "e2e/.artifacts"));
const runDir = path.join(outBase, new Date().toISOString().replaceAll(":", "-").replaceAll(".", "-") + "-" + randomUUID().slice(0, 8));
await mkdir(runDir, { recursive: true });
const readText = async (p) => readFile(p, "utf8").then(t => t.trim()).catch(() => null);
const sourceCommit = (() => {try {
  return execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" }).trim();
} catch {return null;}})();
const sourceDirty = (() => { try {
  return execFileSync("git", ["status", "--porcelain", "--", "e2e", ".gitignore"], { cwd: root, encoding: "utf8" }).trim().length > 0;
} catch { return null; } })();
const previews = {};
for (const item of selected) {
  if (previews[item.slot]) continue;
  const folder = path.join(lab, "previews", item.slot);
  previews[item.slot] = {
    commit: await readText(path.join(folder, ".preview-sha")),
    branch: await readText(path.join(folder, ".preview-branch")),
    base: "http://127.0.0.1:4196/" + item.slot + "/"
  };
}
const report = {
  schema: "hmw-e2e-v1", startedAt: new Date().toISOString(),
  sourceCommit, sourceDirty, previews, javaActiveCommit: await readText("/var/lib/hmw-backend-activator/active-sha"),
  mode: "serial, live Playwright UI + slot bridge + native Hop Java",
  exclusions: ["T1 multi-user authenticated DatabaseMeta CRUD", "Native Workflow execution/log stream"],
  cases: []
};
const saveReport = () => writeFile(path.join(runDir, "report.json"), JSON.stringify(report, null, 2) + "\n");
await saveReport();
let failures = 0;
for (const item of selected) {
  const evidenceDir = path.join(runDir, item.id);
  await mkdir(evidenceDir, { recursive: true });
  const env = { ...process.env, HMW_E2E_EVIDENCE_DIR: evidenceDir,
    HMW_E2E_TRACE_ALL: traceMode === "all" ? "1" : "0" };
  for (const [k, v] of Object.entries(item.env)) {
    env[k] = /^t[1-5]$/.test(v) ? path.join(workspaces, v) : v;
  }
  const result = { id: item.id, goal: item.goal, slot: item.slot,
    startedAt: new Date().toISOString(), script: "node e2e/" + item.file,
    evidenceDir: item.id };
  const start = Date.now();
  let stdout = "", stderr = "", exitCode = -1, signal = null, timedOut = false;
  console.log("E2E_START " + item.id);
  try {
    const child = spawn(process.execPath, [path.join(root, "e2e", item.file)], {
      cwd: root, env, stdio: ["ignore", "pipe", "pipe"],
      detached: process.platform !== "win32"
    });
    const killAfter = setTimeout(() => {
      timedOut = true;
      // Kill the whole Linux process group, including the Playwright browser children.
      try {
        if (process.platform !== "win32" && child.pid) process.kill(-child.pid, "SIGKILL");
        else child.kill("SIGKILL");
      } catch { child.kill("SIGKILL"); }
    }, seconds * 1000);
    child.stdout.on("data", chunk => { stdout += String(chunk); if (stdout.length > 2000000) stdout = stdout.slice(-2000000); });
    child.stderr.on("data", chunk => { stderr += String(chunk); if (stderr.length > 2000000) stderr = stderr.slice(-2000000); });
    const completed = await new Promise((resolve, reject) => {
      child.once("error", reject);
      child.once("exit", (code, sig) => resolve({code, sig}));
    });
    clearTimeout(killAfter);
    exitCode = completed.code; signal = completed.sig;
  } catch (e) { stderr += "\nRunner: " + String(e.stack ?? e); }
  result.durationMs = Date.now() - start;
  result.endedAt = new Date().toISOString();
  result.exitCode = exitCode; result.signal = signal;
  const previewAfter = await readText(path.join(lab, "previews", item.slot, ".preview-sha"));
  const javaAfter = await readText("/var/lib/hmw-backend-activator/active-sha");
  result.previewCommitAfter = previewAfter;
  result.javaActiveCommitAfter = javaAfter;
  result.versionsStable = Boolean(previews[item.slot]?.commit && report.javaActiveCommit &&
    previews[item.slot].commit === previewAfter && report.javaActiveCommit === javaAfter);
  result.timedOut = timedOut;
  result.pass = exitCode === 0 && !timedOut && result.versionsStable;
  result.failureHint = result.pass ? null :
    (result.versionsStable ? "" : "Preview/Java version changed or cannot be verified during E2E; ") +
    (stderr.trim().split("\n").slice(-8).join("\n") || stdout.trim().split("\n").slice(-8).join("\n")).slice(-1600);
  await writeFile(path.join(evidenceDir, "stdout.log"), stdout);
  await writeFile(path.join(evidenceDir, "stderr.log"), stderr);
  report.cases.push(result);
  if (!result.pass) failures++;
  report.completedAt = result.endedAt;
  report.pass = failures === 0;
  await saveReport();
  console.log("E2E_" + (result.pass ? "PASS " : "FAIL ") + item.id + " duration=" + result.durationMs + "ms");
}
report.completedAt = new Date().toISOString();
report.summary = { total: selected.length, passed: selected.length - failures, failed: failures };
report.pass = failures === 0;
await saveReport();
const lines = [
  "# HMW E2E evidence", "",
  "Source commit: " + (sourceCommit ?? "unknown"),
  "Active Java commit: " + (report.javaActiveCommit ?? "unknown"),
  "Started: " + report.startedAt, "",
  "| Browser scenario | Actual preview SHA | Result | Duration |",
  "|---|---|---|---|",
  ...report.cases.map(c => "| " + c.id + " | " +
    (previews[c.slot]?.commit?.slice(0, 12) ?? "unknown") + " | " +
    (c.pass ? "PASS" : "FAIL") + " | " + c.durationMs + " ms |"),
  "", "Each case directory includes stdout/stderr logs, a browser screenshot and a Playwright trace when failed.",
  "T1 trusted identity and real workflow execution are not covered.", ""
];
await writeFile(path.join(runDir, "README.md"), lines.join("\n"));
console.log("E2E_REPORT " + path.join(runDir, "report.json"));
console.log("E2E_RESULT " + JSON.stringify(report.summary));
if (!report.pass) process.exitCode = 1;