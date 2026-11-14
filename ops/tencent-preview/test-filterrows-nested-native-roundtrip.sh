#!/usr/bin/env bash
# Browser creates and saves nested FilterRows; independent Hop Java parses the same XML.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime/server}"
WORKSPACE="${T3_TEST_WORKSPACE:-/home/ubuntu/hmw-agent-lab/workspaces/t3}"
UUID=$(python3 -c 'import uuid; print(uuid.uuid4())')
BASENAME="filterrows-nested-$UUID.hpl"
FIXTURE="$WORKSPACE/$BASENAME"
TMP=$(mktemp -d /tmp/hmw-t3-nested-java-XXXXXXXX)
trap 'rm -f "$FIXTURE"; rm -rf "$TMP"' EXIT
T3_KEEP_FIXTURE=1 T3_FIXTURE_BASENAME="$BASENAME" T3_TEST_WORKSPACE="$WORKSPACE" \
  node "$ROOT/e2e/t3-filterrows-nested-preservation.mjs" | tee "$TMP/browser.log"
test -s "$FIXTURE"
javac -proc:none --release 21 -cp "$RUNTIME/lib/*" -d "$TMP" \
  "$ROOT/ops/tencent-preview/NativeNestedFilterRowsHplProof.java"
java -Xmx128m -cp "$TMP:$RUNTIME/lib/*" NativeNestedFilterRowsHplProof "$FIXTURE"
echo T3_NESTED_BROWSER_AND_NATIVE_JAVA_ROUNDTRIP_PASS
