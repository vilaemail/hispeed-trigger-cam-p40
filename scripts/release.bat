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
