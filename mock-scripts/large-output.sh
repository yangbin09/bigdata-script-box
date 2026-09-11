#!/usr/bin/env bash
# Mock large-output script. Generates a lot of lines so we can validate that
# stdout/stderr drains do not deadlock.
LINES=${1:-2000}
# but our executor passes LINES via --lines; accept both forms
for arg in "$@"; do
  case "$arg" in
    --lines) shift; LINES="$1"; shift;;
    *) shift;;
  esac
done
echo "producing $LINES lines…"
i=0
while [ "$i" -lt "$LINES" ]; do
  i=$((i+1))
  echo "line $i: lorem ipsum dolor sit amet consectetur adipiscing elit"
done
echo "done"
exit 0