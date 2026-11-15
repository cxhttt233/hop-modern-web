#!/usr/bin/env bash
# Browser edits both InsertUpdate lists in a Hop Java-produced .hpl; Java independently reparses.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime/server}"
WORKSPACE="${HMW_ISOLATED_WORKSPACE:-${T3_TEST_WORKSPACE:-/home/ubuntu/hmw-agent-lab/workspaces/t3}}"
UUID=$(python3 -c 'import uuid; print(uuid.uuid4())')
BASENAME="insertupdate-edit-$UUID.hpl"
FILE="$WORKSPACE/$BASENAME"
TMP=$(mktemp -d /tmp/hmw-t3-insertupdate-check-XXXXXXXX)
trap 'rm -f "$FILE"; rm -rf "$TMP"' EXIT
T3_KEEP_FIXTURE=1 T3_FIXTURE_BASENAME="$BASENAME" T3_TEST_WORKSPACE="$WORKSPACE" \
  node "$ROOT/e2e/t3-insertupdate-native-mapping-roundtrip.mjs"
test -s "$FILE"
javac -proc:none --release 21 -cp "$RUNTIME/lib/*" -d "$TMP" \
  "$ROOT/ops/tencent-preview/NativeInsertUpdateMappingHplProof.java"
java -Xmx128m -cp "$TMP:$RUNTIME/lib/*" NativeInsertUpdateMappingHplProof "$FILE"
echo 'T3_BROWSER_AND_NATIVE_HOP_INSERTUPDATE_MAPPING_PASS'
