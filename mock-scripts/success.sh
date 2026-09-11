#!/usr/bin/env bash
# Mock success script. Accepts --name <value> and echoes greeting.
NAME=""
COUNT=0
i=0
while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="$2"; shift 2;;
    --count) COUNT="$2"; shift 2;;
    *) shift;;
  esac
done
echo "[success] script started"
echo "[success] name = ${NAME:-world}"
echo "[success] pid  = $$"
while [ "$i" -lt "${COUNT:-0}" ]; do
  i=$((i+1))
  echo "[success] tick $i"
done
echo "[success] script finished"
exit 0