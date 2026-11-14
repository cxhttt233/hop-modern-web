#!/usr/bin/env bash
# Browser edits a UUID native TableInput .hpl; Apache Hop Java independently parses the same disk file.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime/server}"
WORKSPACE="${T4_TEST_WORKSPACE:-/home/ubuntu/hmw-agent-lab/workspaces/t4}"
UUID=$(python3 -c 'import uuid; print(uuid.uuid4())')
BASENAME="table-input-sql-$UUID.hpl"
FIXTURE="$WORKSPACE/$BASENAME"
TMP=$(mktemp -d /tmp/hmw-t4-native-sql-XXXXXXXX)
trap 'rm -f "$FIXTURE"; rm -rf "$TMP"' EXIT
T4_KEEP_FIXTURE=1 T4_FIXTURE_BASENAME="$BASENAME" T4_TEST_WORKSPACE="$WORKSPACE" \
  node "$ROOT/e2e/t4-tableinput-sql-workbench-roundtrip.mjs" | tee "$TMP/browser.log"
test -s "$FIXTURE"
javac -proc:none --release 21 -cp "$RUNTIME/lib/*" -d "$TMP" \
  "$ROOT/ops/tencent-preview/NativeTableInputSqlHplProof.java"
java -Xmx128m -cp "$TMP:$RUNTIME/lib/*" NativeTableInputSqlHplProof "$FIXTURE"
echo "T4_BROWSER_AND_NATIVE_HOP_JAVA_SQL_ROUNDTRIP_PASS"
