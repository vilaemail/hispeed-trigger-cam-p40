@echo off
setlocal
cd /d "%~dp0.."

echo === HiSpeed Trigger Cam - Deploy Debug to Device ===
echo.

echo Stopping app...
adb shell am force-stop com.hispeedtriggercam.p40.debug >nul 2>&1

call gradlew.bat installDebug
if %ERRORLEVEL% neq 0 (
    echo.
    echo DEPLOY FAILED
    exit /b %ERRORLEVEL%
)

echo.
echo === Deployed - launching app ===
adb shell am start -n com.hispeedtriggercam.p40.debug/com.hispeedtriggercam.p40.MainActivity
echo Starting logcat (Ctrl+C to stop)...
echo.
adb logcat -c
adb logcat -s HiSpeedTriggerCam:V SSM_CAM2:V SSM_Custom:V HwRecorder:V SuperSlowRecordAction:V ModeManager:V Settings:V
