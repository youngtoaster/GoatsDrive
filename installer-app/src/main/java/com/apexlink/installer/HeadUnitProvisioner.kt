package com.apexlink.installer

import android.content.Context
import android.util.Log

class HeadUnitProvisioner(private val context: Context) {

    companion object {
        private const val TAG = "GD_Provisioner"
        private const val TARGET_PACKAGE = "com.apexlink.projection"
        private const val ZLINK_PACKAGE = "com.zjinnova.zlink"
        private const val REMOTE_TMP_APK = "/data/local/tmp/GoatsDrive.apk"
    }

    interface Listener {
        fun onLog(message: String)
        fun onProgress(percent: Int)
        fun onCompleted(success: Boolean)
    }

    fun provision(
        ip: String,
        port: Int,
        disableZlink: Boolean,
        apkBytes: ByteArray,
        listener: Listener
    ) {
        Thread {
            val adb = AdbClient(ip, port)
            try {
                listener.onLog("Connecting to Head Unit at $ip:$port...")
                if (!adb.connect(6000)) {
                    listener.onLog("❌ Connection failed! Make sure Head Unit is on same Wi-Fi and Wireless ADB (port 5555) is enabled.")
                    listener.onCompleted(false)
                    return@Thread
                }
                listener.onLog("✅ Connected to Head Unit ADB daemon!")

                // Check model & OS info
                val model = adb.executeShell("getprop ro.product.model").trim()
                val androidVer = adb.executeShell("getprop ro.build.version.release").trim()
                val board = adb.executeShell("getprop ro.board.platform").trim()
                listener.onLog("📟 Detected Hardware: $model | Android $androidVer | Platform: $board")

                // Handle ZLink5 if requested
                if (disableZlink) {
                    listener.onLog("🔍 Checking for factory ZLink5...")
                    val packages = adb.executeShell("pm list packages $ZLINK_PACKAGE")
                    if (packages.contains(ZLINK_PACKAGE)) {
                        listener.onLog("⚠️ Factory ZLink5 detected. Disabling to free MCU CAN-bus hooks...")
                        val disableResult = adb.executeShell("pm disable-user --user 0 $ZLINK_PACKAGE")
                        listener.onLog("ZLink disable output: $disableResult")
                        if (!disableResult.contains("disabled")) {
                            adb.executeShell("pm uninstall -k --user 0 $ZLINK_PACKAGE")
                        }
                        listener.onLog("✅ ZLink5 suppressed successfully.")
                    } else {
                        listener.onLog("ℹ️ ZLink5 not found or already disabled.")
                    }
                }

                // Push GoatsDrive APK
                listener.onLog("📦 Pushing GoatsDrive receiver APK (${apkBytes.size / 1024} KB)...")
                val pushSuccess = adb.pushFile(apkBytes, REMOTE_TMP_APK) { fraction ->
                    val p = (fraction * 100).toInt()
                    listener.onProgress(p)
                }

                if (!pushSuccess) {
                    listener.onLog("❌ Failed to push APK to head unit!")
                    listener.onCompleted(false)
                    return@Thread
                }
                listener.onLog("✅ APK uploaded to $REMOTE_TMP_APK")

                // Install APK
                listener.onLog("⚙️ Executing package manager installation...")
                val installOut = adb.executeShell("pm install -r -d -g $REMOTE_TMP_APK")
                listener.onLog("Installation response: $installOut")

                if (!installOut.contains("Success")) {
                    listener.onLog("❌ Installation failed: $installOut")
                    listener.onCompleted(false)
                    return@Thread
                }
                listener.onLog("🎉 GoatsDrive installed successfully!")

                // Grant required headunit overlay and background execution permissions
                listener.onLog("🔐 Granting SYSTEM_ALERT_WINDOW and background wake locks...")
                adb.executeShell("appops set $TARGET_PACKAGE SYSTEM_ALERT_WINDOW allow")
                adb.executeShell("dumpsys deviceidle whitelist +$TARGET_PACKAGE")
                adb.executeShell("pm grant $TARGET_PACKAGE android.permission.ACCESS_FINE_LOCATION 2>/dev/null")
                adb.executeShell("pm grant $TARGET_PACKAGE android.permission.NEARBY_WIFI_DEVICES 2>/dev/null")

                // Clean up tmp APK
                adb.executeShell("rm -f $REMOTE_TMP_APK")

                // Boot GoatsDrive immediately
                listener.onLog("🚀 Launching GoatsDrive into foreground...")
                adb.executeShell("am start -n $TARGET_PACKAGE/.MainActivity")

                listener.onLog("🏁 ALL DONE! GoatsDrive is now running on your head unit screen.")
                listener.onCompleted(true)

            } catch (e: Exception) {
                listener.onLog("❌ Error: ${e.message}")
                listener.onCompleted(false)
            } finally {
                adb.close()
            }
        }.start()
    }
}
