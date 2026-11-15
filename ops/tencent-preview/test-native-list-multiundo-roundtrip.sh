#!/usr/bin/env bash
# Strictly disposable UUID test files; browser -> Hop Java readback for two native transforms.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime/server}"
WORKSPACE="${T4_TEST_WORKSPACE:-/home/ubuntu/hmw-agent-lab/workspaces/t4}"
TMP=$(mktemp -d /tmp/hmw-native-multiundo-XXXXXXXX)
FIRST=""
SECOND=""
trap '[[ -z "$FIRST" ]] || rm -f "$FIRST"; [[ -z "$SECOND" ]] || rm -f "$SECOND"; rm -rf "$TMP"' EXIT
T4_KEEP_FIXTURE=1 T4_TEST_WORKSPACE="$WORKSPACE" \
  node "$ROOT/e2e/t4-native-list-multiundo-roundtrip.mjs" | tee "$TMP/browser.log"
mapfile -t saved < <(grep '^T4_UNDO_SAVED_FILE=' "$TMP/browser.log" | cut -d= -f2-)
[[ "${#saved[@]}" -eq 2 ]] || { echo "Expected exactly two native saved HPL paths" >&2; exit 1; }
FIRST="${saved[0]}"
SECOND="${saved[1]}"
for file in "$FIRST" "$SECOND"; do
  [[ "$file" == "$WORKSPACE"/native-undo-*.hpl ]] ||
    { echo "Unsafe test path, refusing cleanup: $file" >&2; exit 1; }
  [[ -f "$file" && ! -L "$file" ]] || { echo "Missing native fixture: $file" >&2; exit 1; }
done
javac --release 21 -proc:none -cp "$RUNTIME/lib/*" -d "$TMP" \
  "$ROOT/ops/tencent-preview/NativeListUndoHplProof.java"
java -Xmx128m -cp "$TMP:$RUNTIME/lib/*" NativeListUndoHplProof "$FIRST" "$SECOND"
echo T4_NATIVE_LIST_MULTIUNDO_BROWSER_AND_HOP_JAVA_PASS
