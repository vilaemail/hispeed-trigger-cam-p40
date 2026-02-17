@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."

:: ============================================================
:: patch-hwcamerakit.bat
:: Patches pull\hwcamerakit.jar -> libs\hwcamerakit.jar:
::   - Video bitrate: 12 Mbps -> specified value
::   - Optionally: video encoder H.264 -> H.265/HEVC
::   - Optionally: CBR bitrate mode (compiles replacement class)
::   - Fingerprint string: "done" -> "d0ne"
::
:: Usage:  scripts\patch-hwcamerakit.bat [bitrate_bps] [h264|h265] [cbr]
::
:: Examples:
::   scripts\patch-hwcamerakit.bat                       (100 Mbps, H.264, VBR)
::   scripts\patch-hwcamerakit.bat 100000000 h264 cbr    (100 Mbps, H.264, CBR)
::   scripts\patch-hwcamerakit.bat 80000000 h265 cbr     (80 Mbps, H.265, CBR)
::   scripts\patch-hwcamerakit.bat 60000000 h265         (60 Mbps, H.265, VBR)
::
:: Limits from /vendor/etc/media_codecs.xml (overrides /system/etc):
:: H.264 max: 100 Mbps  (OMX.hisi.video.encoder.avc,  VBR/CBR)
:: H.265 max:  80 Mbps  (OMX.hisi.video.encoder.hevc, VBR/CBR)
::
:: Run scripts\pull-hwcamerakit.bat first to get the original JAR.
:: Requires: Python 3.  CBR mode also requires: JDK (javac), Android SDK.
:: ============================================================

set BITRATE=%~1
if "%BITRATE%"=="" set BITRATE=100000000

set CODEC=%~2
if "%CODEC%"=="" set CODEC=h264

set BITRATEMODE=%~3

set OLD_BITRATE=12000000
set ORIG_JAR=pull\hwcamerakit.jar
set PATCHED_JAR=libs\hwcamerakit.jar

if not exist "%ORIG_JAR%" (
    echo ERROR: %ORIG_JAR% not found. Run scripts\pull-hwcamerakit.bat first.
    exit /b 1
)

echo === Patch HwCameraKit ===
echo Bitrate: %OLD_BITRATE% -^> %BITRATE% bps
echo Encoder: %CODEC%
if /i "%BITRATEMODE%"=="cbr" (echo Bitrate mode: CBR) else (echo Bitrate mode: VBR ^(stock^))
echo.

if not exist "libs" mkdir "libs"

set H265_FLAG=
if /i "%CODEC%"=="h265" set H265_FLAG=--h265
if /i "%CODEC%"=="hevc" set H265_FLAG=--h265

:: ── CBR: compile replacement class ──────────────────────────
set CBR_FLAG=
if /i not "%BITRATEMODE%"=="cbr" goto :skip_cbr

echo --- Compiling CBR replacement class ---

:: Find javac
set "JAVAC=javac"
if defined JAVA_HOME set "JAVAC=%JAVA_HOME%\bin\javac"

:: Find android.jar from local.properties or ANDROID_HOME
set "SDK_DIR="
if exist "local.properties" (
    for /f "usebackq tokens=1,* delims==" %%A in ("local.properties") do (
        if "%%A"=="sdk.dir" set "SDK_DIR=%%B"
    )
)
:: Clean up escaped path from local.properties (C\:\\foo\\bar -> C:\foo\bar)
if defined SDK_DIR (
    set "SDK_DIR=!SDK_DIR:\:=:!"
    set "SDK_DIR=!SDK_DIR:\\=\!"
)
if not defined SDK_DIR (
    if defined ANDROID_HOME set "SDK_DIR=%ANDROID_HOME%"
)
if not defined SDK_DIR (
    if defined ANDROID_SDK_ROOT set "SDK_DIR=%ANDROID_SDK_ROOT%"
)

if not defined SDK_DIR (
    echo ERROR: Cannot find Android SDK. Set ANDROID_HOME or check local.properties.
    exit /b 1
)
echo SDK: !SDK_DIR!

:: Find latest android.jar
set "ANDROID_JAR="
for /d %%D in ("!SDK_DIR!\platforms\android-*") do set "ANDROID_JAR=%%D\android.jar"
if not defined ANDROID_JAR (
    echo ERROR: No android.jar found in !SDK_DIR!\platforms\
    exit /b 1
)
if not exist "!ANDROID_JAR!" (
    echo ERROR: android.jar not found: !ANDROID_JAR!
    exit /b 1
)
echo android.jar: !ANDROID_JAR!

:: Compile
set "CBR_SRC=%~dp0..\temp\c.java"
set "CBR_OUT=%~dp0..\temp\cbr_out"
if exist "!CBR_OUT!" rd /s /q "!CBR_OUT!"
mkdir "!CBR_OUT!"

"!JAVAC!" -source 11 -target 11 -cp "%ORIG_JAR%;!ANDROID_JAR!" -d "!CBR_OUT!" "!CBR_SRC!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: javac compilation failed
    exit /b 1
)

set "CBR_CLASS=!CBR_OUT!\com\huawei\camerakit\impl\c.class"
if not exist "!CBR_CLASS!" (
    echo ERROR: Compiled class not found at !CBR_CLASS!
    exit /b 1
)
echo Compiled: !CBR_CLASS!
echo.

set CBR_FLAG=--cbr-class "!CBR_CLASS!"

:skip_cbr

:: ── Run patcher ─────────────────────────────────────────────
py "%~dp0patch_bitrate.py" "%ORIG_JAR%" "%PATCHED_JAR%" %OLD_BITRATE% %BITRATE% %H265_FLAG% %CBR_FLAG%
if %ERRORLEVEL% neq 0 (
    echo ERROR: Bytecode patching failed
    exit /b 1
)

:: Clean up CBR compilation output
if exist "%~dp0..\temp\cbr_out" rd /s /q "%~dp0..\temp\cbr_out"

echo.
echo === Done ===
echo   Patched: %PATCHED_JAR%
echo   Bitrate: %BITRATE% bps
echo   Encoder: %CODEC%
if /i "%BITRATEMODE%"=="cbr" (echo   Mode:    CBR) else (echo   Mode:    VBR)
