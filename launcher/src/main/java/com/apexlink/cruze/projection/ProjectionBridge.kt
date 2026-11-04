package com.apexlink.cruze.projection

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import com.apexlink.cruze.climate.FloatingClimateOverlayService

/**
 * Robust CarPlay & Android Auto projection coordinator.
 * Detects whatever factory or third-party projection software is installed on the head unit
 * (ZLink5, TLink, AutoKit, SpeedPlay, Android Auto, or GoatsDrive) and launches it seamlessly
 * while keeping the Floating Climate HUD overlay active on top of navigation.
 */
object ProjectionBridge {

    val KNOWN_PROJECTION_PACKAGES = listOf(
        "com.zjinnova.zlink" to "ZLink5 CarPlay / AA",
        "com.syu.carlink" to "TLink CarLink",
        "cn.manstep.phonemirrorbox" to "AutoKit Box",
        "com.suding.speedplay" to "SpeedPlay",
        "com.carletter" to "CarLetter",
        "net.easyconn" to "EasyConnection",
        "com.google.android.projection.gearhead" to "Android Auto",
        "com.apexlink.projection" to "GoatsDrive"
    )

    enum class ConnectionType {
        DISCONNECTED,
        CARPLAY_WIRELESS,
        CARPLAY_USB,
        ANDROID_AUTO_WIRELESS,
        ANDROID_AUTO_USB
    }

    var connectionState = ConnectionType.DISCONNECTED

    /**
     * Finds the primary available projection app on this car head unit.
     */
    fun findInstalledProjectionPackage(context: Context): Pair<String, String>? {
        val pm = context.packageManager
        for ((pkg, label) in KNOWN_PROJECTION_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return Pair(pkg, label)
            } catch (ignored: PackageManager.NameNotFoundException) {}
        }
        return null
    }

    /**
     * Launches the car projection app and spins up the floating climate bar on top.
     */
    fun launchProjection(context: Context): Boolean {
        // 1. Start floating climate HUD if overlay permission is granted
        if (hasOverlayPermission(context)) {
            FloatingClimateOverlayService.start(context)
        }

        // 2. Discover installed projection engine
        val pm = context.packageManager
        for ((pkg, label) in KNOWN_PROJECTION_PACKAGES) {
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                try {
                    context.startActivity(launchIntent)
                    return true
                } catch (ignored: Exception) {}
            }
        }

        // 3. If none installed, notify driver
        Toast.makeText(context, "No CarPlay/AA projection app found. Use GoatsInstaller to install.", Toast.LENGTH_LONG).show()
        return false
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun getStatusLabel(context: Context): String {
        val installed = findInstalledProjectionPackage(context)
        val engineName = installed?.second ?: "CarPlay / Android Auto"
        return when (connectionState) {
            ConnectionType.CARPLAY_WIRELESS -> "Apple CarPlay (Wireless)"
            ConnectionType.CARPLAY_USB -> "Apple CarPlay (USB Cable)"
            ConnectionType.ANDROID_AUTO_WIRELESS -> "Android Auto (Wireless)"
            ConnectionType.ANDROID_AUTO_USB -> "Android Auto (USB Cable)"
            ConnectionType.DISCONNECTED -> "Launch $engineName"
        }
    }
}
