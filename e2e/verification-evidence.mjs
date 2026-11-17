/** Optional Playwright trace and screenshot evidence for HMW browser E2E. */
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

export async function startEvidence(page, name) {
  const root = process.env.HMW_E2E_EVIDENCE_DIR;
  if (!root) return null;
  if (!/^[a-z0-9-]+$/.test(name)) throw Error("Invalid E2E evidence ID");
  const folder = path.resolve(root);
  await mkdir(folder, { recursive: true });
  const proof = { folder, name, startedAt: new Date().toISOString(), errors: [] };
  page.on("pageerror", (e) => proof.errors.push(String(e.message).slice(0, 600)));
  await page.context().tracing.start({ screenshots: true, snapshots: true, sources: true });
  return proof;
}

export async function finishEvidence(page, proof, failure = null) {
  if (!proof) return;
  const failed = failure != null;
  const meta = { case: proof.name, pass: !failed, startedAt: proof.startedAt,
    endedAt: new Date().toISOString(), pageErrors: proof.errors,
    failure: failed ? String(failure.stack ?? failure).slice(0, 4000) : null };
  try {
    const filename = "browser-" + (failed ? "failure" : "success") + ".png";
    await page.screenshot({ path: path.join(proof.folder, filename),
      fullPage: true, timeout: 12000 });
    meta.screenshot = filename;
  } catch (e) { meta.screenshotError = String(e.message ?? e).slice(0, 500); }
  try {
    if (failed || process.env.HMW_E2E_TRACE_ALL === "1") {
      await page.context().tracing.stop({ path: path.join(proof.folder, "playwright-trace.zip") });
      meta.trace = "playwright-trace.zip";
    } else { await page.context().tracing.stop(); }
  } catch (e) { meta.traceError = String(e.message ?? e).slice(0, 500); }
  try {
    await writeFile(path.join(proof.folder, "browser-evidence.json"), JSON.stringify(meta, null, 2) + "\n");
  } catch (e) { console.error("E2E_EVIDENCE_WRITE_FAILED", String(e.message ?? e)); }
}