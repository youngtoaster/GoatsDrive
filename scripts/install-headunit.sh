#!/bin/bash
set -e

# ==============================================================================
# GoatsDrive One-Click Head Unit Installer
# Can be run directly from an Android Phone (via Termux) or from Linux / macOS
# ==============================================================================

echo "========================================================"
echo "      ⚡ GoatsDrive Ultra-Low Latency Projection ⚡     "
echo "        One-Click Aftermarket Head Unit Installer       "
echo "========================================================"

# Check ADB
if ! command -v adb &> /dev/null; then
    echo "❌ 'adb' command not found!"
    echo "👉 On Android Termux: run 'pkg install android-tools' first."
    echo "👉 On PC/Mac: install Android platform-tools."
    exit 1
fi

HEADUNIT_IP="${1}"
if [ -z "$HEADUNIT_IP" ]; then
    echo -n "Enter Head Unit IP address (e.g. 192.168.43.1): "
    read -r HEADUNIT_IP
fi

HEADUNIT_PORT="5555"

echo ""
echo "🔌 Connecting to Head Unit at ${HEADUNIT_IP}:${HEADUNIT_PORT}..."
adb disconnect >/dev/null 2>&1 || true
adb connect "${HEADUNIT_IP}:${HEADUNIT_PORT}"

sleep 1

# Check connection
DEVICE_STATE=$(adb get-state 2>/dev/null || echo "offline")
if [ "$DEVICE_STATE" != "device" ]; then
    echo "❌ Unable to connect to head unit! Ensure Wireless ADB is active."
    exit 1
fi

echo "✅ Connected to Head Unit successfully!"

# Fetch Model
MODEL=$(adb shell getprop ro.product.model | tr -d '\r')
ANDROID_VER=$(adb shell getprop ro.build.version.release | tr -d '\r')
echo "📟 Head Unit Model: ${MODEL} (Android ${ANDROID_VER})"

# Suppress / Disable ZLink5
echo ""
echo "🔍 Checking for factory bloated ZLink5 (com.zjinnova.zlink)..."
if adb shell pm list packages com.zjinnova.zlink | grep -q "com.zjinnova.zlink"; then
    echo "⚠️ Factory ZLink5 found! Disabling to free MCU CAN-bus hooks..."
    adb shell pm disable-user --user 0 com.zjinnova.zlink >/dev/null 2>&1 || \
    adb shell pm uninstall -k --user 0 com.zjinnova.zlink >/dev/null 2>&1 || true
    echo "✅ ZLink5 suppressed."
else
    echo "ℹ️ ZLink5 not detected or already disabled."
fi

# Locate APK
APK_FILE="GoatsDrive.apk"
if [ ! -f "$APK_FILE" ]; then
    echo "🌐 Downloading latest GoatsDrive.apk from GitHub Releases..."
    curl -sL "https://github.com/youngtoaster/GoatsDrive/releases/latest/download/GoatsDrive.apk" -o "$APK_FILE" || {
        echo "⚠️ Download failed or release not yet populated. Searching local build..."
        APK_FILE=$(find . -name "*release*.apk" -o -name "app-debug.apk" | head -n 1)
    }
fi

if [ ! -f "$APK_FILE" ]; then
    echo "❌ Could not find GoatsDrive APK to install!"
    exit 1
fi

echo "📦 Installing ${APK_FILE} onto Head Unit..."
adb install -r -d -g "$APK_FILE"

echo "🔐 Configuring Automotive Overlays & Background Execution..."
adb shell appops set com.apexlink.projection SYSTEM_ALERT_WINDOW allow || true
adb shell dumpsys deviceidle whitelist +com.apexlink.projection || true
adb shell pm grant com.apexlink.projection android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
adb shell pm grant com.apexlink.projection android.permission.NEARBY_WIFI_DEVICES 2>/dev/null || true

echo "🚀 Launching GoatsDrive into foreground..."
adb shell am start -n com.apexlink.projection/.MainActivity

echo ""
echo "========================================================"
echo "🎉 SUCCESS: GoatsDrive is now active on your Head Unit!"
echo "Plug in your phone or connect Bluetooth to begin projection."
echo "========================================================"
