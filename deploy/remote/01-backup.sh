#!/usr/bin/env bash
# 备份远端现有 jar 与 data/ 目录（data 含 H2 库、脚本、keytab、执行记录）。
set -u
APP="__APPDIR__"
RUNAS="__RUNAS__"
TS="__STAMP__"

cd "$APP" || exit 1

cp -a target/script-box.jar "/home/$RUNAS/backup-script-box.jar.$TS" \
  && echo "jar  -> /home/$RUNAS/backup-script-box.jar.$TS"

tar czf "/home/$RUNAS/data-backup-$TS.tar.gz" data \
  && echo "data -> /home/$RUNAS/data-backup-$TS.tar.gz"
