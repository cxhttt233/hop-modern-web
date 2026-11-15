"""Real Hop HTTP regression: checked native save stays inside workspace after path swaps.

Runs only in an isolated JVM with UUID synthetic files; never executes a workflow,
pipeline or SQL, or connects to external databases. All outside targets are /tmp.
"""
import json, os, shutil, tempfile, uuid, urllib.request, urllib.error
from pathlib import Path

workspace = Path(os.environ["HMW_ISOLATED_WORKSPACE"]).resolve()
base = os.environ["HMW_ISOLATED_URL"] + "/api/"
root = workspace / ("secure-save-proof-" + str(uuid.uuid4()))
outer = Path(tempfile.mkdtemp(prefix="hmw-outside-save-proof-", dir="/tmp"))
root.mkdir(parents=True)
sources = {
    "workflow": (Path("/home/ubuntu/hmw-agent-lab/workspaces/t2/two-actions.hwf"), "hwf", "workflows"),
    "pipeline": (Path(__file__).resolve().parents[2] / "e2e/fixtures/execsql-native-waterlevel.hpl", "hpl", "pipelines"),
}
results = []

def post(url, payload):
    request = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"),
                                     method="POST", headers={"Content-Type":"application/json"})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())

def expect(condition, detail):
    if not condition:
        raise AssertionError(detail)

def edit(kind, endpoint, identity, suffix=""):
    if kind == "workflow":
        return post(endpoint+"/edit", {"id":identity, "description":"changed-but-uncommitted"+suffix})
    return post(endpoint+"/"+identity+"/edit/add",
                {"nodeId":"guard-proof"+suffix, "pluginId":"TableOutput", "x":150, "y":100})

def open_document(endpoint, relative):
    status, result = post(endpoint+"/open", {"path":relative})
    expect(status == 200, "open failed "+str((status, result)))
    return result["id"]

def save(kind, endpoint, identity):
    if kind == "workflow":
        return post(endpoint+"/save", {"id":identity})
    return post(endpoint+"/"+identity+"/save", {})

try:
    for kind,(source,extension,route) in sources.items():
        expect(source.is_file(),"fixture missing "+str(source))
        endpoint=base+route
        data=source.read_bytes()
        for scenario in ["normal", "external-edit", "parent-symlink", "file-symlink"]:
            scenario_root=root/(kind+"-"+scenario)
            parent=scenario_root/"documents"
            parent.mkdir(parents=True)
            leaf="target."+extension
            target=parent/leaf
            target.write_bytes(data)
            relative=str(target.relative_to(workspace))
            identity=open_document(endpoint,relative)
            code,changed=edit(kind,endpoint,identity)
            expect(code==200,"edit failed "+str((code,changed)))
            expected_status=200
            outside_target=None
            outside_bytes=None
            if scenario=="external-edit":
                outside_bytes=data+b"\n<!-- externally modified -->\n"
                target.write_bytes(outside_bytes)
                expected_status=409
            elif scenario=="parent-symlink":
                outside_directory=outer/(kind+"-parent")
                outside_directory.mkdir()
                outside_target=outside_directory/leaf
                outside_target.write_bytes(data)
                outside_bytes=outside_target.read_bytes()
                parent.rename(scenario_root/"original-documents")
                parent.symlink_to(outside_directory,target_is_directory=True)
                expected_status=409
            elif scenario=="file-symlink":
                outside_target=outer/(kind+"-file."+extension)
                outside_target.write_bytes(data)
                outside_bytes=outside_target.read_bytes()
                target.rename(parent/("original."+extension))
                target.symlink_to(outside_target)
                expected_status=409
            status,result=save(kind,endpoint,identity)
            expect(status==expected_status,
                   kind+"/"+scenario+" unexpected save status "+str((status,result)))
            if scenario=="normal":
                expect(target.read_bytes()!=data,kind+" normal save wrote no native change")
                reopened=open_document(endpoint,relative)
                if kind=="workflow":
                    check_code,detail=post(endpoint+"/edit",
                                           {"id":reopened,"description":"post-save-reopen-verified"})
                    expect(check_code==200,"workflow lost normal reopen")
                else:
                    code2,doc=post(endpoint+"/open",{"path":relative})
                    expect(code2==200 and len(doc["nodes"])==2,"native pipeline save/reopen lost node")
            if scenario=="external-edit":
                expect(target.read_bytes()==outside_bytes,kind+" overwritten external update")
            if outside_target is not None:
                expect(outside_target.read_bytes()==outside_bytes,
                       kind+" wrote outside workspace during "+scenario)
            if expected_status==409:
                code2,_=edit(kind,endpoint,identity,"-after-conflict")
                expect(code2==200,kind+" lost unsaved editable draft after conflict")
            results.append({"type":kind,"scenario":scenario,"saveStatus":status,
                            "outsideUnchanged": outside_target is None or outside_target.read_bytes()==outside_bytes})
    print("SECURE_NATIVE_PIPELINE_AND_WORKFLOW_SAVE_BOUNDARY_PASS")
    print(json.dumps(results,ensure_ascii=False))
finally:
    shutil.rmtree(root,ignore_errors=True)
    shutil.rmtree(outer,ignore_errors=True)
