#!/usr/bin/env bash
# Browser writes a UUID native .hpl; Apache Hop Java independently parses the exact file.
# Run on Tencent Cloud after deploying this branch's T3 preview.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime/server}"
WORKSPACE="${T3_TEST_WORKSPACE:-/home/ubuntu/hmw-agent-lab/workspaces/t3}"
UUID=$(python3 -c 'import uuid; print(uuid.uuid4())')
BASENAME="filterrows-composite-$UUID.hpl"
FIXTURE="$WORKSPACE/$BASENAME"
TMP=$(mktemp -d /tmp/hmw-t3-native-roundtrip-XXXXXXXX)
trap 'rm -f "$FIXTURE"; rm -rf "$TMP"' EXIT
T3_KEEP_FIXTURE=1 T3_FIXTURE_BASENAME="$BASENAME" T3_TEST_WORKSPACE="$WORKSPACE" \
  node "$ROOT/e2e/t3-filterrows-composite-roundtrip.mjs" | tee "$TMP/browser.log"
test -s "$FIXTURE"
javac -proc:none --release 21 -cp "$RUNTIME/lib/*" -d "$TMP" \
  "$ROOT/ops/tencent-preview/NativeFilterRowsHplProof.java"
java -Xmx128m -cp "$TMP:$RUNTIME/lib/*" NativeFilterRowsHplProof "$FIXTURE"
echo "T3_BROWSER_AND_NATIVE_HOP_JAVA_ROUNDTRIP_PASS"
