package com.apexlink.cruze.projection

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.apexlink.cruze.climate.FloatingClimateOverlayService

/**
 * Coordinates launching the zero-copy GoatsDrive projection receiver (CarPlay & Android Auto)
 * while keeping the Floating Climate HUD overlay active on top of the projection stream.
 */
object ProjectionBridge {

    const val PROJECTION_PACKAGE = "com.apexlink.projection"
    const val PROJECTION_ACTIVITY = "com.apexlink.projection.MainActivity"

    enum class ConnectionType {
        DISCONNECTED,
        CARPLAY_WIRELESS,
        CARPLAY_USB,
        ANDROID_AUTO_WIRELESS,
        ANDROID_AUTO_USB
    }

    var connectionState = ConnectionType.DISCONNECTED

    fun launchProjection(context: Context) {
        // Ensure floating climate HUD is running so driver can adjust AC inside CarPlay/AA
        if (hasOverlayPermission(context)) {
            FloatingClimateOverlayService.start(context)
        }

        val launchIntent = Intent().apply {
            setClassName(PROJECTION_PACKAGE, PROJECTION_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            // Fallback: try package launch intent
            val fallback = context.packageManager.getLaunchIntentForPackage(PROJECTION_PACKAGE)
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
            }
        }
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun getStatusLabel(): String {
        return when (connectionState) {
            ConnectionType.CARPLAY_WIRELESS -> "📱 Apple CarPlay (Wireless)"
            ConnectionType.CARPLAY_USB -> "🔌 Apple CarPlay (USB)"
            ConnectionType.ANDROID_AUTO_WIRELESS -> "📱 Android Auto (Wireless)"
            ConnectionType.ANDROID_AUTO_USB -> "🔌 Android Auto (USB)"
            ConnectionType.DISCONNECTED -> "📱 Connect Phone (USB or BT)"
        }
    }
}
