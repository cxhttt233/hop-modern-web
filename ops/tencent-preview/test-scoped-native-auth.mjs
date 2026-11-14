import assert from "node:assert/strict";
import { test } from "node:test";
import { createHmac, randomBytes } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { authorizeNativeSession } from "./scoped-native-auth.mjs";
const root = fs.mkdtempSync(path.join(os.tmpdir(), "hmw-scoped-auth-"));
const key = randomBytes(32), now = Date.now();
const sign = claims => { const p = Buffer.from(JSON.stringify(claims)).toString("base64url"); return p + "." + createHmac("sha256", key).update(p).digest("base64url"); };
const claims = { aud: "hmw-native-metadata", slot: "t1", subject: "userA", exp: now + 60000 };
try {
  for (const slot of ["t1", "t2"]) fs.mkdirSync(path.join(root, slot));
  fs.mkdirSync(path.join(root, "t1", "userA"));
  fs.symlinkSync(path.join(root, "t1", "userA"), path.join(root, "t2", "userA"));
  const check = (credential, slot = "t1") => authorizeNativeSession({ credential, secret: key, slot, now, workspaceRoot: root });
  test("valid signed session resolves only its own real workspace", () => assert.equal(check(sign(claims)).workspace, path.join(root, "t1", "userA")));
  test("cross-slot credential is rejected", () => assert.throws(() => check(sign(claims), "t2"), /unauthorized/));
  test("tampered signature rejected", () => assert.throws(() => check(sign(claims).slice(0, -2) + "xx"), /unauthorized/));
  test("expired credential rejected", () => assert.throws(() => check(sign({ ...claims, exp: now - 1 })), /unauthorized/));
  test("wrong audience rejected", () => assert.throws(() => check(sign({ ...claims, aud: "other" })), /unauthorized/));
  test("path traversal identity rejected", () => assert.throws(() => check(sign({ ...claims, subject: "../t2" })), /unauthorized/));
  test("symlinked workspace rejected", () => assert.throws(() => check(sign({ ...claims, slot: "t2" }), "t2"), /unauthorized/));
  test("missing key rejected", () => assert.throws(() => authorizeNativeSession({ credential: sign(claims), slot: "t1", workspaceRoot: root }), /unauthorized/));
} finally { process.on("exit", () => fs.rmSync(root, { recursive: true, force: true })); }