#!/usr/bin/env bash
# Mock failure script. Writes to stderr and exits 1.
set -e
echo "step 1 ok"
echo "step 2 ok"
echo "ERROR: simulated failure on stderr" 1>&2
echo "ERROR: stack trace line 1" 1>&2
echo "ERROR: stack trace line 2" 1>&2
exit 1