package com.apexlink.installer

import android.content.Context
import android.util.Log
import java.io.InputStream

class HeadUnitProvisioner(private val context: Context) {

    companion object {
        private const val TAG = "GD_Provisioner"
        const val GOATSDRIVE_PACKAGE = "com.apexlink.projection"
        const val CRUZEOS_PACKAGE = "com.apexlink.cruzeos"
        const val ZLINK_PACKAGE = "com.zjinnova.zlink"
        private const val REMOTE_TMP_APK = "/data/local/tmp/staged_install.apk"
    }

    interface Listener {
        fun onLog(message: String)
        fun onProgress(percent: Int)
        fun onCompleted(success: Boolean)
    }

    data class InstallTask(
        val transport: AdbTransport,
        val apkBytes: ByteArray? = null,
        val apkStreamProvider: (() -> InputStream?)? = null,
        val totalSizeBytes: Long = 0L,
        val appTitle: String = "GoatsDrive",
        val packageName: String = GOATSDRIVE_PACKAGE,
        val isGoatsDrive: Boolean = true,
        val isCruzeOs: Boolean = false,
        val disableZlink: Boolean = false,
        val launchAfterInstall: Boolean = true
    )

    /**
     * Executes installation of GoatsDrive or any custom APK across either USB or TCP transport.
     */
    fun executeInstall(task: InstallTask, listener: Listener) {
        Thread {
            val adb = AdbClient(task.transport)
            try {
                listener.onLog("Connecting to Head Unit via ${task.transport.name}...")
                if (!adb.connect(6000)) {
                    val helpTip = if (task.transport.isUsb) {
                        "❌ USB connection failed! Ensure USB Debugging is ON in Head Unit Factory Settings."
                    } else {
                        "❌ Connection failed! Make sure Head Unit is on same network and port 5555 is open."
                    }
                    listener.onLog(helpTip)
                    listener.onCompleted(false)
                    return@Thread
                }
                listener.onLog("✅ Connected to Head Unit ADB daemon (${task.transport.name})!")

                // Probe hardware model & OS release
                val model = adb.executeShell("getprop ro.product.model").trim()
                val androidVer = adb.executeShell("getprop ro.build.version.release").trim()
                val board = adb.executeShell("getprop ro.board.platform").trim()
                listener.onLog("📟 Target Device: $model | Android $androidVer | Platform: $board")

                // Handle ZLink5 if requested
                if (task.disableZlink) {
                    listener.onLog("🔍 Checking for factory ZLink5...")
                    val packages = adb.executeShell("pm list packages $ZLINK_PACKAGE")
                    if (packages.contains(ZLINK_PACKAGE)) {
                        listener.onLog("⚠️ Factory ZLink5 detected. Disabling to free MCU hardware hooks...")
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

                // Push APK to head unit
                val sizeKb = (task.totalSizeBytes / 1024).toInt()
                listener.onLog("📦 Uploading ${task.appTitle} ($sizeKb KB) to head unit...")

                val pushSuccess = if (task.apkStreamProvider != null) {
                    val inStream = task.apkStreamProvider.invoke()
                    if (inStream == null) {
                        listener.onLog("❌ Failed to open APK stream from storage!")
                        listener.onCompleted(false)
                        return@Thread
                    }
                    val pushed = adb.pushStream(inStream, task.totalSizeBytes, REMOTE_TMP_APK) { fraction ->
                        listener.onProgress((fraction * 100).toInt())
                    }
                    try { inStream.close() } catch (ignored: Exception) {}
                    pushed
                } else if (task.apkBytes != null) {
                    adb.pushFile(task.apkBytes, REMOTE_TMP_APK) { fraction ->
                        listener.onProgress((fraction * 100).toInt())
                    }
                } else {
                    listener.onLog("❌ No APK payload provided to install!")
                    listener.onCompleted(false)
                    return@Thread
                }

                if (!pushSuccess) {
                    listener.onLog("❌ Failed to push APK to head unit storage!")
                    listener.onCompleted(false)
                    return@Thread
                }
                listener.onLog("✅ APK uploaded to $REMOTE_TMP_APK")

                // Install package
                listener.onLog("⚙️ Executing package manager installation (pm install -r -d -g)...")
                val installOut = adb.executeShell("pm install -r -d -g $REMOTE_TMP_APK")
                listener.onLog("PackageManager response: $installOut")

                if (!installOut.contains("Success")) {
                    listener.onLog("❌ Installation failed: $installOut")
                    listener.onCompleted(false)
                    return@Thread
                }
                listener.onLog("🎉 ${task.appTitle} installed successfully!")

                // Clean up staged temporary APK
                adb.executeShell("rm -f $REMOTE_TMP_APK")

                // Permissions & Optimization
                if (task.isGoatsDrive) {
                    listener.onLog("🔐 Granting overlay & foreground battery whitelist...")
                    adb.executeShell("appops set ${task.packageName} SYSTEM_ALERT_WINDOW allow")
                    adb.executeShell("dumpsys deviceidle whitelist +${task.packageName}")
                    adb.executeShell("pm grant ${task.packageName} android.permission.ACCESS_FINE_LOCATION 2>/dev/null")
                    adb.executeShell("pm grant ${task.packageName} android.permission.NEARBY_WIFI_DEVICES 2>/dev/null")
                } else if (task.isCruzeOs) {
                    listener.onLog("🔐 Granting CruzeOS overlay & home launcher permissions...")
                    adb.executeShell("appops set ${task.packageName} SYSTEM_ALERT_WINDOW allow")
                    adb.executeShell("dumpsys deviceidle whitelist +${task.packageName}")
                    adb.executeShell("cmd package set-home-activity ${task.packageName}/com.apexlink.cruze.CruzeLauncherActivity 2>/dev/null")
                } else {
                    adb.executeShell("appops set ${task.packageName} SYSTEM_ALERT_WINDOW allow 2>/dev/null")
                }

                // Launch if requested
                if (task.launchAfterInstall) {
                    listener.onLog("🚀 Launching ${task.appTitle} on Head Unit screen...")
                    if (task.isGoatsDrive) {
                        adb.executeShell("am start -n ${task.packageName}/.MainActivity")
                    } else if (task.isCruzeOs) {
                        adb.executeShell("am start -n ${task.packageName}/com.apexlink.cruze.CruzeLauncherActivity")
                    } else {
                        adb.executeShell("monkey -p ${task.packageName} -c android.intent.category.LAUNCHER 1 2>/dev/null")
                    }
                }

                listener.onLog("🏁 ALL DONE! ${task.appTitle} is deployed and ready on your car screen.")
                listener.onCompleted(true)

            } catch (e: Exception) {
                listener.onLog("❌ Error: ${e.message}")
                listener.onCompleted(false)
            } finally {
                adb.close()
            }
        }.start()
    }

    /**
     * Backward-compatible helper for legacy Wi-Fi installs.
     */
    fun provision(
        ip: String,
        port: Int,
        disableZlink: Boolean,
        apkBytes: ByteArray,
        listener: Listener
    ) {
        val task = InstallTask(
            transport = TcpAdbTransport(ip, port),
            apkBytes = apkBytes,
            totalSizeBytes = apkBytes.size.toLong(),
            appTitle = "GoatsDrive",
            packageName = GOATSDRIVE_PACKAGE,
            isGoatsDrive = true,
            disableZlink = disableZlink,
            launchAfterInstall = true
        )
        executeInstall(task, listener)
    }
}
