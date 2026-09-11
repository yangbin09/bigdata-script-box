#!/usr/bin/env bash
# Mock: print the received file paths so the test can verify they point at the
# correct server-controlled location.
echo "[file-input] start"
SQL_FILE=""
CSV_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --sqlFile) SQL_FILE="$2"; shift 2;;
    --csvFile) CSV_FILE="$2"; shift 2;;
    *) shift;;
  esac
done
echo "[file-input] sqlFile = ${SQL_FILE:-<none>}"
echo "[file-input] csvFile = ${CSV_FILE:-<none>}"
if [ -n "$SQL_FILE" ] && [ -f "$SQL_FILE" ]; then
  echo "[file-input] sqlFile exists, first line:"
  head -1 "$SQL_FILE"
fi
echo "[file-input] done"
exit 0