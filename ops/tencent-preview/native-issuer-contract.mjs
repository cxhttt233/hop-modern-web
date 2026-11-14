// A fail-closed adapter contract. The reverse proxy MUST authenticate a user first.
// This module does not accept user identity from HTTP headers, IP addresses or slots.
import { createHmac, timingSafeEqual } from 'node:crypto';
const DENY = () => { throw Object.assign(new Error('forbidden'), { status: 403 }); };
const ID = /^[A-Za-z0-9_-]{1,80}$/;
const SLOTS = new Set(['t1','t2','t3','t4','t5']);
function key(k) { if (!Buffer.isBuffer(k) || k.length < 32) DENY(); }
function digest(k, v) { return createHmac('sha256', k).update(v).digest(); }
function equal(a,b) { return a.length === b.length && timingSafeEqual(a,b); }
// verifiedPrincipal must be supplied ONLY by a trusted authentication middleware,
// never populated from request headers or a browser-provided claim.
export function authorizeNativeMutation({ verifiedPrincipal, request, slot, sessionId, csrfSecret, allowedOrigin }) {
  key(csrfSecret);
  if (!verifiedPrincipal || verifiedPrincipal.authenticated !== true ||
      verifiedPrincipal.source !== 'trusted-issuer' ||
      typeof verifiedPrincipal.subject !== 'string' || !ID.test(verifiedPrincipal.subject) ||
      !SLOTS.has(slot) || verifiedPrincipal.slot !== slot ||
      typeof sessionId !== 'string' || !ID.test(sessionId) ||
      typeof allowedOrigin !== 'string' || !/^https:\/\//.test(allowedOrigin)) DENY();
  if (!request || !['PUT','POST','PATCH','DELETE'].includes(request.method) ||
      request.origin !== allowedOrigin || request.fetchSite !== 'same-origin' ||
      typeof request.csrf !== 'string' || !/^[a-f0-9]{64}$/.test(request.csrf)) DENY();
  const expected = digest(csrfSecret, ['hmw-native-csrf-v1',verifiedPrincipal.subject,slot,sessionId].join('\n'));
  if (!equal(Buffer.from(request.csrf,'hex'),expected)) DENY();
  return Object.freeze({ subject: verifiedPrincipal.subject, slot });
}
// Called by trusted server code only; never expose this helper as an anonymous endpoint.
export function mintNativeCsrf({ verifiedPrincipal, slot, sessionId, csrfSecret }) {
  key(csrfSecret);
  if (verifiedPrincipal?.authenticated !== true || verifiedPrincipal.source !== 'trusted-issuer' ||
      !ID.test(verifiedPrincipal.subject ?? '') || verifiedPrincipal.slot !== slot ||
      !SLOTS.has(slot) || !ID.test(sessionId ?? '')) DENY();
  return digest(csrfSecret,['hmw-native-csrf-v1',verifiedPrincipal.subject,slot,sessionId].join('\n')).toString('hex');
}