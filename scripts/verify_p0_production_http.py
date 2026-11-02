#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.
"""Verify real P0 editing HTTP routes on the packaged production Jetty listener.

Run only against an isolated CI listener with the official P0 distribution.
Never executes a pipeline, modifies the source fixture, or persists an edit.
"""

import hashlib
import json
import shutil
import sys
import tempfile
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

P0_IDS = frozenset({
    "CheckSum", "ConcatFields", "DataGrid", "ExecSql", "FilterRows",
    "GroupBy", "Http", "InsertUpdate", "JsonInput", "MergeJoin",
    "ReplaceString", "Rest", "ScriptValueMod", "SelectValues", "SetVariable",
    "StreamLookup", "StringCut", "TableInput", "TableOutput", "UniqueRowsByHashSet",
})


def post(base, path, payload):
    request = Request(
        base.rstrip("/") + path,
        data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=8) as response:
            status = response.status
            body = response.read(8 * 1024 * 1024)
    except HTTPError as error:
        status = error.code
        body = error.read(8192)
    except (URLError, TimeoutError) as error:
        raise AssertionError(f"{path}: transport error: {error}") from error
    if status != 200:
        raise AssertionError(f"{path}: HTTP {status}, response={body[:1200]!r}")
    try:
        value = json.loads(body)
    except (ValueError, UnicodeDecodeError) as error:
        raise AssertionError(f"{path}: invalid JSON response: {body[:300]!r}") from error
    if not isinstance(value, dict):
        raise AssertionError(f"{path}: expected a JSON object")
    return value


def verify(base, fixture, manifest):
    distribution = json.loads(manifest.read_text(encoding="utf-8"))
    actual = [entry["id"] for entry in distribution["plugins"]]
    if len(actual) != 20 or set(actual) != P0_IDS or len(set(actual)) != len(actual):
        raise AssertionError("official P0 distribution manifest does not match the 20-plugin contract")
    with tempfile.TemporaryDirectory(prefix="p0-production-http-") as directory:
        copy = Path(directory) / "p0-http-fixture.hpl"
        shutil.copyfile(fixture, copy)
        original_sha = hashlib.sha256(copy.read_bytes()).hexdigest()
        graph = post(base, "/api/pipelines/open", {"path": str(copy)})
        document_id = graph.get("id")
        if not isinstance(document_id, str) or len(document_id) != 64:
            raise AssertionError("real .hpl open did not return an opaque document ID")
        root = "/api/pipelines/" + quote(document_id, safe="")
        expected_config = {}
        for plugin_id in sorted(P0_IDS):
            node_id = "packaged-" + plugin_id
            graph = post(base, root + "/edit/add", {
                "nodeId": node_id, "pluginId": plugin_id, "x": 40, "y": 40,
            })
            matches = [node for node in graph.get("nodes", [])
                       if node.get("id") == node_id and node.get("pluginId") == plugin_id]
            if len(matches) != 1:
                raise AssertionError(f"{plugin_id}: added node missing from authoritative graph")
            config = post(base, root + "/config/read", {"nodeId": node_id})
            if (config.get("nodeId") != node_id or config.get("pluginId") != plugin_id
                    or not isinstance(config.get("config"), dict)
                    or not isinstance(config.get("descriptor"), dict)):
                raise AssertionError(f"{plugin_id}: authoritative config or descriptor missing")
            # Real HTTP config writes must survive both an immediate read and disk reopen.
            written = post(base, root + "/config/write", {
                "nodeId": node_id, "config": config["config"],
            })
            if (written.get("nodeId") != node_id or written.get("pluginId") != plugin_id
                    or not isinstance(written.get("config"), dict)):
                raise AssertionError(f"{plugin_id}: config write response invalid")
            reread = post(base, root + "/config/read", {"nodeId": node_id})
            if reread.get("config") != written["config"]:
                raise AssertionError(f"{plugin_id}: HTTP write/read config mismatch")
            expected_config[plugin_id] = reread["config"]
            print(f"PRODUCTION_P0_HTTP_PLUGIN={plugin_id} status=PASS", flush=True)
        if hashlib.sha256(copy.read_bytes()).hexdigest() != original_sha:
            raise AssertionError("in-memory HTTP editing unexpectedly changed the disk fixture")
        print("PRODUCTION_P0_HTTP_DISK_UNCHANGED_BEFORE_SAVE=PASS", flush=True)

        saved = post(base, root + "/save", {})
        if not all(any(node.get("id") == "packaged-" + plugin_id
                       for node in saved.get("nodes", [])) for plugin_id in P0_IDS):
            raise AssertionError("save response lost one or more P0 transforms")
        if hashlib.sha256(copy.read_bytes()).hexdigest() == original_sha:
            raise AssertionError("save did not persist the 20 added P0 transforms")
        reopened = post(base, "/api/pipelines/open", {"path": str(copy)})
        reopened_id = reopened.get("id")
        if not isinstance(reopened_id, str) or len(reopened_id) != 64:
            raise AssertionError("disk reopen did not return a document ID")
        reopened_root = "/api/pipelines/" + quote(reopened_id, safe="")
        for plugin_id in sorted(P0_IDS):
            node_id = "packaged-" + plugin_id
            if not any(node.get("id") == node_id and node.get("pluginId") == plugin_id
                       for node in reopened.get("nodes", [])):
                raise AssertionError(f"{plugin_id}: persisted node missing after disk reopen")
            reread = post(base, reopened_root + "/config/read", {"nodeId": node_id})
            if reread.get("pluginId") != plugin_id or reread.get("config") != expected_config[plugin_id]:
                raise AssertionError(f"{plugin_id}: persisted config differs after disk reopen")
            print(f"PRODUCTION_P0_HTTP_PERSISTED_PLUGIN={plugin_id} status=PASS", flush=True)
    print("PRODUCTION_P0_HTTP_OPEN_ADD_CONFIG=20/20 PASS", flush=True)
    print("PRODUCTION_P0_HTTP_CONFIG_WRITE_READ=20/20 PASS", flush=True)
    print("PRODUCTION_P0_HTTP_SAVE_REOPEN_CONFIG=20/20 PASS", flush=True)


def main(argv):
    if len(argv) != 4:
        raise SystemExit("usage: verify_p0_production_http.py <loopback-url> <fixture.hpl> <p0-manifest.json>")
    base, fixture, manifest = argv[1], Path(argv[2]).resolve(), Path(argv[3]).resolve()
    if not base.startswith("http://127.0.0.1:"):
        raise ValueError("production HTTP admission must target a loopback-only CI listener")
    if not fixture.is_file() or not manifest.is_file():
        raise ValueError("fixture and distribution manifest must exist")
    verify(base, fixture, manifest)


if __name__ == "__main__":
    main(sys.argv)
