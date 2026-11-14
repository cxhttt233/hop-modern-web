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
