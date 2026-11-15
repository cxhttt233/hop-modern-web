#!/usr/bin/env bash
# External T5 inventory, not a T3 generator implementation or UI acceptance.
# Reads only runtime plugin registry + SWT-free Hop metadata annotations; never
# executes workflows, opens external connections, or changes project documents.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LAB="${HMW_AGENT_LAB:-/home/ubuntu/hmw-agent-lab}"
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime}"
BACKEND_CLASSES="${HMW_BACKEND_CLASSES:-$LAB/backend-main/current/classes}"
[[ -f "$BACKEND_CLASSES/org/apache/hop/modern/web/document/AutomaticConfigDescriptor.class" ]] || {
  echo "Missing runtime FormSchema descriptor class" >&2; exit 69;
}
TMP=$(mktemp -d /tmp/hmw-schema-audit-XXXXXXXX)
trap 'rm -rf "$TMP"' EXIT
javac --release 21 -proc:none \
 -cp "$BACKEND_CLASSES:$RUNTIME/server/classes:$RUNTIME/server/lib/*" \
 -d "$TMP" "$ROOT/ops/tencent-preview/NativeSchemaCoverageProbe.java"
timeout 90s java -Xmx300m -cp "$TMP:$BACKEND_CLASSES:$RUNTIME/server/classes:$RUNTIME/server/lib/*" \
 NativeSchemaCoverageProbe > "$TMP/raw.csv" 2> "$TMP/summary.log"
# Hop's own config warning can appear on stdout before the first CSV header.
grep -E '^(category,|Transform,|Action,)' "$TMP/raw.csv" > "$TMP/coverage.csv"
head -1 "$TMP/coverage.csv" | grep -q '^category,id,'
TRANSFORMS=$(grep -c '^Transform,' "$TMP/coverage.csv" || true)
ACTIONS=$(grep -c '^Action,' "$TMP/coverage.csv" || true)
[[ "$TRANSFORMS" -ge 20 && "$ACTIONS" -ge 4 ]] || {
  echo "Incomplete runtime plugin discovery Transform=$TRANSFORMS Action=$ACTIONS" >&2; exit 70;
}
cat "$TMP/summary.log"
echo "T5_NATIVE_REGISTRY_INVENTORY transforms=$TRANSFORMS actions=$ACTIONS"
if [[ -n "${HMW_SCHEMA_COVERAGE_OUT:-}" ]]; then
  cp -- "$TMP/coverage.csv" "$HMW_SCHEMA_COVERAGE_OUT"
  echo "T5_COVERAGE_CSV=$HMW_SCHEMA_COVERAGE_OUT"
fi
echo T5_READ_ONLY_TRANSFORM_ACTION_SCHEMA_COVERAGE_PASS
