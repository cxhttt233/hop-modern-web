#!/usr/bin/env bash
# Read-only guard: only the versioned systemd JVM may own Hop Modern Web :8080.
# The legacy cron launcher may still be required for nginx and cloudflared.
set -euo pipefail
SERVICE="hmw-java-runtime.service"
LEGACY="/home/ubuntu/hop-modern-preview/runtime/launch.sh"
systemctl is-enabled --quiet "$SERVICE" || {
  echo "HMW_8080_OWNER_FAIL: versioned systemd service is not enabled" >&2; exit 68;
}
systemctl is-active --quiet "$SERVICE" || {
  echo "HMW_8080_OWNER_FAIL: versioned systemd service is not active" >&2; exit 69;
}
MAINPID=$(systemctl show "$SERVICE" -p MainPID --value)
[[ "$MAINPID" =~ ^[1-9][0-9]*$ ]] || {
  echo "HMW_8080_OWNER_FAIL: no systemd MainPID" >&2; exit 70;
}
PORT=$(ss -H -lntp '( sport = :8080 )' | grep -m1 'LISTEN' || true)
[[ -n "$PORT" ]] || {
  echo "HMW_8080_OWNER_FAIL: :8080 is not listening" >&2; exit 71;
}
LISTENER_PID=$(sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' <<< "$PORT")
[[ "$LISTENER_PID" == "$MAINPID" ]] || {
  echo "HMW_8080_OWNER_FAIL: :8080 pid=$LISTENER_PID differs from systemd pid=$MAINPID" >&2;
  exit 72;
}
if crontab -l -u ubuntu 2>/dev/null | grep -Fq "$LEGACY"; then
  if [[ ! -r "$LEGACY" ]] || ! grep -Fq 'JAVA_MANAGED_BY_SYSTEMD' "$LEGACY"; then
    echo "HMW_8080_OWNER_FAIL: cron legacy launcher can start competing Java JVM" >&2;
    exit 73;
  fi
fi
printf 'HMW_8080_SINGLE_OWNER_PASS pid=%s systemd=%s\n' "$MAINPID" "$SERVICE"