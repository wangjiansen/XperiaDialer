@echo off
rem Xperia Dialer - tail app logs only
chcp 65001 >nul
setlocal
set "ANDROID_HOME=C:\Users\wangj\.android-sdk"
set "PATH=%ANDROID_HOME%\platform-tools;%PATH%"
set "PKG=com.wangj.xperiadialer"

adb get-state >nul 2>&1
if errorlevel 1 (
  echo [FAIL] No device detected.
  pause
  exit /b 1
)

echo Clearing log buffer...
adb logcat -c

echo Waiting for %PKG% to start (launch it on the phone)...
:wait
set "PID="
for /f "delims=" %%i in ('adb shell pidof %PKG% 2^>nul') do set "PID=%%i"
if "%PID%"=="" (
  timeout /t 1 >nul
  goto wait
)

echo Attached to pid=%PID%. Press Ctrl+C to stop.
echo NOTE: if nothing shows up, the app is simply not logging anything.
echo       This app is quiet when idle - trigger a call or tap the UI.
echo.
adb logcat -v time --pid=%PID%
