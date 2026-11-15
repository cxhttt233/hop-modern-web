#!/usr/bin/env bash
# Proves UI -> real workflow API -> .hwf disk -> independent native Hop ActionSuccess parser.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime/server}"
WORKSPACE="${HMW_ISOLATED_WORKSPACE:?Run via with-isolated-java-branch}"
JAR="/home/ubuntu/hmw-agent-lab/vendor/action-plugin-proof/hop-action-success-2.20.0.jar"
UUID=$(python3 -c 'import uuid; print(uuid.uuid4())')
BASENAME="native-success-action-$UUID.hwf"
FIXTURE="$WORKSPACE/$BASENAME"
TMP=$(mktemp -d /tmp/hmw-t2-success-native-XXXXXXXX)
trap 'rm -f "$FIXTURE"; rm -rf "$TMP"' EXIT
T2_KEEP_FIXTURE=1 T2_FIXTURE_BASENAME="$BASENAME" node "$ROOT/e2e/t2-native-success-roundtrip.mjs"
test -s "$FIXTURE"
javac -proc:none --release 21 -cp "$RUNTIME/lib/*:$JAR" -d "$TMP" \
  "$ROOT/ops/tencent-preview/NativeWorkflowSuccessProof.java"
java -Xmx128m -cp "$TMP:$JAR:$RUNTIME/lib/*" NativeWorkflowSuccessProof "$FIXTURE"
echo 'T2_SUCCESS_UI_SAVE_FAIL_RETRY_AND_HOP_NATIVE_PARSE_PASS'
