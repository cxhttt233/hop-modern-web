#!/usr/bin/env bash
# T5 external, executable real-browser native .hpl batch acceptance.
# Generates fresh Hop Java .hpl files; never executes Transform SQL, HTTP or JavaScript.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime}"
TMP=$(mktemp -d /tmp/hmw-t5-batch-native-XXXXXXXX)
trap 'rm -rf "$TMP"' EXIT
javac --release 21 -proc:none -cp "$RUNTIME/server/lib/*" -d "$TMP" \
  "$ROOT/ops/tencent-preview/NativeBatchTransformWitnessProducer.java"
java -Xmx300m -cp "$TMP:$RUNTIME/server/lib/*" \
  NativeBatchTransformWitnessProducer "$TMP"
T5_NATIVE_BATCH_FIXTURES="$TMP" \
  node "$ROOT/e2e/t5-native-batch-sample.mjs"
echo T5_JAVA_PRODUCER_AND_BROWSER_NATIVE_BATCH_10_PASS
