#!/usr/bin/env bash
# Real T4 browser edits the Hop Java-produced ExecSql .hpl; independent Hop Java parses the save.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime/server}"
WORKSPACE="${T4_TEST_WORKSPACE:-/home/ubuntu/hmw-agent-lab/workspaces/t4}"
UUID=$(python3 -c 'import uuid; print(uuid.uuid4())')
BASENAME="execsql-sql-$UUID.hpl"
FIXTURE="$WORKSPACE/$BASENAME"
TMP=$(mktemp -d /tmp/hmw-t4-execsql-native-XXXXXXXX)
trap 'rm -f "$FIXTURE"; rm -rf "$TMP"' EXIT
T4_KEEP_FIXTURE=1 T4_FIXTURE_BASENAME="$BASENAME" T4_TEST_WORKSPACE="$WORKSPACE" \
  node "$ROOT/e2e/t4-execsql-sql-workbench-roundtrip.mjs"
test -s "$FIXTURE"
javac -proc:none --release 21 -cp "$RUNTIME/lib/*" -d "$TMP" \
  "$ROOT/ops/tencent-preview/NativeExecSqlHplProof.java"
java -Xmx128m -cp "$TMP:$RUNTIME/lib/*" NativeExecSqlHplProof "$FIXTURE"
echo 'T4_EXECSQL_MONACO_BROWSER_AND_NATIVE_HOP_PARSE_PASS'
