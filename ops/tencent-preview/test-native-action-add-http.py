import json, os, shutil, urllib.request, urllib.error
from pathlib import Path
root = Path(os.environ["HMW_ISOLATED_WORKSPACE"])
path = root / "native-action-add.hwf"
shutil.copyfile("/home/ubuntu/hmw-agent-lab/workspaces/t2/two-actions.hwf", path)
base = os.environ["HMW_ISOLATED_URL"]
def post(endpoint, body, expected=200):
    request = urllib.request.Request(base + "/api/workflows/" + endpoint, data=json.dumps(body).encode(),
        headers={"Content-Type":"application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=7) as response:
            status, data = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        status, data = error.code, json.loads(error.read())
    assert status == expected, (endpoint, status, data)
    return data
initial = post("open", {"path":path.name})
id = initial["id"]
assert len(initial["actions"]) == 2
created = post("action-add", {"id":id, "type":"DUMMY", "name":"Native completion", "x":330,"y":140})
assert len(created["actions"]) == 3 and created["dirty"]
assert any(a["name"] == "Native completion" for a in created["actions"])
post("action-add", {"id":id,"type":"DUMMY","name":"native completion","x":1,"y":1},409)
post("action-add", {"id":id,"type":"FAKE","name":"Injected","x":1,"y":1},400)
post("action-add", {"id":id,"type":"DUMMY","name":"Invalid","x":-1,"y":1},400)
post("action-add", {"id":id,"type":"ABORT","name":"Missing message","x":1,"y":1},400)
post("action-add", {"id":id,"type":"ABORT","name":"Bad level","message":"bad","logLevel":"TRACE","x":1,"y":1},400)
abort = post("action-add", {"id":id,"type":"ABORT","name":"Abort on failure",
    "message":"Water level threshold exceeded","logLevel":"ERROR","x":520,"y":180})
assert len(abort["actions"]) == 4 and abort["dirty"]
assert any(a["name"] == "Abort on failure" and "Abort workflow" in a["type"] for a in abort["actions"]), abort["actions"]
connected = post("hop-create", {"id":id,"from":"Start","to":"Native completion"})
connected = post("hop-create", {"id":id,"from":"Native completion","to":"Abort on failure"})
assert any(h["from"] == "Start" and h["to"] == "Native completion" for h in connected["hops"])
assert any(h["from"] == "Native completion" and h["to"] == "Abort on failure" for h in connected["hops"])
post("save", {"id":id})
native_xml = path.read_text()
assert "<type>DUMMY</type>" in native_xml, "not serialized as native Hop DUMMY"
assert "<type>ABORT</type>" in native_xml, "not serialized as native Hop ABORT"
assert "Water level threshold exceeded" in native_xml
assert "<loglevel>ERROR</loglevel>" in native_xml
reopened = post("open", {"path":path.name})
assert len(reopened["actions"]) == 4
assert any(a["name"]=="Native completion" and "action does anything" in a["type"] for a in reopened["actions"]), reopened["actions"]
assert any(a["name"]=="Abort on failure" and "Abort workflow" in a["type"] for a in reopened["actions"]), reopened["actions"]
assert len(reopened["hops"]) == 2
print("NATIVE_DUMMY_AND_ABORT_ADD_HOP_SAVE_REOPEN_PASS")