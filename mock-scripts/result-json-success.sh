#!/usr/bin/env bash
# Mock: write a well-formed result.json next to stdout/stderr so the platform
# can parse it. Also dump a human-readable line to stdout.
echo "[result-json] running"
cat > result.json <<'JSON'
{
  "status": "SUCCESS",
  "message": "Hudi 表检查完成",
  "data": {
    "table": "cobp_dwd.test_hudi_mor",
    "type": "MOR",
    "recordCount": 10,
    "partitionCount": 2,
    "hdfsExists": true
  }
}
JSON
exit 0