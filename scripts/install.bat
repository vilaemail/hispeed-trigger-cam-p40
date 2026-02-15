@echo off
setlocal
cd /d "%~dp0.."

if "%~1"=="" (
    set "APK=release\latest.apk"
) else (
    set "APK=release\%~1.apk"
)

if not exist "%APK%" (
    echo ERROR: %APK% not found.
    exit /b 1
)

echo Installing %APK% ...
adb install -r "%APK%"
