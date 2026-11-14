// Fail-closed authorization primitive for a future authenticated native metadata gateway.
// This module deliberately does not register any HTTP route or hold a Hop service token.
import { createHmac, timingSafeEqual } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const SLOTS = new Set(["t1", "t2", "t3", "t4", "t5"]);
const ID = /^[a-zA-Z0-9_-]{1,80}$/;
function deny() { throw Object.assign(new Error("unauthorized"), { status: 403 }); }
function signature(key, payload) { return createHmac("sha256", key).update(payload).digest(); }
export function authorizeNativeSession({ credential, secret, slot, now = Date.now(), workspaceRoot }) {
  if (!SLOTS.has(slot) || typeof credential !== "string" || !Buffer.isBuffer(secret) || secret.length < 32) deny();
  const parts = credential.split(".");
  if (parts.length !== 2 || !/^[A-Za-z0-9_-]+$/.test(parts[0]) || !/^[A-Za-z0-9_-]+$/.test(parts[1])) deny();
  const actual = Buffer.from(parts[1], "base64url");
  const expected = signature(secret, parts[0]);
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) deny();
  let claims;
  try { claims = JSON.parse(Buffer.from(parts[0], "base64url").toString("utf8")); } catch { deny(); }
  if (!claims || claims.aud !== "hmw-native-metadata" || claims.slot !== slot ||
      !ID.test(claims.subject ?? "") || !Number.isSafeInteger(claims.exp) ||
      claims.exp <= now || claims.exp > now + 3600000) deny();
  if (typeof workspaceRoot !== "string" || !path.isAbsolute(workspaceRoot)) deny();
  const base = fs.realpathSync(workspaceRoot);
  const slotPath = path.join(base, slot);
  const sessionPath = path.join(slotPath, claims.subject);
  // Never accept symlinked slot/session directories, including final components.
  for (const entry of [slotPath, sessionPath]) {
    if (fs.lstatSync(entry).isSymbolicLink() || !fs.lstatSync(entry).isDirectory()) deny();
  }
  const real = fs.realpathSync(sessionPath);
  if (!real.startsWith(base + path.sep) || !real.startsWith(fs.realpathSync(slotPath) + path.sep)) deny();
  return Object.freeze({ slot, subject: claims.subject, workspace: real });
}