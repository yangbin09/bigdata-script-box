#!/usr/bin/env bash
# Mock mixed output script. Uses --message arg, prints to both stdout and stderr.
MSG=""
for arg in "$@"; do
  case "$arg" in
    --message) shift; MSG="$1"; shift;;
    *) shift;;
  esac
done
echo "[stdout] hello ${MSG:-world}"
echo "[stderr] warning: ${MSG:-world}" 1>&2
echo "[stdout] more data"
echo "[stderr] another warning" 1>&2
exit 0