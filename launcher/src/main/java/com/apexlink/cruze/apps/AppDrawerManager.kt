package com.apexlink.cruze.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    var isPinnedToDock: Boolean = false
)

object AppDrawerManager {

    private val allApps = mutableListOf<AppInfo>()
    private val pinnedPackages = mutableSetOf(
        "com.google.android.apps.maps",
        "com.spotify.music",
        "com.apexlink.projection",
        "com.android.settings"
    )

    fun loadInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolved = pm.queryIntentActivities(intent, 0)
        allApps.clear()

        for (ri in resolved) {
            val pkg = ri.activityInfo.packageName
            // Skip launcher itself
            if (pkg == context.packageName) continue

            val label = ri.loadLabel(pm).toString()
            val icon = ri.loadIcon(pm)
            val isPinned = pinnedPackages.contains(pkg)

            allApps.add(
                AppInfo(
                    label = label,
                    packageName = pkg,
                    activityName = ri.activityInfo.name,
                    icon = icon,
                    isPinnedToDock = isPinned
                )
            )
        }

        allApps.sortBy { it.label.lowercase() }
        return allApps
    }

    fun getApps(query: String = ""): List<AppInfo> {
        if (query.isEmpty()) return allApps
        return allApps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    fun getPinnedDockApps(): List<AppInfo> {
        return allApps.filter { it.isPinnedToDock }
    }

    fun togglePin(app: AppInfo) {
        app.isPinnedToDock = !app.isPinnedToDock
        if (app.isPinnedToDock) {
            pinnedPackages.add(app.packageName)
        } else {
            pinnedPackages.remove(app.packageName)
        }
    }

    fun launchApp(context: Context, app: AppInfo) {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    }
}
