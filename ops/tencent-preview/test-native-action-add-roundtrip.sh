#!/usr/bin/env bash
# Verify DUMMY + official ABORT using the same isolated Hop JVM and real browser UI.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime/server}"
WORKSPACE="${HMW_ISOLATED_WORKSPACE:?run through with-isolated-java-branch}"
ABORT_JAR="/home/ubuntu/hmw-agent-lab/vendor/action-plugin-proof/hop-action-abort-2.20.0.jar"
TMP=$(mktemp -d /tmp/hmw-t2-abort-proof-XXXXXXXX)
trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/ops/tencent-preview/test-native-action-add-http.py"
node "$ROOT/e2e/t2-native-action-add-isolated.mjs"
test -s "$WORKSPACE/native-ui-action.hwf"
javac -proc:none --release 21 -cp "$RUNTIME/lib/*:$ABORT_JAR" -d "$TMP" \
  "$ROOT/ops/tencent-preview/NativeWorkflowActionProof.java"
java -Xmx128m -cp "$TMP:$ABORT_JAR:$RUNTIME/lib/*" NativeWorkflowActionProof \
  "$WORKSPACE/native-ui-action.hwf"
echo "T2_BROWSER_AND_NATIVE_HOP_ABORT_ROUNDTRIP_PASS"
