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
 * and enable Wireless ADB on port 5555.
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
                LinearLayout.LayoutParams.WRAP_CONTENT
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
            text = "📖 Head Unit Setup & Wireless ADB Guide"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 12, 0, 16)
        }
        layout.addView(titleView)

        addHeader("📶 STEP 1: Connect Phone & Car Head Unit", "#64B5F6")
        addParagraph(
            "Both devices must be on the <b>same local Wi-Fi network</b> so your phone can communicate with the head unit's ADB daemon.<br><br>" +
            "<b>Option A (Easiest in Car - Mobile Hotspot):</b><br>" +
            "1. Turn on <b>Personal Hotspot</b> on your phone.<br>" +
            "2. On your Car Head Unit, go to <i>Settings &gt; Wi-Fi</i> and connect to your phone's hotspot.<br>" +
            "3. In this mode, the Head Unit's IP is almost always <b>192.168.43.1</b> (or check your phone's Connected Devices list).<br><br>" +
            "<b>Option B (Home / Garage Wi-Fi):</b><br>" +
            "Connect both your phone and car head unit to your home Wi-Fi network."
        )

        addHeader("🔓 STEP 2: Enable Wireless ADB (Port 5555)", "#81C784")
        addParagraph(
            "Android head units support ADB over Wi-Fi on port <b>5555</b> once USB Debugging is turned on in Factory / Developer Settings:"
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

        addParagraph(
            "💡 <i>Tip: After turning ON USB Debugging, the head unit automatically opens port 5555. No root or PC is required!</i>"
        )

        addHeader("🌐 STEP 3: Finding the Head Unit's IP Address", "#FFD54F")
        addParagraph(
            "• <b>On Head Unit:</b> Open <i>Settings &gt; Network &amp; Internet &gt; Wi-Fi</i>, tap your connected Wi-Fi network, expand <b>Advanced</b>, and find <b>IP Address</b> (e.g. 192.168.1.105 or 192.168.43.x).<br><br>" +
            "• <b>On Phone:</b> If the head unit is connected to your Phone Hotspot, go to <i>Settings &gt; Hotspot &gt; Connected Devices</i> to see its exact IP address."
        )

        addHeader("⚡ STEP 4: Test Connection & Flash GoatsDrive", "#FF8A65")
        addParagraph(
            "1. Enter the IP into GoatsInstaller and tap <b>🔍 Test Connection</b>.<br>" +
            "2. If verified, keep <b>[x] Disable bloated factory ZLink5</b> checked.<br>" +
            "3. Tap <b>⚡ Flash GoatsDrive to Head Unit</b>.<br>" +
            "4. GoatsInstaller will automatically verify the latest build, upload the APK, install it, grant system permissions, and launch GoatsDrive on your car display!"
        )

        addHeader("🛡️ Why Disable ZLink5?", "#E57373")
        addParagraph(
            "Factory ZLink5 takes over the Bluetooth and USB hardware with bloated background processes that cause memory exhaustion and audio/touch lag on 2GB RAM head units.<br><br>" +
            "Disabling it allows GoatsDrive to run with <b>zero-copy native C++ hardware acceleration under 35MB RAM</b> with sub-5ms touch response."
        )

        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(scroll)
            .setPositiveButton("Got It! Let's Connect", null)
            .show()
    }
}
