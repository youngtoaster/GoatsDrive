@echo off
setlocal enabledelayedexpansion

echo ========================================================
echo       ⚡ GoatsDrive Ultra-Low Latency Projection ⚡     
echo         One-Click Aftermarket Head Unit Installer       
echo ========================================================

where adb >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] 'adb' command not found in PATH!
    echo Please install Android platform-tools or add it to PATH.
    pause
    exit /b 1
)

set HEADUNIT_IP=%1
if "%HEADUNIT_IP%"=="" (
    set /p HEADUNIT_IP="Enter Head Unit IP address (e.g. 192.168.43.1): "
)

echo.
echo [1/5] Connecting to Head Unit at %HEADUNIT_IP%:5555...
adb disconnect >nul 2>nul
adb connect %HEADUNIT_IP%:5555

echo.
echo [2/5] Checking for factory ZLink5...
adb shell pm list packages com.zjinnova.zlink | findstr "com.zjinnova.zlink" >nul
if %errorlevel% equ 0 (
    echo Disabling factory ZLink5 to free MCU hooks...
    adb shell pm disable-user --user 0 com.zjinnova.zlink >nul 2>nul
    adb shell pm uninstall -k --user 0 com.zjinnova.zlink >nul 2>nul
    echo ZLink5 disabled.
) else (
    echo ZLink5 not detected.
)

echo.
echo [3/5] Locating GoatsDrive APK...
set APK_FILE=GoatsDrive.apk
if not exist "%APK_FILE%" (
    echo Downloading latest GoatsDrive release from GitHub...
    powershell -Command "Invoke-WebRequest -Uri 'https://github.com/youngtoaster/GoatsDrive/releases/latest/download/GoatsDrive.apk' -OutFile 'GoatsDrive.apk'"
)

echo.
echo [4/5] Installing GoatsDrive onto Head Unit...
adb install -r -d -g GoatsDrive.apk

echo.
echo [5/5] Granting permissions and launching...
adb shell appops set com.apexlink.projection SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +com.apexlink.projection
adb shell pm grant com.apexlink.projection android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.apexlink.projection android.permission.NEARBY_WIFI_DEVICES
adb shell am start -n com.apexlink.projection/.MainActivity

echo.
echo ========================================================
echo 🎉 SUCCESS: GoatsDrive installed and running!
echo ========================================================
pause
