#!/usr/bin/env bash
# 停旧进程 -> 替换 jar -> 以 RUNAS 用户重启 -> 健康检查。
# 健康检查失败时自动还原旧 jar 并重启（回滚），并以非零码退出。
set -u
APP="__APPDIR__"
RUNAS="__RUNAS__"
JAVA="__JAVABIN__"
PORT="__PORT__"
SHELLX="__SHELL__"
JAR="__JAR__"
TIMEOUT="__TIMEOUT__"

TS="$(cat /tmp/scriptbox-deploy-ts 2>/dev/null || date +%Y%m%d-%H%M%S)"
cd "$APP" || exit 1

# 注意：这里刻意让 `&` 只作用于 nohup 命令本身（不在前面拼 cd），
# 否则 `cd APP && nohup ... &` 会 fork 出一个子壳持有 SSH 通道，
# 导致 ssh 会话挂住不返回，且 $! 记到的是子壳而非 java 的 PID。
start_app() {
  cd "$APP" || return 1
  sudo -u "$RUNAS" bash -c "nohup $JAVA -jar target/$JAR --server.port=$PORT --scriptbox.shell-executable=$SHELLX >> script-box.out 2>&1 < /dev/null & echo \$! > script-box.pid"
  sleep 2
  chown "$RUNAS:$RUNAS" script-box.pid 2>/dev/null
}

wait_healthy() {
  i=0
  while [ "$i" -lt "$TIMEOUT" ]; do
    CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$PORT/api/system/info" 2>/dev/null)
    [ "$CODE" = "200" ] && return 0
    sleep 3
    i=$((i + 3))
  done
  return 1
}

OLD_PID=$(cat script-box.pid 2>/dev/null)
if [ -n "$OLD_PID" ]; then
  echo "停止旧进程 PID=$OLD_PID"
  kill -TERM "$OLD_PID" 2>/dev/null
  n=0
  while kill -0 "$OLD_PID" 2>/dev/null && [ "$n" -lt 30 ]; do
    sleep 1
    n=$((n + 1))
  done
  if kill -0 "$OLD_PID" 2>/dev/null; then
    echo "旧进程未退出，强制 kill -9"
    kill -9 "$OLD_PID"
    sleep 3
  fi
fi

mv "target/$JAR" "target/$JAR.replaced-$TS"
mv "target/$JAR.new" "target/$JAR"
chown "$RUNAS:$RUNAS" "target/$JAR"
echo "jar 已替换 -> $(stat -c%s "target/$JAR") bytes"

echo "启动新版本 ..."
start_app

if wait_healthy; then
  echo "HEALTH_OK"
  JPID=$(ss -lntp 2>/dev/null | grep ":$PORT " | grep -o 'pid=[0-9]*' | cut -d= -f2 | head -1)
  if [ -n "$JPID" ]; then
    echo "$JPID" > script-box.pid
    chown "$RUNAS:$RUNAS" script-box.pid
  fi
  echo "新 PID: $(cat script-box.pid)"
else
  echo "HEALTH_FAIL"
  NP=$(cat script-box.pid 2>/dev/null)
  [ -n "$NP" ] && kill -9 "$NP" 2>/dev/null
  sleep 2
  mv "target/$JAR" "target/$JAR.failed-$TS"
  mv "target/$JAR.replaced-$TS" "target/$JAR"
  chown "$RUNAS:$RUNAS" "target/$JAR"
  start_app
  if wait_healthy; then
    echo "ROLLBACK_OK"
  else
    echo "ROLLBACK_FAIL"
  fi
  echo "--- 失败版本输出尾部 ---"
  tail -30 script-box.out
  exit 1
fi

echo
echo "--- 进程 ---"
ps -o pid,user,etime,cmd -p "$(cat script-box.pid)" 2>/dev/null
echo "--- 端口 ---"
ss -lntp 2>/dev/null | grep ":$PORT "
echo "--- 接口 ---"
echo "/api/system/info : $(curl -s "http://127.0.0.1:$PORT/api/system/info")"
echo "首页主包         : $(curl -s "http://127.0.0.1:$PORT/" | grep -o 'assets/index-[A-Za-z0-9_-]*\.js' | head -1)"
echo "scripts 条数     : $(curl -s "http://127.0.0.1:$PORT/api/scripts" | grep -o '"id":' | wc -l)"
echo "scripts 哈希     : $(curl -s "http://127.0.0.1:$PORT/api/scripts" | sha256sum | cut -d' ' -f1)"
echo "data 文件数      : $(find data -type f 2>/dev/null | wc -l)"
