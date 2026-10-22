#!/usr/bin/env bash
# 通话链路验证脚本：清日志 → 等通话结束 → 打印前台服务/通知全过程 + 崩溃检查
#
# 用法：
#   1. 优先用 logs-call.cmd（纯 cmd 实现，不依赖 bash 的 read）
#   2. 或者直接 bash logs-call.sh
#   3. 脚本清空日志后，在手机上打一通电话（对方接听，通话 30 秒以上）
#   4. 挂断后回到电脑按回车，脚本输出全过程
#
# 注意：不要用 set -e。之前就是因为 read 在非交互 stdin 下返回 EOF，
# set -e 让脚本在 read 处直接退出，logcat 一行都没跑，表现为"窗口里什么也没有"。
set -u

SDK="/c/Users/wangj/.android-sdk"
ADB="$SDK/platform-tools/adb.exe"
PKG=com.wangj.xperiadialer

"$ADB" get-state >/dev/null 2>&1 || { echo "未检测到设备"; exit 1; }

"$ADB" logcat -c || { echo "清空日志失败"; exit 1; }
echo "==> 日志已清空。现在请在手机上打一通电话（对方接听、通话 30 秒以上再挂断）"
echo "==> 挂断后回到这里按回车继续"
read -r _ || true

echo
echo "==================== 前台服务 / 通知链路 ===================="
"$ADB" logcat -d -v time -s CallForegroundService CallNotif CallManager \
  CallScreeningService InCallServiceImpl DialerApp 2>/dev/null | tr -d '\r' | tail -80

echo
echo "==================== 崩溃检查 ===================="
CRASH=$("$ADB" logcat -b crash -d -v time 2>/dev/null | tr -d '\r' | tail -40 || true)
if [ -z "$CRASH" ]; then
  echo "无崩溃记录 OK"
else
  echo "$CRASH"
fi

echo
echo "==================== 前台服务状态 ===================="
"$ADB" shell dumpsys activity services "$PKG" 2>/dev/null | tr -d '\r' \
  | grep -E "ServiceRecord|isForeground" || echo "（空 = 服务已停止，通话结束后属于正常）"

echo
echo "==================== 通知残留检查 ===================="
"$ADB" shell dumpsys notification --noredact 2>/dev/null | tr -d '\r' \
  | grep -oE "id=200[12].*channel=[a-z_]+" | head -4 || true
echo "（通话结束后这里应该是空的）"
