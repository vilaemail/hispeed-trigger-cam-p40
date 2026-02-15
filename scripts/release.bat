@echo off
setlocal
cd /d "%~dp0.."

if "%~1"=="" (
    echo Usage: scripts\release.bat v1.0.0
    exit /b 1
)
set "VERSION=%~1"

echo === HiSpeed Trigger Cam - Release %VERSION% ===
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
set "APK=release\%VERSION%.apk"
copy /y "app\build\outputs\apk\release\app-release.apk" "%APK%" >nul

echo.
echo === Creating GitHub release %VERSION% ===
git tag %VERSION%
git push origin %VERSION%
gh release create %VERSION% "%APK%" --title "%VERSION%" --generate-notes

echo.
echo === Done ===
echo Release: %VERSION%
echo APK: %APK%
