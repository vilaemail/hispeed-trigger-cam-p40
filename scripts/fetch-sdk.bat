@echo off
setlocal
cd /d "%~dp0.."

echo === Downloading Huawei CameraKit SDK ===

if not exist "app\libs" mkdir app\libs

curl -L -o "app\libs\camerakit-1.1.3.aar" "https://developer.huawei.com/repo/com/huawei/multimedia/camerakit/1.1.3/camerakit-1.1.3.aar"
if %ERRORLEVEL% neq 0 (
    echo ERROR: Download failed
    exit /b 1
)

echo Done: app\libs\camerakit-1.1.3.aar
