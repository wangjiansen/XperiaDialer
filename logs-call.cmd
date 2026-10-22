@echo off
chcp 65001 >nul
setlocal
set "ANDROID_HOME=C:\Users\wangj\.android-sdk"
set "PATH=%ANDROID_HOME%\platform-tools;%PATH%"
set "PKG=com.wangj.xperiadialer"
set "OUT=%~dp0call-log.txt"
set "CRA=%~dp0call-crash.txt"

echo ==========================================================
echo  Xperia Dialer - call chain log collector
echo ==========================================================
echo.

adb get-state >nul 2>&1
if errorlevel 1 (
  echo [FAIL] No device detected.
  echo        - connect the phone over USB
  echo        - enable USB debugging
  echo        - accept the "Allow USB debugging" prompt
  goto end
)

echo [1/3] Clearing logcat buffer...
adb logcat -c
if errorlevel 1 (
  echo [FAIL] adb logcat -c failed.
  goto end
)

echo.
echo [2/3] Now CALL YOUR PHONE.
echo         - let it ring, answer it
echo         - talk 30 seconds or longer
echo         - hang up
echo         - then come back and press any key
echo.
pause >nul

echo.
echo [3/3] Collecting...
echo.

adb logcat -d -v time -s CallForegroundService CallNotif CallManager CallScreeningService InCallServiceImpl DialerApp > "%OUT%" 2>&1
set "SZ=0"
for %%A in ("%OUT%") do set "SZ=%%~zA"
echo ---------- app log (call chain) ----------
if "%SZ%"=="0" (
  echo   [WARN] no matching lines. Did the app run during the call?
) else (
  type "%OUT%"
)

echo.
echo ---------- crash buffer ----------
adb logcat -b crash -d -v time > "%CRA%" 2>&1
set "SZ2=0"
for %%A in ("%CRA%") do set "SZ2=%%~zA"
if "%SZ2%"=="0" (
  echo   no crash record  [OK]
) else (
  type "%CRA%"
)

echo.
echo ---------- foreground service state ----------
adb shell dumpsys activity services %PKG% 2>&1 | findstr /I "ServiceRecord isForeground"
echo   ^(empty = service stopped, expected after the call ended^)

echo.
echo ---------- notification leftover ----------
adb shell dumpsys notification --noredact 2>&1 | findstr /C:"id=2001" /C:"id=2002"
echo   ^(empty = no leftover notification, expected^)

echo.
echo ==========================================================
echo  full log : %OUT%
echo  crash log: %CRA%
echo ==========================================================

:end
echo.
pause
endlocal
