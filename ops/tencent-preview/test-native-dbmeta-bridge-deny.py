#!/usr/bin/env python3
"""Fail-closed regression for the shared preview bridge before scoped auth exists.

This intentionally does NOT send any backend token or create metadata. A 200,
401, or upstream 503 is a failure: it would mean the protected endpoint became
reachable through the unauthenticated slot bridge. Run against port 8181.
"""
import json
import os
import urllib.error
import urllib.request

base = os.environ.get("HMW_BRIDGE_URL", "http://127.0.0.1:8181").rstrip("/")
checks = 0
for slot in ("t1", "t2", "t3", "t4", "t5"):
    for method, suffix, payload in (
        ("GET", "", None), ("GET", "/RIVER_PG", None),
        ("PUT", "/RIVER_PG", b'{}'), ("POST", "", b'{}'),
        ("DELETE", "/RIVER_PG", None),
    ):
        url = f"{base}/{slot}/api/native-connections{suffix}"
        req = urllib.request.Request(url, data=payload, method=method,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=4) as response:
                status, body = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, body = error.code, error.read()
        assert status == 404, f"unsafe bridge exposure {method} {url}: {status}"
        assert json.loads(body).get("code") == "not_found", (
            f"unexpected bridge response {method} {url}: {body[:150]!r}")
        checks += 1
print(f"NATIVE_BRIDGE_FAIL_CLOSED_PASS: {checks} denied requests across 5 slots")
