#!/usr/bin/env bash
# Xperia Dialer - 只看本 App 的日志
set -e

SDK="/c/Users/wangj/.android-sdk"
ADB="$SDK/platform-tools/adb.exe"
PKG=com.wangj.xperiadialer

"$ADB" get-state >/dev/null 2>&1 || { echo "未检测到设备"; exit 1; }

echo "==> 清空日志缓冲"
"$ADB" logcat -c

echo "==> 等待 App 启动（在手机上打开 Xperia Dialer）..."
PID=""
while [ -z "$PID" ]; do
  PID=$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r')
  [ -z "$PID" ] && sleep 1
done

echo "==> 已附加 pid=$PID，Ctrl+C 退出"
echo
"$ADB" logcat -v time --pid="$PID"
