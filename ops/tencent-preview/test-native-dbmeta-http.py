#!/usr/bin/env python3
"""Run inside with-isolated-java-branch with HMW_ISOLATED_PG_PLUGIN=1.

Tests genuine PostgreSQL DatabaseMeta JSON metadata on a private disposable
workspace; no password, external connection, shared backend or user files.
"""
import json
import os
from pathlib import Path
import tempfile
import urllib.error
import urllib.request

base = os.environ["HMW_ISOLATED_URL"] + "/api/native-connections"
workspace = Path(os.environ["HMW_ISOLATED_WORKSPACE"])
token = os.environ["HMW_ISOLATED_TOKEN"]
headers = {"X-HMW-Connection-Token": token, "Content-Type": "application/json"}


def request(method: str, path: str = "", data=None, *, auth=True):
    h = headers if auth else {"Content-Type": "application/json"}
    req = urllib.request.Request(
        base + path, method=method, headers=h,
        data=json.dumps(data).encode("utf-8") if data is not None else None,
    )
    try:
        with urllib.request.urlopen(req, timeout=9) as response:
            body = response.read().decode("utf-8")
            return response.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8")[:250]


def expect(actual, expected, message):
    if actual != expected:
        raise AssertionError(f"{message}: got {actual!r}, expected {expected!r}")


code, _ = request("GET", auth=False)
expect(code, 403, "missing-token denial")
code, _ = request("GET", "/BAD", auth=False)
expect(code, 403, "missing-token read denial")

draft = {
    "name": "RIVER_PG", "type": "POSTGRESQL", "hostname": "127.0.0.1",
    "databaseName": "river_observation", "port": "5432", "username": "monitor",
}
code, connection = request("PUT", "/RIVER_PG", draft)
expect(code, 200, "native PostgreSQL metadata create")
expect(connection, draft, "native connection values")
file = workspace / "metadata" / "rdbms" / "RIVER_PG.json"
if not file.is_file():
    raise AssertionError("native Hop metadata JSON missing on disk")
original = file.read_bytes()
if b"POSTGRESQL" not in original or b"river_observation" not in original:
    raise AssertionError("native metadata did not persist required fields")
# Hop's native serializer may include an empty password *property*, which
# does not mean that a credential was supplied or stored. Verify that the
# endpoint rejects or discards a deliberately supplied secret value instead.
canary = "HMW_TEST_CANARY_PASSWORD_DO_NOT_PERSIST"
code, _ = request("PUT", "/RIVER_PG", dict(draft, password=canary))
if code not in (200, 400, 422):
    raise AssertionError(f"unexpected password-injection status {code}")
if canary.encode() in file.read_bytes():
    raise AssertionError("client-supplied secret leaked into native metadata")
# The test file may have been overwritten with identical password-free data.
original = file.read_bytes()

code, restored = request("GET", "/RIVER_PG")
expect(code, 200, "native metadata reload")
expect(restored, draft, "reloaded connection metadata")
code, listed = request("GET")
expect(code, 200, "list metadata")
expect(listed["connections"], [draft], "list persisted connection")

# A workspace symlink must never read or write metadata outside the workspace.
with tempfile.TemporaryDirectory(prefix="hmw-native-db-outside-", dir="/home/ubuntu/hmw-agent-lab") as outer:
    outside = Path(outer) / "outside.json"
    outside.write_bytes(original)
    link = file.parent / "OUTSIDE.json"
    link.symlink_to(outside)
    try:
        for method, pathname, value in [
            ("GET", "/OUTSIDE", None),
            ("GET", "", None),
            ("PUT", "/OUTSIDE", dict(draft, name="OUTSIDE")),
        ]:
            code, _ = request(method, pathname, value)
            expect(code, 503, f"symlink file must fail closed: {method} {pathname}")
        if outside.read_bytes() != original:
            raise AssertionError("external sentinel modified via symlink")
    finally:
        link.unlink()

    directory = workspace / "metadata" / "rdbms"
    saved_directory = directory.with_name("rdbms-original")
    directory.rename(saved_directory)
    directory.symlink_to(saved_directory, target_is_directory=True)
    try:
        code, _ = request("GET", "/RIVER_PG")
        expect(code, 503, "directory symlink must fail closed")
    finally:
        directory.unlink()
        saved_directory.rename(directory)

code, recovered = request("GET", "/RIVER_PG")
expect(code, 200, "normal reads resume after symlink removed")
expect(recovered, draft, "normal metadata still intact")
code, _ = request("PUT", "/bad.name", dict(draft, name="bad.name"))
# Dotted names are permitted, but we must not unexpectedly reject safe relative names.
expect(code, 200, "allowed simple dotted name")
code, _ = request("PUT", "/INVALID%", dict(draft, name="INVALID%"))
if code not in (400, 404):
    raise AssertionError(f"invalid path was accepted with HTTP {code}")

print(json.dumps({
    "pass": True, "type": "POSTGRESQL", "nativeFile": "metadata/rdbms/RIVER_PG.json",
    "create": 200, "read": 200, "unauthorized": 403, "symlinkFile": 503,
    "symlinkDirectory": 503, "outsideModified": False,
    "diskRoundtrip": True, "externalConnectionAttempted": False,
}, ensure_ascii=False))
