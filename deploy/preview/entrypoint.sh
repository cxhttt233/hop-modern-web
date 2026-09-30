#!/bin/sh
set -eu

mkdir -p /workspace
if [ ! -f /workspace/real-graph-proof.hpl ]; then
  cp /opt/modern/fixtures/real-graph-proof.hpl /workspace/real-graph-proof.hpl
fi

java -cp "/opt/modern/server/classes:/opt/modern/server/lib/*" \
  org.apache.hop.modern.web.ModernWebServer \
  >/tmp/hop-modern-server.log 2>&1 &
SERVER_PID=$!

cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
}
trap cleanup INT TERM EXIT

ready=0
i=0
while [ "$i" -lt 60 ]; do
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    cat /tmp/hop-modern-server.log
    exit 1
  fi
  if curl -s -o /dev/null http://127.0.0.1:8080/api/pipelines/open; then
    ready=1
    break
  fi
  i=$((i + 1))
  sleep 1
done

if [ "$ready" -ne 1 ]; then
  cat /tmp/hop-modern-server.log
  exit 1
fi

exec nginx -g 'daemon off;'
