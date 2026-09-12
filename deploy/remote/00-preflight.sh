#!/usr/bin/env bash
# 部署预检：记录远端现状与数据基线。只读，不改动任何东西。
set -u
APP="__APPDIR__"
PORT="__PORT__"
JAVA="__JAVABIN__"

cd "$APP" || { echo "APPDIR_MISSING: $APP"; exit 1; }

echo "当前 PID 文件    : $(cat script-box.pid 2>/dev/null || echo '(无)')"
echo "当前 jar 大小    : $(stat -c%s target/script-box.jar 2>/dev/null || echo '?') bytes"
echo "scripts API 条数 : $(curl -s "http://127.0.0.1:$PORT/api/scripts" | grep -o '"id":' | wc -l)"
echo "scripts API 哈希 : $(curl -s "http://127.0.0.1:$PORT/api/scripts" | sha256sum | cut -d' ' -f1)"
echo "data 文件数      : $(find data -type f 2>/dev/null | wc -l)"
echo "首页主包         : $(curl -s "http://127.0.0.1:$PORT/" | grep -o 'assets/index-[A-Za-z0-9_-]*\.js' | head -1)"
if [ -x "$JAVA" ]; then echo "JAVA 可执行      : 存在 ($JAVA)"; else echo "JAVA 可执行      : 缺失 ($JAVA)"; fi
echo "磁盘可用         : $(df -h "$APP" | tail -1 | awk '{print $4}')"
