@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."

:: ============================================================
:: pull-hwcamerakit.bat
:: Pulls HwCameraKit.apk from device, converts to JAR.
:: Run once — then use patch-hwcamerakit.bat to iterate.
::
:: Requires: adb, curl
:: ============================================================

set APK_SRC=/system/priv-app/HwCameraKit/HwCameraKit.apk
set APK_LOCAL=pull\HwCameraKit\HwCameraKit.apk
set ORIG_JAR=pull\hwcamerakit.jar

set DEX2JAR_DIR=%TEMP%\dex-tools-v2.4
set DEX2JAR=%DEX2JAR_DIR%\d2j-dex2jar.bat
set DEX2JAR_URL=https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip

echo === Pull HwCameraKit ===
echo.

:: ----- Step 1: Pull APK from device -----
echo [1/3] Pulling HwCameraKit.apk from device...
if not exist "pull\HwCameraKit" mkdir "pull\HwCameraKit"
adb pull %APK_SRC% %APK_LOCAL%
if %ERRORLEVEL% neq 0 (
    echo ERROR: Failed to pull APK. Is the device connected?
    exit /b 1
)

:: ----- Step 2: Ensure dex2jar is available -----
if not exist "%DEX2JAR%" (
    echo [2/3] Downloading dex2jar v2.4...
    curl -L -o "%TEMP%\dex2jar.zip" "%DEX2JAR_URL%"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to download dex2jar
        exit /b 1
    )
    powershell -command "Expand-Archive -Force '%TEMP%\dex2jar.zip' '%TEMP%'"
    if not exist "%DEX2JAR%" (
        echo ERROR: dex2jar extraction failed
        exit /b 1
    )
) else (
    echo [2/3] dex2jar already cached
)

:: ----- Step 3: Convert APK to JAR -----
echo [3/3] Converting APK to JAR...
call "%DEX2JAR%" "%APK_LOCAL%" -o "%ORIG_JAR%" --force
if %ERRORLEVEL% neq 0 (
    echo ERROR: dex2jar conversion failed
    exit /b 1
)

echo.
echo === Done ===
echo   Original JAR: %ORIG_JAR%
echo   Now run: scripts\patch-hwcamerakit.bat [bitrate_bps]
