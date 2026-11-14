// Server-side boundary only. Never forward database requests to the shared Hop JVM.
// An upstream authentication service must verify the user before calling issueSession.
import { createHmac, timingSafeEqual } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
const SLOTS = new Set(['t1', 't2', 't3', 't4', 't5']);
const ID = /^[a-zA-Z0-9_-]{1,80}$/;
const deny = () => { throw Object.assign(new Error('unauthorized'), { status: 403 }); };
const mac = (secret, data) => createHmac('sha256', secret).update(data).digest();
function requireKey(secret) { if (!Buffer.isBuffer(secret) || secret.length < 32) deny(); }
export function issueSession({ authenticatedSubject, slot, secret, now = Date.now(), ttlMs = 300000 }) {
  requireKey(secret);
  if (!SLOTS.has(slot) || typeof authenticatedSubject !== 'string' || !ID.test(authenticatedSubject) ||
      !Number.isSafeInteger(now) || !Number.isSafeInteger(ttlMs) || ttlMs < 1 || ttlMs > 300000) deny();
  const payload = Buffer.from(JSON.stringify({ aud: 'hmw-native-metadata', slot, subject: authenticatedSubject,
    iat: now, exp: now + ttlMs })).toString('base64url');
  return payload + '.' + mac(secret, payload).toString('base64url');
}
export function verifySession({ credential, slot, secret, workspaceRoot, now = Date.now() }) {
  requireKey(secret);
  if (!SLOTS.has(slot) || typeof credential !== 'string' || credential.length > 2048) deny();
  const parts = credential.split('.');
  if (parts.length !== 2 || !parts.every(p => /^[A-Za-z0-9_-]+$/.test(p))) deny();
  const expected = mac(secret, parts[0]);
  const actual = Buffer.from(parts[1], 'base64url');
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) deny();
  let claims;
  try { claims = JSON.parse(Buffer.from(parts[0], 'base64url').toString('utf8')); } catch { deny(); }
  if (claims?.aud !== 'hmw-native-metadata' || claims.slot !== slot || !ID.test(claims.subject ?? '') ||
      !Number.isSafeInteger(claims.iat) || !Number.isSafeInteger(claims.exp) ||
      claims.iat > now || claims.exp <= now || claims.exp - claims.iat > 300000) deny();
  if (typeof workspaceRoot !== 'string' || !path.isAbsolute(workspaceRoot)) deny();
  try {
    const base = fs.realpathSync(workspaceRoot);
    const slotDir = path.join(base, slot);
    const sessionDir = path.join(slotDir, claims.subject);
    for (const p of [slotDir, sessionDir]) {
      const stat = fs.lstatSync(p);
      if (!stat.isDirectory() || stat.isSymbolicLink()) deny();
    }
    const real = fs.realpathSync(sessionDir);
    if (!real.startsWith(fs.realpathSync(slotDir) + path.sep)) deny();
    return Object.freeze({ subject: claims.subject, slot, workspace: real });
  } catch { deny(); }
}