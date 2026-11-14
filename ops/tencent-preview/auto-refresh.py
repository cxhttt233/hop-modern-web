#!/usr/bin/env python3
"""Update per-agent previews from their newest repo PRs, retaining last good builds."""
import pathlib
import re
import subprocess

LAB = pathlib.Path("/home/ubuntu/hmw-agent-lab")
PREVIEWS = LAB / "previews"
LOGS = LAB / "logs"
PATTERNS = {
    "t1": re.compile(r"^(t1/|feat/t1[-/]|work/task-1)"),
    "t2": re.compile(r"^(t2/|feat/t2[-/]|work/t2-|work/task-2)"),
    "t3": re.compile(r"^(t3/|feat/t3[-/]|test/t3-|work/task-3)"),
    "t4": re.compile(r"^(t4/|feat/t4[-/]|work/task-4)"),
    "t5": re.compile(r"^(t5/|feat/t5[-/]|feature/|integration/product-)"),
}
REPO = LAB / "source"
def discover_branches():
    # A full shallow fetch of every head periodically stalls on this small VM.
    # Discover cheaply, then download only changed branch tips. Cached refs are
    # retained when GitHub is slow; never delete or blank the last good preview.
    remote_names = None
    try:
        remote = subprocess.run(
            ["git", "-C", str(REPO), "ls-remote", "--heads", "origin"],
            check=True, capture_output=True, text=True, timeout=18)
        heads = {ref.removeprefix("refs/heads/"): sha for sha, ref in
                 (line.split("\t", 1) for line in remote.stdout.splitlines())
                 if ref.startswith("refs/heads/")}
        if not heads:
            raise RuntimeError("GitHub returned no branches")
        remote_names = set(heads)
        known = subprocess.run(
            ["git", "-C", str(REPO), "for-each-ref",
             "--format=%(refname:short)|%(objectname)", "refs/remotes/origin/"],
            check=True, capture_output=True, text=True, timeout=10)
        local = dict(line.removeprefix("origin/").rsplit("|", 1)
                     for line in known.stdout.splitlines() if line.startswith("origin/"))
        changed = [name for name, sha in heads.items() if local.get(name) != sha]
        if changed:
            refspecs = ["+refs/heads/" + name + ":refs/remotes/origin/" + name
                        for name in changed]
            subprocess.run(["git", "-C", str(REPO), "fetch", "-q", "--no-tags",
                            "--depth=1", "origin", *refspecs], check=True, timeout=55)
        print("GIT_DISCOVERY", len(heads), "heads,", len(changed), "changed", flush=True)
    except Exception as exc:
        print("GIT_DEGRADED using cached remote refs:", repr(exc), flush=True)
    result = subprocess.run(
        ["git", "-C", str(REPO), "for-each-ref",
         "--format=%(refname:short)|%(committerdate:unix)",
         "refs/remotes/origin/"],
        text=True, capture_output=True, check=True, timeout=15)
    refs = []
    for line in result.stdout.splitlines():
        try:
            name, ts = line.rsplit("|", 1)
            if name.startswith("origin/"):
                branch = name.removeprefix("origin/")
                if remote_names is None or branch in remote_names:
                    refs.append((branch, int(ts)))
        except ValueError:
            continue
    return refs
def choose(slot, refs):
    # A task branch created before the latest main should never replace newer
    # already-integrated product features in the automatic preview. Agents
    # can still explicitly preview any review branch with preview-branch.
    if slot == "t5":
        return "main"
    main_timestamp = next((ts for branch, ts in refs if branch == "main"), 0)
    candidates = [(branch, ts) for branch, ts in refs
                  if PATTERNS[slot].match(branch) and ts > main_timestamp]
    if candidates:
        return max(candidates, key=lambda item: (item[1], item[0]))[0]
    if main_timestamp:
        return "main"
    pinned = PREVIEWS / slot / ".preview-branch"
    return pinned.read_text().strip() if pinned.exists() else "main"

def run():
    LOGS.mkdir(parents=True, exist_ok=True)
    try:
        refs = discover_branches()
    except Exception as e:
        print("Git branch discovery unavailable; preserve all current previews:", str(e), flush=True)
        refs = None
    # Deploy matching Hop Java code from main before updating browser previews.
    try:
        backend = subprocess.run([str(LAB / 'bin/backend-refresh')], capture_output=True,
                                 text=True, timeout=170, check=False)
        print('JAVA_BACKEND', backend.returncode, (backend.stdout + backend.stderr)[-300:], flush=True)
    except Exception as e:
        print('JAVA_BACKEND_UNAVAILABLE', str(e), flush=True)
    if refs is None:
        print("PREVIEW_REFRESH_SKIPPED: no fresh branch information", flush=True)
        return
    for slot in PATTERNS:
        pin = PREVIEWS / slot / '.preview-pin'
        if pin.exists():
            print(slot, 'PINNED_LOCAL', pin.read_text().strip(), flush=True)
            continue
        branch = choose(slot, refs)
        previous = PREVIEWS / slot / ".preview-sha"
        old_sha = previous.read_text().strip() if previous.exists() else ""
        if not branch or branch.startswith("-"):
            print(slot, "skip invalid ref", flush=True)
            continue
        remote_sha = subprocess.run(
            ["git", "-C", str(REPO), "rev-parse", "--verify", "refs/remotes/origin/" + branch],
            capture_output=True, text=True, timeout=8, check=False)
        expected = remote_sha.stdout.strip()
        if remote_sha.returncode != 0:
            print(slot, branch, "REF_NOT_AVAILABLE", flush=True)
            continue
        if old_sha == expected and (PREVIEWS / slot / ".preview-api").read_text().strip() == "connected-v1":
            print(slot, branch, old_sha[:12], "UNCHANGED", flush=True)
            continue
        cmd = [str(LAB / "bin/preview-branch"), slot, branch]
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=240,
                                    env={**__import__("os").environ, "HMW_PREVIEW_CACHED_REF": "1"},check=False)
        except Exception as e:
            print(slot, "build invocation failed:", str(e), flush=True)
            continue
        (LOGS / ("auto-refresh-" + slot + ".log")).write_text(result.stdout + result.stderr)
        if result.returncode != 0:
            print(slot, branch, "FAILED", result.returncode, (result.stderr or "")[-240:], flush=True)
            continue
        current_sha = previous.read_text().strip() if previous.exists() else ""
        print(slot, branch, current_sha[:12], "UPDATED" if current_sha != old_sha else "UNCHANGED", flush=True)
        if current_sha != old_sha:
            smoke = subprocess.run(["/usr/bin/node", str(LAB / "bin/smoke-product.mjs"), slot],
                                   capture_output=True, text=True, timeout=45, check=False)
            (LOGS / ("auto-smoke-" + slot + ".log")).write_text(smoke.stdout + smoke.stderr)
            print(slot, "BROWSER_OK" if smoke.returncode == 0 else "BROWSER_FAILED", flush=True)

if __name__ == "__main__":
    run()