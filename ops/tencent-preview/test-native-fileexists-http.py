"""Isolated 8084 native FILE_EXISTS create/validate/connect/save/reopen test."""
import json
import os
import shutil
import uuid
from pathlib import Path
from urllib import request, error

workspace = Path(os.environ["HMW_ISOLATED_WORKSPACE"])
path = workspace / ("t2-fileexists-" + str(uuid.uuid4()) + ".hwf")
base = os.environ["HMW_ISOLATED_URL"] + "/api/workflows/"

def post(route, body, expected=200):
    req = request.Request(base + route, data=json.dumps(body).encode(),
                          headers={"Content-Type": "application/json"}, method="POST")
    try:
        with request.urlopen(req, timeout=10) as response:
            status, payload = response.status, json.load(response)
    except error.HTTPError as exc:
        status, payload = exc.code, json.loads(exc.read())
    assert status == expected, (route, status, payload)
    return payload

try:
    shutil.copyfile("/home/ubuntu/hmw-agent-lab/workspaces/t2/two-actions.hwf", path)
    opened = post("open", {"path": path.name})
    workflow_id = opened["id"]
    for filename in ("../secret", "/etc/passwd", "${HOME}/data", "a/../../b", "", "https://example.com/x"):
        post("action-add", {"id": workflow_id, "type": "FILE_EXISTS", "name": "Bad path",
                            "filename": filename, "x": 200, "y": 150}, 400)
    created = post("action-add", {"id": workflow_id, "type": "FILE_EXISTS",
                                  "name": "Check input", "filename": "inputs/flow.csv",
                                  "x": 280, "y": 180})
    assert any(a["name"] == "Check input" and a["filename"] == "inputs/flow.csv"
               for a in created["actions"]), created
    post("hop-create", {"id": workflow_id, "from": "Start", "to": "Check input"})
    post("save", {"id": workflow_id})
    xml = path.read_text()
    assert "<type>FILE_EXISTS</type>" in xml and "inputs/flow.csv" in xml
    reopened = post("open", {"path": path.name})
    assert any(a["name"] == "Check input" and a["filename"] == "inputs/flow.csv"
               for a in reopened["actions"]), reopened
    assert any(h["from"] == "Start" and h["to"] == "Check input" for h in reopened["hops"])
    print("T2_FILE_EXISTS_HTTP_CREATE_VALIDATE_CONNECT_SAVE_REOPEN_PASS")
finally:
    path.unlink(missing_ok=True)
