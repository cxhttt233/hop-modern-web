#!/usr/bin/env bash
# Official Hop 2.20 FILE_EXISTS plugin producer and independent native .hwf parse.
# Does not install the plugin into the shared 8080 runtime or execute Workflow.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
JAR="${HMW_FILE_EXISTS_JAR:-/home/ubuntu/hmw-agent-lab/vendor/action-plugin-proof/hop-action-fileexists-2.20.0.jar}"
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime/server}"
[[ -f "$JAR" ]] || { echo "Missing official Hop 2.20 FILE_EXISTS JAR: $JAR" >&2; exit 69; }
printf '%s  %s\n' 66d423e8832af84651e90a990c378270ed6a1e2f6bbac36a4a51cf2a52b1ea78 "$JAR" | sha256sum -c --status || {
  echo "FILE_EXISTS official Maven artifact checksum mismatch" >&2; exit 65;
}
TMP=$(mktemp -d /tmp/hmw-native-fileexists-proof-XXXXXXXX)
trap 'rm -rf "$TMP"' EXIT
javac --release 21 -proc:none -cp "$JAR:$RUNTIME/lib/*" -d "$TMP" "$ROOT/ops/tencent-preview/NativeFileExistsArtifactProof.java"
java -Xmx128m -cp "$TMP:$JAR:$RUNTIME/lib/*" NativeFileExistsArtifactProof "$TMP/fileexists.hwf"
test -s "$TMP/fileexists.hwf"
echo 'T2_VERIFIED_FILE_EXISTS_ARTIFACT_HOP_NATIVE_HWF_PASS'
