@echo off
setlocal
cd /d "%~dp0.."

echo === HiSpeed Trigger Cam - Debug Build ===
echo.

call gradlew.bat assembleDebug
if %ERRORLEVEL% neq 0 (
    echo.
    echo BUILD FAILED
    exit /b %ERRORLEVEL%
)

echo.
echo === Build successful ===
echo APK: app\build\outputs\apk\debug\app-debug.apk
