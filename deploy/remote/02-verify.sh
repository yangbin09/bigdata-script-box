#!/usr/bin/env bash
# 校验刚上传的 *.new 与本地构建产物逐字节一致（SHA256），并修正属主。
set -u
APP="__APPDIR__"
RUNAS="__RUNAS__"
EXPECT="__HASH__"
JAR="__JAR__"
NEW="$APP/target/$JAR.new"

ACT=$(sha256sum "$NEW" | cut -d' ' -f1 | tr 'A-Z' 'a-z')
if [ "$ACT" != "$EXPECT" ]; then
  echo "HASH_MISMATCH actual=$ACT expect=$EXPECT"
  exit 1
fi

chown "$RUNAS:$RUNAS" "$NEW"
echo "远端 SHA256 校验通过: $ACT"
