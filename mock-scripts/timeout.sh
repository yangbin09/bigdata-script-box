#!/usr/bin/env bash
# Mock timeout script. Sleeps forever to trigger ProcessBuilder timeout.
echo "starting long sleep"
for i in 1 2 3 4 5 6 7 8 9 10; do
  echo "tick $i"
  sleep 5
done
exit 0