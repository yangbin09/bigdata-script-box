#!/usr/bin/env bash
# Mock: WARNING status with data.
echo "[result-json-warning] running"
cat > result.json <<'JSON'
{
  "status": "WARNING",
  "message": "completed with warnings",
  "data": { "warnings": 3 }
}
JSON
exit 0