# Tencent Cloud shared HMW preview refresh

Version-controlled copy of the tested server refresh script.

- Runtime: `/home/ubuntu/hmw-agent-lab/bin/auto-refresh.py`.
- Run: `python3 /home/ubuntu/hmw-agent-lab/bin/auto-refresh.py` (the existing `hmw-auto-refresh.timer` also runs it).
- Shared SPA preview slots: local `http://127.0.0.1:4196/t1/` through `/t5/`; public HTTPS `https://43.142.133.101:8093/t1/` through `/t5/` requires existing authorization. Port 8092 is not required.
- Prerequisites on the server: existing Git clone in `hmw-agent-lab/source`, `bin/preview-branch`, `bin/backend-refresh`, `bin/smoke-product.mjs`, systemd services, and secrets kept **outside** this repository.
- Rather than fetching every remote Git head each time, `ls-remote --heads` checks inexpensive SHA references and `fetch` downloads only changed heads. If GitHub fails, cached refs and last known good previews remain available. Deployment of a changed preview is followed by Chromium smoke.
- VM has limited RAM: previews build serially under a shared lock. Keep at least 4 GiB free for builds.
- Validate with `python3 -m py_compile ops/tencent-preview/auto-refresh.py` and check service logs after the next run. Browser smoke must be run against all five slots before claiming deployment is healthy.

This file is documentation/source control for the server script, not a substitute for existing authentication or privileged backend activation. Update server deployment deliberately when changing it.

## Compile a Java branch without Maven SNAPSHOT resolution

`/home/ubuntu/hmw-agent-lab/bin/check-java-branch <branch>` uses the currently installed Java 21 runtime and Hop jars to compile all `server/src/main/java` sources from an existing cached origin branch. It acquires the common build lock, uses a temporary directory, does **not** deploy or restart Java, and deletes compiled objects after checking. It is a compile check, not a replacement for JUnit, integration/security review, or actual HTTP browser tests. Run `preview-branch` and focused API tests after it passes. On 2026-11-14 both `main` and `t1/catalog-bounded-scan-20261114` compiled successfully (47 and 46 `.class` files).

## Validate a PR's real Java HTTP endpoints without touching the shared backend

On the Tencent preview VM, `/home/ubuntu/hmw-agent-lab/bin/with-isolated-java-branch <GitHub-branch> <command> [args...]` fetches only that branch (with a warned cached-ref fallback), compiles its Java sources using installed Hop JARs, then launches a temporary loopback-only JVM at `127.0.0.1:8084` (or `HMW_TEST_PORT=8084..8089`). It sets:

- `HMW_ISOLATED_URL` to the ephemeral HTTP base URL.
- `HMW_ISOLATED_WORKSPACE` to a disposable, empty storage directory.
- `HMW_ISOLATED_TOKEN` to a **test-only token**, never a real credential.

Run a real test command inside the runner; it returns the command's exit code and removes the Java process, compiled classes, and temporary workspace on exit. It uses one shared build lock and a per-port lock. Do not expose the loopback port publicly. This is *isolated integration testing*, not a replacement for authenticated production API checks.

Examples:

```sh
/home/ubuntu/hmw-agent-lab/bin/with-isolated-java-branch t2/action-rename-20261114 python3 path/to/test-workflow-rename.py
/home/ubuntu/hmw-agent-lab/bin/with-isolated-java-branch t1/database-connections-20261114 python3 path/to/test-connections.py
```

The T2 #133 native action rename was verified by a disposable server test: rename, duplicate-name HTTP 409, preserving hop references, save/reopen original Hop XML. The T1 #132 backend was found to **fail JVM startup** due to request-scoped `@HeaderParam` field injection on a Jersey singleton; corrected to method-parameter injection. T1 now boots, and isolated tests verified no/wrong-token 403, create/read/list 200, bad name 400 and password-free file storage. T1 is **still Draft** until it uses Hop DatabaseMeta and an authenticated React user path.
