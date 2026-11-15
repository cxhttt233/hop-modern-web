#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME=/home/ubuntu/hop-modern-preview/runtime/server
JAR=/home/ubuntu/hmw-agent-lab/vendor/action-plugin-proof/hop-action-fileexists-2.20.0.jar
WORKSPACE="${HMW_ISOLATED_WORKSPACE:?requires isolated Hop JVM}"
UUID=$(python3 -c 'import uuid; print(uuid.uuid4())')
NAME=t2-fileexists-$UUID.hwf
FILE=$WORKSPACE/$NAME
TMP=$(mktemp -d /tmp/hmw-t2-fileexists-browser-XXXXXXXX)
trap 'rm -f "$FILE"; rm -rf "$TMP"' EXIT
T2_KEEP_FIXTURE=1 T2_FIXTURE_BASENAME="$NAME" \
  node "$ROOT/e2e/t2-fileexists-browser-save-retry.mjs"
test -s "$FILE"
javac --release 21 -proc:none -cp "$JAR:$RUNTIME/lib/*" -d "$TMP" \
  "$ROOT/ops/tencent-preview/NativeFileExistsApiParse.java"
java -Xmx128m -cp "$TMP:$JAR:$RUNTIME/lib/*" NativeFileExistsApiParse "$FILE"
echo T2_FILE_EXISTS_BROWSER_AND_JAVA_503_RECOVERY_PASS
