#!/usr/bin/env bash
# Verify DUMMY + official ABORT using an isolated Hop JVM or the explicit shared backend.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime/server}"
WORKSPACE="${HMW_ISOLATED_WORKSPACE:?set workspace or use with-isolated-java-branch}"
ABORT_JAR="/home/ubuntu/hmw-agent-lab/vendor/action-plugin-proof/hop-action-abort-2.20.0.jar"
UUID=$(python3 -c 'import uuid; print(uuid.uuid4())')
BASENAME="native-ui-action-$UUID.hwf"
FIXTURE="$WORKSPACE/$BASENAME"
TMP=$(mktemp -d /tmp/hmw-t2-abort-proof-XXXXXXXX)
trap 'rm -f "$FIXTURE"; rm -rf "$TMP"' EXIT
if [[ "${T2_LIVE_SHARED_BACKEND:-0}" != "1" ]]; then
  python3 "$ROOT/ops/tencent-preview/test-native-action-add-http.py"
fi
T2_FIXTURE_BASENAME="$BASENAME" T2_KEEP_FIXTURE=1 node "$ROOT/e2e/t2-native-action-add-isolated.mjs"
test -s "$FIXTURE"
javac -proc:none --release 21 -cp "$RUNTIME/lib/*:$ABORT_JAR" -d "$TMP" \
  "$ROOT/ops/tencent-preview/NativeWorkflowActionProof.java"
java -Xmx128m -cp "$TMP:$ABORT_JAR:$RUNTIME/lib/*" NativeWorkflowActionProof "$FIXTURE"
echo "T2_BROWSER_AND_NATIVE_HOP_ABORT_ROUNDTRIP_PASS"
