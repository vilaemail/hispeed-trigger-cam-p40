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

powershell -NoProfile -Command ^
    "$pw = Read-Host 'Keystore password' -AsSecureString;" ^
    "$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pw));" ^
    "@(" ^
    "  'storeFile=../signing/release.keystore'," ^
    "  \"storePassword=$plain\"," ^
    "  'keyAlias=release'," ^
    "  \"keyPassword=$plain\"" ^
    ") | Set-Content -Path 'signing/keystore.properties' -Encoding ASCII"

if not exist "signing\keystore.properties" (
    echo ERROR: Failed to create signing config.
    exit /b 1
)

call gradlew.bat assembleRelease
set "BUILD_RESULT=%ERRORLEVEL%"

del /q "signing\keystore.properties" 2>nul

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
