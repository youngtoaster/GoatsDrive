# ⚡ GoatsDrive: Ultra-Lightweight Zero-Copy Car Projection Receiver

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%207.0%20--%2014-green.svg)](https://developer.android.com)
[![Memory Footprint](https://img.shields.io/badge/RAM%20Ceiling-%3C45MB-brightgreen.svg)]()
[![Zero GC](https://img.shields.io/badge/Video%20Pipeline-Zero--GC%20JNI-orange.svg)]()

**GoatsDrive** is an embedded, zero-copy Android Auto and Apple CarPlay projection receiver engineered to replace bloated factory projection software (**ZLink5**, SpeedPlay, CarLetter) on aftermarket Android head units (**FYT 7862/8581**, **Topway TS10/TS18**, **Allwinner T3L/T507** architectures).

Unlike stock Chinese head unit apps that consume 350MB+ RAM and stutter under low memory conditions, GoatsDrive operates strictly **under 45MB RAM** with **zero garbage collection pauses** in video, audio, and touch loops.

---

## 🏎️ Core Performance Characteristics

* **Zero-Copy Video Pipeline:** H.264 NAL units are parsed directly in native C++17 memory and fed into hardware `AMediaCodec`. Decoded frames are rendered straight to `ANativeWindow` with GPU direct flip (zero CPU copies).
* **Ultra-Low Latency Audio:** Native `AAudio` engine operating in `AAUDIO_SHARING_MODE_EXCLUSIVE` with automatic fallback to `OpenSL ES` on Android 7.0–7.1. Integrated automotive audio ducking lowers projection volume during native navigation or FM radio announcements.
* **Sub-5ms Touch & SWC Injection:** Touch events bypass Java object allocation entirely by packing normalized 16-bit coordinates and actions into a static primitive byte buffer dispatched directly over JNI `GetPrimitiveArrayCritical`. Full support for steering wheel control keys (`KEYCODE_MEDIA_NEXT`, `KEYCODE_VOICE_ASSIST`, etc.).
* **Instant Auto-Launch Triggers:** Listens for USB accessory attachment (`android.hardware.usb.action.USB_ACCESSORY_ATTACHED`, AOA 2.0 Vendor `0x18D1`) and smartphone Bluetooth connection (`ACTION_ACL_CONNECTED`), bypassing Android 10+ background execution limits with `SYSTEM_ALERT_WINDOW`.

---

## 📱 Quick & Easy Installation from an Android Phone

You can deploy GoatsDrive to your car's head unit directly from your phone in under 30 seconds—**no computer required**.

### Method 1: GoatsInstaller Companion App (Recommended)
1. Download **`GoatsInstaller.apk`** from [Latest GitHub Releases](https://github.com/youngtoaster/GoatsDrive/releases/latest) onto your Android smartphone.
2. Choose your connection method:
   * **🔌 Fast USB Cable Install:** Plug phone into head unit via USB cable. Tap **"Install via USB"** (supports OTG host mode & USB Tethering).
   * **📶 Wi-Fi / Hotspot Install:** Connect phone and head unit to the same Wi-Fi/Hotspot, enter IP, and tap **"Install via Wi-Fi"**.
3. Choose what to install:
   * **⚡ GoatsDrive Receiver:** Automatically fetches the latest build, suppresses factory ZLink5, installs GoatsDrive, and boots it onto your car screen.
   * **📁 Sideload Custom APK:** Tap **"Select APK File from Phone"** to pick any app saved on your phone (Spotify, Waze, YouTube Music, etc.) and deploy it straight to your car without needing a USB flash drive or Google Play Store!
4. In-App Tools:
   * **📖 Connection & Setup Guide:** In-app walkthrough with factory PINs (`3368`, `8888`, `1617`) for FYT, Topway, and Allwinner boards.
   * **🔍 Test ADB Connection:** One-tap hardware check that verifies connection and displays head unit model and Android version.

### Method 2: One-Line Termux / ADB Command (From Phone or PC)
If you have **Termux** on your phone or `adb` on your PC/Mac:
```bash
# In Termux on Android Phone (or Linux/Mac terminal):
curl -sL https://raw.githubusercontent.com/youngtoaster/GoatsDrive/main/scripts/install-headunit.sh | bash
```
Or for Windows:
```cmd
# Double click or run in Command Prompt:
scripts\install-headunit.bat
```

---

## 🛠️ FYT & Topway MCU Whitelist Quirk

Many aftermarket head units run proprietary MCU daemons that actively kill background applications not recognized by the factory whitelist.

If your head unit terminates GoatsDrive on ignition cycle:
1. In `app/build.gradle.kts`, set:
   ```kotlin
   applicationId = "com.zjinnova.zlink"
   ```
2. Disable the stock factory ZLink5 app via ADB:
   ```bash
   adb shell pm disable-user --user 0 com.zjinnova.zlink
   ```
3. Re-install GoatsDrive. The head unit MCU will now treat GoatsDrive as the factory projection app, routing steering wheel CAN bus keys and Bluetooth triggers to it automatically!

---

## 📁 Repository Structure

```text
GoatsDrive/
├── .github/workflows/
│   └── build-release.yml        # CI/CD: Builds APKs and publishes GitHub Releases
├── app/                         # Ultra-lightweight Projection Receiver (Head Unit)
│   ├── src/main/
│   │   ├── cpp/                 # C++17 NDK multimedia pipeline
│   │   │   ├── CMakeLists.txt
│   │   │   ├── ring_buffer.h    # Lock-free SPSC circular buffer
│   │   │   ├── transport_io.cpp # POSIX TCP & USB FD IO reader
│   │   │   ├── native-decoder.cpp # Zero-copy AMediaCodec + ANativeWindow
│   │   │   ├── native-audio.cpp # Low-latency AAudio & OpenSL ES sink
│   │   │   └── native-bridge.cpp# High-performance JNI bindings
│   │   ├── java/com/apexlink/projection/
│   │   │   ├── MainActivity.kt
│   │   │   ├── ProjectionSurfaceView.kt
│   │   │   ├── receiver/ConnectionTriggerReceiver.kt
│   │   │   ├── service/ProjectionForegroundService.kt
│   │   │   ├── service/AudioFocusController.kt
│   │   │   └── transport/
│   │   │       ├── UsbAoapTransport.kt
│   │   │       └── WirelessSocketManager.kt
│   │   └── res/xml/             # USB AOA 2.0 & CarPlay multiplexer device filters
├── installer-app/               # Companion Phone Installer App
│   └── src/main/java/com/apexlink/installer/
│       ├── AdbClient.kt         # Pure Kotlin ADB TCP client
│       ├── HeadUnitProvisioner.kt
│       └── MainActivity.kt      # Phone UI with live deployment console
└── scripts/
    ├── install-headunit.sh      # One-click installer for Termux / Mac / Linux
    └── install-headunit.bat     # One-click installer for Windows
```

---

## 🔨 Building from Source

Ensure you have Android SDK 34 and NDK 26+ installed:

```bash
git clone https://github.com/youngtoaster/GoatsDrive.git
cd GoatsDrive

# Build Head Unit Receiver APK & Phone Companion Installer APK
./gradlew assembleRelease
```
The resulting binaries will be placed in:
* `app/build/outputs/apk/release/`
* `installer-app/build/outputs/apk/release/`

---

## 📄 License
Licensed under the Apache 2.0 License.
