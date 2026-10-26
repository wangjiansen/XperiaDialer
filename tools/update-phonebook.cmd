@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0.."

set "GH=C:\Program Files\GitHub CLI\gh.exe"
set "TMP=%TEMP%\vcards-src"

if not exist "%GH%" (
  echo [FAIL] 找不到 GitHub CLI: %GH%
  echo        先装：winget install GitHub.cli
  goto end
)

echo [1/3] 下载 metowolf/vCards 最新数据...
if exist "%TMP%" rmdir /s /q "%TMP%"
mkdir "%TMP%" 2>nul
"%GH%" api repos/metowolf/vCards/tarball/master > "%TMP%\src.tar.gz"
if errorlevel 1 (
  echo [FAIL] 下载失败，检查网络或 gh auth status
  goto end
)

echo [2/3] 解压...
tar -xzf "%TMP%\src.tar.gz" -C "%TMP%"
if errorlevel 1 (
  echo [FAIL] 解压失败
  goto end
)
set "DATA="
for /d %%D in ("%TMP%\metowolf-vCards-*") do set "DATA=%%D\data"
if not defined DATA (
  echo [FAIL] 解压后找不到 data 目录
  goto end
)

echo [3/3] 生成 assets/phonebook.tsv ...
python "tools\gen_phonebook.py" --src "%DATA%"
if errorlevel 1 (
  echo [FAIL] 生成失败
  goto end
)

echo.
echo 完成。注意：phonebook.tsv 被 .gitignore 排除（上游仓库没有 LICENSE）。

:end
echo.
pause
endlocal
