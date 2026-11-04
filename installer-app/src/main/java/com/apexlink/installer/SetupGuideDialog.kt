package com.apexlink.installer

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Interactive, high-contrast in-app guide explaining step-by-step
 * how to connect an Android phone to aftermarket car head units (FYT, Topway, Allwinner)
 * via USB Cable or Wi-Fi, enable ADB on port 5555, and sideload any APK.
 */
object SetupGuideDialog {

    fun show(context: Context) {
        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(36, 24, 36, 36)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(layout)

        fun addHeader(title: String, color: String = "#81C784") {
            val tv = TextView(context).apply {
                text = title
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(color))
                setPadding(0, 24, 0, 8)
            }
            layout.addView(tv)
        }

        fun addParagraph(htmlText: String) {
            val tv = TextView(context).apply {
                @Suppress("DEPRECATION")
                text = Html.fromHtml(htmlText)
                textSize = 13.5f
                setTextColor(Color.parseColor("#E0E0E0"))
                setLineSpacing(4f, 1.15f)
                setPadding(0, 0, 0, 12)
            }
            layout.addView(tv)
        }

        fun addCodeBox(content: String) {
            val tv = TextView(context).apply {
                text = content
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#80D8FF"))
                setBackgroundColor(Color.parseColor("#1E1E1E"))
                setPadding(20, 16, 20, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 16) }
            }
            layout.addView(tv)
        }

        // Title
        val titleView = TextView(context).apply {
            text = "📖 Head Unit Setup & Installation Guide"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 12, 0, 16)
        }
        layout.addView(titleView)

        addHeader("🔌 METHOD 1: Fast USB Cable Installation (No Wi-Fi Needed)", "#80D8FF")
        addParagraph(
            "You can install GoatsDrive or any APK directly over a USB cable plugged between your phone and car head unit!<br><br>" +
            "<b>Option A (USB OTG / Host Mode):</b><br>" +
            "Plug your phone into the head unit using an OTG adapter or Type-C cable. Tap <i>'Install via USB'</i>. When Android prompts <i>'Allow USB Permission'</i>, tap OK to begin flashing.<br><br>" +
            "<b>Option B (Standard USB Cable + USB Tethering):</b><br>" +
            "1. Plug your phone into the car's USB port with your normal charging/sync cable.<br>" +
            "2. On your phone, go to <b>Settings &gt; Network &gt; Hotspot &amp; Tethering &gt; Enable USB Tethering</b>.<br>" +
            "3. Tap <b>'Install via USB'</b> in GoatsInstaller. The app detects the USB network link and deploys instantly!"
        )

        addHeader("📶 METHOD 2: Wireless Wi-Fi / Hotspot Installation", "#64B5F6")
        addParagraph(
            "<b>In Car (Mobile Hotspot):</b><br>" +
            "1. Turn on <b>Personal Hotspot</b> on your phone.<br>" +
            "2. On your Car Head Unit, connect to your phone's Wi-Fi hotspot.<br>" +
            "3. In this mode, the Head Unit's IP is almost always <b>192.168.43.1</b>.<br><br>" +
            "<b>In Garage / Home:</b><br>" +
            "Connect both your phone and car head unit to your home Wi-Fi network."
        )

        addHeader("🔓 Enabling USB Debugging / ADB on Head Unit", "#81C784")
        addParagraph(
            "Ensure USB Debugging / Wireless ADB is enabled in your head unit's factory settings:"
        )

        addParagraph("<b>1. FYT Boards (UIS7862, UIS8581, FYT6021, DuduAuto, Joying, Mekede):</b>")
        addCodeBox(
            "Method 1: Settings > Car / Factory Settings\n" +
            "  -> Enter PIN: 3368 (or 1617 / 8888)\n" +
            "  -> Turn ON 'USB Debugging' or 'ADB'\n\n" +
            "Method 2: Settings > System > About\n" +
            "  -> Tap 'Build number' 7 times\n" +
            "  -> Open Developer Options > Enable 'USB Debugging'"
        )

        addParagraph("<b>2. Topway Boards (TS10, TS18, TS7, Teeyes CC3):</b>")
        addCodeBox(
            "Settings > Factory Settings\n" +
            "  -> Enter PIN: 8888 (or 123456)\n" +
            "  -> Engineer Settings / Options\n" +
            "  -> Toggle 'ADB' or 'USB Debugging' to ON"
        )

        addParagraph("<b>3. Allwinner Boards (T3L, T507, T8):</b>")
        addCodeBox(
            "Car Settings > Extra Settings\n" +
            "  -> Enter PIN: 1617 or 126\n" +
            "  -> Turn ON 'USB Debugging' switch"
        )

        addHeader("📁 Sideloading ANY APK from Your Phone", "#FFD54F")
        addParagraph(
            "Need Spotify, Waze, YouTube Music, or another car app without Google Play Store?<br>" +
            "1. Switch the target to <b>'Custom APK from Phone'</b>.<br>" +
            "2. Tap <b>📂 Choose APK File</b> and pick any <code>.apk</code> saved on your phone.<br>" +
            "3. Tap <b>Install via USB</b> or <b>Install via Wi-Fi</b>.<br>" +
            "4. GoatsInstaller will upload, install, grant permissions, and launch the app right on your dashboard!"
        )

        addHeader("🛡️ Why Disable ZLink5?", "#E57373")
        addParagraph(
            "Factory ZLink5 takes over the Bluetooth and USB hardware with bloated background daemons that cause memory exhaustion and audio/touch lag on 2GB RAM head units.<br><br>" +
            "Disabling it allows GoatsDrive to run with <b>zero-copy native C++ hardware acceleration under 35MB RAM</b> with sub-5ms touch response."
        )

        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(scroll)
            .setPositiveButton("Got It! Let's Connect", null)
            .show()
    }
}
