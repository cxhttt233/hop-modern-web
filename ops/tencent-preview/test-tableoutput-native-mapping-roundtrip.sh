#!/usr/bin/env bash
# Browser native TableOutput list edit/cancel/save -> real .hpl -> independent Hop Java parse.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime/server}"
WORKSPACE="${HMW_ISOLATED_WORKSPACE:-${T3_TEST_WORKSPACE:-/home/ubuntu/hmw-agent-lab/workspaces/t3}}"
UUID=$(python3 -c 'import uuid; print(uuid.uuid4())')
BASENAME="tableoutput-mapping-$UUID.hpl"
FIXTURE="$WORKSPACE/$BASENAME"
TMP=$(mktemp -d /tmp/hmw-t3-tableoutput-native-XXXXXXXX)
trap 'rm -f "$FIXTURE"; rm -rf "$TMP"' EXIT
T3_KEEP_FIXTURE=1 T3_FIXTURE_BASENAME="$BASENAME" T3_TEST_WORKSPACE="$WORKSPACE" \
  node "$ROOT/e2e/t3-tableoutput-native-mapping-roundtrip.mjs"
test -s "$FIXTURE"
javac -proc:none --release 21 -cp "$RUNTIME/lib/*" -d "$TMP" \
  "$ROOT/ops/tencent-preview/NativeTableOutputMappingHplProof.java"
java -Xmx128m -cp "$TMP:$RUNTIME/lib/*" NativeTableOutputMappingHplProof "$FIXTURE"
echo 'T3_TABLEOUTPUT_BROWSER_AND_NATIVE_HOP_MAPPING_ROUNDTRIP_PASS'
