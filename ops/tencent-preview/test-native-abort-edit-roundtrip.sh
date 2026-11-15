#!/usr/bin/env bash
# T2 real browser native ABORT edit -> save failure/retry -> independent Hop Java .hwf parse.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime/server}"
WORKSPACE="${HMW_ISOLATED_WORKSPACE:-${T2_TEST_WORKSPACE:-/home/ubuntu/hmw-agent-lab/workspaces/t2}}"
ABORT_JAR="/home/ubuntu/hmw-agent-lab/vendor/action-plugin-proof/hop-action-abort-2.20.0.jar"
UUID=$(python3 -c 'import uuid; print(uuid.uuid4())')
BASENAME="native-abort-edit-$UUID.hwf"
FIXTURE="$WORKSPACE/$BASENAME"
TMP=$(mktemp -d /tmp/hmw-t2-native-abort-edit-XXXXXXXX)
trap 'rm -f "$FIXTURE"; rm -rf "$TMP"' EXIT
T2_KEEP_FIXTURE=1 T2_FIXTURE_BASENAME="$BASENAME" node "$ROOT/e2e/t2-native-abort-edit-roundtrip.mjs"
test -s "$FIXTURE"
javac -proc:none --release 21 -cp "$RUNTIME/lib/*:$ABORT_JAR" -d "$TMP" \
  "$ROOT/ops/tencent-preview/NativeWorkflowAbortEditProof.java"
java -Xmx128m -cp "$TMP:$ABORT_JAR:$RUNTIME/lib/*" NativeWorkflowAbortEditProof "$FIXTURE"
echo 'T2_BROWSER_NATIVE_ABORT_EDIT_AND_HOP_JAVA_ROUNDTRIP_PASS'
