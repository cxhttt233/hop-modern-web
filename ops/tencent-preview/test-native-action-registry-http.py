#!/usr/bin/env python3
"""Real Hop ActionPluginType acceptance against an isolated Java JVM only.

Run via: bin/with-isolated-java-branch BRANCH python3 ops/tencent-preview/test-native-action-registry-http.py
The runner supplies a disposable workspace and loopback API. No shared 8080 changes.
"""
import json
import os
import shutil
import urllib.request
from pathlib import Path

base = os.environ["HMW_ISOLATED_URL"]
target = Path(os.environ["HMW_ISOLATED_WORKSPACE"]) / "native-actions.hwf"
source = os.environ.get("HMW_ACTION_FIXTURE", "/home/ubuntu/hmw-agent-lab/workspaces/t2/two-actions.hwf")
shutil.copyfile(source, target)
def post(path, payload):
    request = urllib.request.Request(base + path, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=8) as response:
        return json.load(response)
opened = post("/api/workflows/open", {"path": target.name})
assert len(opened["actions"]) == 2 and all("start" in a["type"].lower() for a in opened["actions"]), opened["actions"]
edited = post("/api/workflows/edit", {"id": opened["id"], "description": "native action registry isolated proof"})
assert edited["description"] == "native action registry isolated proof"
post("/api/workflows/save", {"id": opened["id"]})
reopened = post("/api/workflows/open", {"path": target.name})
assert reopened["description"] == "native action registry isolated proof"
assert len(reopened["actions"]) == 2 and all("start" in a["type"].lower() for a in reopened["actions"])
print("NATIVE_ACTION_PLUGIN_AND_DISK_REOPEN_PASS")
