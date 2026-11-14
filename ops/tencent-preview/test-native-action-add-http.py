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
connected = post("hop-create", {"id":id,"from":"Start","to":"Native completion"})
assert any(h["from"] == "Start" and h["to"] == "Native completion" for h in connected["hops"])
post("save", {"id":id})
native_xml = path.read_text()
assert "<type>DUMMY</type>" in native_xml, "not serialized as native Hop DUMMY"
assert "Native completion" in native_xml
reopened = post("open", {"path":path.name})
assert len(reopened["actions"]) == 3
assert any(a["name"]=="Native completion" and "action does anything" in a["type"] for a in reopened["actions"]), reopened["actions"]
assert any(h["to"] == "Native completion" for h in reopened["hops"])
print("NATIVE_ACTION_ADD_HOP_SAVE_REOPEN_PASS")