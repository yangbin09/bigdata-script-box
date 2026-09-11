#!/usr/bin/env bash
# Mock: emit a malformed JSON so the platform should log "parse failed"
# but still surface stdout normally.
echo "[invalid-json] starting"
cat > result.json <<'JSON'
{ this is not valid JSON
JSON
exit 0