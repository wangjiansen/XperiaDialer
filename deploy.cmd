@echo off
rem Xperia Dialer - one-click build + install + launch
rem Double-click this file after the phone is connected over USB.
chcp 65001 >nul
setlocal

set "JAVA_HOME=C:\Program Files\Java\oracleJdk-21"
set "ANDROID_HOME=C:\Users\wangj\.android-sdk"
set "GRADLE_USER_HOME=C:\Users\wangj\.gradle"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"
set "PKG=com.wangj.xperiadialer"
cd /d "%~dp0"

echo ==========================================
echo  Xperia Dialer  build + install + launch
echo ==========================================
echo.

echo [1/4] Checking device...
adb get-state >nul 2>&1
if errorlevel 1 (
  echo   [FAIL] No device detected.
  echo   - Connect the phone via USB
  echo   - Developer options -^> USB debugging ON
  echo   - Accept the "Allow USB debugging" prompt on the phone
  goto fail
)
for /f "delims=" %%i in ('adb shell getprop ro.product.model 2^>nul') do set "MODEL=%%i"
echo   OK: %MODEL%

echo.
echo [2/4] Building debug APK...
call gradlew.bat assembleDebug --init-script ..\.build\mirrors.gradle --console=plain
if errorlevel 1 goto buildfail

echo.
echo [3/4] Installing...
adb install -r app\build\outputs\apk\debug\app-debug.apk
if errorlevel 1 (
  echo   [FAIL] Install failed. Try: adb uninstall %PKG%
  goto fail
)

echo.
echo [4/4] Launching...
adb shell am start -n %PKG%/.MainActivity >nul 2>&1
echo   OK
echo.
echo ==========================================
echo  DONE
echo  Logs:  run logs.cmd
echo ==========================================
goto end

:buildfail
echo.
echo [FAIL] Build failed - see errors above.
goto end

:fail
echo.

:end
endlocal
pause
