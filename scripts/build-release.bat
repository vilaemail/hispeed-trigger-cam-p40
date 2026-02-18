@echo off
setlocal

cd /d "%~dp0.."

echo === HiSpeed Trigger Cam - Release Build ===
echo.

if not exist "signing\release.keystore" (
    echo ERROR: signing\release.keystore not found.
    echo See signing\README.md for setup instructions.
    exit /b 1
)

for /f "usebackq delims=" %%p in (`powershell -NoProfile -Command ^
    "$pw = Read-Host 'Keystore password' -AsSecureString;" ^
    "[Runtime.InteropServices.Marshal]::PtrToStringAuto(" ^
    "[Runtime.InteropServices.Marshal]::SecureStringToBSTR($pw))"`) do set "KS_PW=%%p"

if not defined KS_PW (
    echo ERROR: Failed to capture keystore password.
    exit /b 1
)

set "KEYSTORE_FILE=../signing/release.keystore"
set "KEYSTORE_PASSWORD=%KS_PW%"
set "KEY_ALIAS=release"
set "KEY_PASSWORD=%KS_PW%"

call gradlew.bat assembleRelease
set "BUILD_RESULT=%ERRORLEVEL%"

if %BUILD_RESULT% neq 0 (
    echo.
    echo BUILD FAILED
    exit /b %BUILD_RESULT%
)

if not exist "release" mkdir release

copy /y "app\build\outputs\apk\release\app-release.apk" "release\latest.apk" >nul

echo.
echo === Build successful ===
echo APK: release\latest.apk
