#!/usr/bin/env bash
# Xperia Dialer - build + install + launch (Git Bash)
# 注意：PATH 里的 Android SDK 必须用 /c/... 风格，用 C:/... 形式 Git Bash 认不出来
set -e

export JAVA_HOME="C:/Program Files/Java/oracleJdk-21"
export GRADLE_USER_HOME="C:/Users/wangj/.gradle"
SDK="/c/Users/wangj/.android-sdk"
ADB="$SDK/platform-tools/adb.exe"
export PATH="$JAVA_HOME/bin:$SDK/platform-tools:$PATH"
PKG=com.wangj.xperiadialer

cd "$(dirname "$0")"

echo "==> [1/4] 检查设备"
if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "    未检测到设备：请插 USB、开启 USB 调试、并在手机上允许调试授权"
  exit 1
fi
echo "    OK: $("$ADB" shell getprop ro.product.model | tr -d '\r')"

echo "==> [2/4] 编译"
./gradlew assembleDebug --init-script ../.build/mirrors.gradle --console=plain

echo "==> [3/4] 安装"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk

echo "==> [4/4] 启动"
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
echo "==> 完成。看日志：./logs.sh"
