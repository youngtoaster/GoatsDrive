package com.apexlink.installer

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

class MainActivity : Activity() {

    private lateinit var ipEditText: EditText
    private lateinit var portEditText: EditText
    private lateinit var testConnButton: Button
    private lateinit var connectionStatusText: TextView
    private lateinit var updateStatusText: TextView
    private lateinit var checkUpdatesButton: Button
    private lateinit var zlinkCheckBox: CheckBox
    private lateinit var installButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressPercentText: TextView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val provisioner = HeadUnitProvisioner(this)

    private var latestReleaseInfo: ApkUpdateManager.ReleaseInfo? = null
    private var isCheckingUpdate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // --- App Header ---
        val titleView = TextView(this).apply {
            text = "⚡ GoatsDrive Head Unit Installer"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 4)
        }
        rootLayout.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = "Zero-Copy Receiver Deployer & ZLink5 Replacement"
            textSize = 12.5f
            setTextColor(Color.parseColor("#81C784"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 20)
        }
        rootLayout.addView(subtitleView)

        // --- Quick Setup Guide Banner Button ---
        val guideButton = Button(this).apply {
            text = "📖 Connection & Setup Guide (FYT / Topway / T3L)"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#80D8FF"))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1A2B3C"))
                setStroke(2, Color.parseColor("#0288D1"))
                cornerRadius = 16f
            }
            background = bg
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
            setOnClickListener {
                SetupGuideDialog.show(this@MainActivity)
            }
        }
        rootLayout.addView(guideButton)

        // --- Connection Card ---
        val connectionCard = createCardView().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }

        val connLabel = TextView(this).apply {
            text = "📡 Head Unit Connection (Port 5555):"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E0E0E0"))
            setPadding(0, 0, 0, 8)
        }
        connectionCard.addView(connLabel)

        val ipPortRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
        }

        ipEditText = EditText(this).apply {
            hint = "192.168.43.1"
            setText(detectDefaultHeadUnitIp())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(20, 18, 20, 18)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f).apply {
                marginEnd = 12
            }
        }
        ipPortRow.addView(ipEditText)

        portEditText = EditText(this).apply {
            setText("5555")
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(20, 18, 20, 18)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }
        ipPortRow.addView(portEditText)
        connectionCard.addView(ipPortRow)

        // Test connection & Detect IP row
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }
        }

        testConnButton = Button(this).apply {
            text = "🔍 Test ADB Connection"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#37474F"))
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f).apply {
                marginEnd = 8
            }
            setOnClickListener { runConnectionTest() }
        }
        actionsRow.addView(testConnButton)

        val detectIpButton = Button(this).apply {
            text = "🔄 Auto-Detect IP"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#263238"))
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val ip = detectDefaultHeadUnitIp()
                ipEditText.setText(ip)
                Toast.makeText(this@MainActivity, "Detected Network Gateway: $ip", Toast.LENGTH_SHORT).show()
            }
        }
        actionsRow.addView(detectIpButton)
        connectionCard.addView(actionsRow)

        connectionStatusText = TextView(this).apply {
            text = "• ADB Status: Tap 'Test ADB Connection' to verify"
            textSize = 12f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, 4, 0, 0)
        }
        connectionCard.addView(connectionStatusText)
        rootLayout.addView(connectionCard)

        // --- Target Build & Update Card ---
        val buildCard = createCardView().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }

        val buildHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 6) }
        }

        val buildTitle = TextView(this).apply {
            text = "📦 GoatsDrive Build:"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E0E0E0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        buildHeaderRow.addView(buildTitle)

        checkUpdatesButton = Button(this).apply {
            text = "🔄 Check GitHub"
            textSize = 11f
            setTextColor(Color.parseColor("#80D8FF"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(12, 4, 12, 4)
            setOnClickListener { checkForUpdates(manual = true) }
        }
        buildHeaderRow.addView(checkUpdatesButton)
        buildCard.addView(buildHeaderRow)

        updateStatusText = TextView(this).apply {
            text = "Checking for latest release on GitHub..."
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 0, 0, 8)
        }
        buildCard.addView(updateStatusText)

        zlinkCheckBox = CheckBox(this).apply {
            text = "Disable bloated factory ZLink5 (Prevents 2GB RAM LMK crash)"
            isChecked = true
            setTextColor(Color.parseColor("#FFCC80"))
            textSize = 12.5f
            setPadding(0, 0, 0, 0)
        }
        buildCard.addView(zlinkCheckBox)
        rootLayout.addView(buildCard)

        // --- Flash Button & Progress ---
        installButton = Button(this).apply {
            text = "⚡ Flash GoatsDrive to Head Unit"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 16f
            setPadding(32, 26, 32, 26)
            setOnClickListener { startInstallation() }
        }
        rootLayout.addView(installButton)

        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 6)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        progressRow.addView(progressBar)

        progressPercentText = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.parseColor("#A5D6A7"))
            typeface = Typeface.MONOSPACE
            setPadding(12, 0, 0, 0)
            visibility = View.GONE
        }
        progressRow.addView(progressPercentText)
        rootLayout.addView(progressRow)

        // --- Deployment Terminal Output ---
        val consoleTitle = TextView(this).apply {
            text = "💻 Deployment Console:"
            textSize = 12.5f
            setTextColor(Color.parseColor("#81C784"))
            typeface = Typeface.MONOSPACE
            setPadding(0, 4, 0, 4)
        }
        rootLayout.addView(consoleTitle)

        logScrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(16, 16, 16, 16)
        }

        logTextView = TextView(this).apply {
            text = "Ready. Tap '📖 Connection & Setup Guide' for step-by-step help.\n"
            setTextColor(Color.parseColor("#A5D6A7"))
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
        }
        logScrollView.addView(logTextView)
        rootLayout.addView(logScrollView)

        setContentView(rootLayout)

        // Initialize target build status and check GitHub for latest releases
        updateBuildStatusLabel()
        checkForUpdates(manual = false)
    }

    private fun createCardView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C1C"))
                cornerRadius = 12f
                setStroke(1, Color.parseColor("#2C2C2C"))
            }
            background = bg
        }
    }

    private fun detectDefaultHeadUnitIp(): String {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifiManager.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                @Suppress("DEPRECATION")
                Formatter.formatIpAddress(dhcp.gateway)
            } else {
                "192.168.43.1" // Standard automotive hotspot gateway
            }
        } catch (e: Exception) {
            "192.168.43.1"
        }
    }

    private fun runConnectionTest() {
        val ip = ipEditText.text.toString().trim()
        val port = portEditText.text.toString().trim().toIntOrNull() ?: 5555

        if (ip.isEmpty()) {
            Toast.makeText(this, "Please enter Head Unit IP address", Toast.LENGTH_SHORT).show()
            return
        }

        testConnButton.isEnabled = false
        connectionStatusText.text = "Testing connection to $ip:$port..."
        connectionStatusText.setTextColor(Color.parseColor("#FFD54F"))
        appendLog("🔍 Probing Head Unit ADB daemon at $ip:$port...")

        Thread {
            val client = AdbClient(ip, port)
            val result = client.testConnection(timeoutMs = 4500)

            mainHandler.post {
                testConnButton.isEnabled = true
                if (result.success) {
                    connectionStatusText.text = "✅ Connected: ${result.model} (Android ${result.androidVersion})"
                    connectionStatusText.setTextColor(Color.parseColor("#81C784"))
                    appendLog("✅ Head Unit Connected: ${result.model} | Android ${result.androidVersion} | Platform: ${result.platform}")
                    Toast.makeText(this, "Connected to ${result.model}!", Toast.LENGTH_SHORT).show()
                } else {
                    connectionStatusText.text = "❌ Cannot reach $ip:$port (Check Wi-Fi/Port 5555)"
                    connectionStatusText.setTextColor(Color.parseColor("#E57373"))
                    appendLog("❌ Connection Failed: ${result.errorMessage}")
                    appendLog("💡 Check that USB Debugging is ON in Factory Settings (Pin 3368 / 8888 / 1617) and both devices are on same Wi-Fi.")
                }
            }
        }.start()
    }

    private fun updateBuildStatusLabel() {
        val desc = ApkUpdateManager.getStatusDescription(this)
        updateStatusText.text = "Active Build: $desc"
    }

    private fun checkForUpdates(manual: Boolean) {
        if (isCheckingUpdate) return
        isCheckingUpdate = true

        if (manual) {
            updateStatusText.text = "Checking GitHub Releases for latest build..."
            appendLog("🌐 Checking GitHub for latest GoatsDrive release...")
        }

        ApkUpdateManager.checkLatestRelease { info, error ->
            mainHandler.post {
                isCheckingUpdate = false
                if (info != null) {
                    latestReleaseInfo = info
                    val isCached = ApkUpdateManager.isCachedUpToDate(this, info)
                    val sizeKb = info.sizeBytes / 1024
                    if (isCached) {
                        updateStatusText.text = "Latest: ${info.tagName} (Up to date - ${sizeKb} KB)"
                        updateStatusText.setTextColor(Color.parseColor("#81C784"))
                        if (manual) appendLog("✅ You have the latest GoatsDrive build [${info.tagName}] cached.")
                    } else {
                        updateStatusText.text = "Newer Build Available: ${info.tagName} (${sizeKb} KB)"
                        updateStatusText.setTextColor(Color.parseColor("#FFD54F"))
                        appendLog("🔔 Newer build found on GitHub: ${info.tagName} (${sizeKb} KB). Will download during flash.")
                    }
                } else {
                    val desc = ApkUpdateManager.getStatusDescription(this)
                    updateStatusText.text = "Using local build: $desc"
                    if (manual) {
                        appendLog("ℹ️ Could not check GitHub (${error ?: "offline"}). Using local build: $desc")
                    }
                }
            }
        }
    }

    private fun startInstallation() {
        val ip = ipEditText.text.toString().trim()
        val port = portEditText.text.toString().trim().toIntOrNull() ?: 5555
        val disableZlink = zlinkCheckBox.isChecked

        if (ip.isEmpty()) {
            Toast.makeText(this, "Please enter Head Unit IP", Toast.LENGTH_SHORT).show()
            return
        }

        installButton.isEnabled = false
        testConnButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressPercentText.visibility = View.VISIBLE
        progressBar.progress = 0
        progressPercentText.text = "0%"
        logTextView.text = ""

        appendLog("🚀 Initiating GoatsDrive Deployment to $ip:$port...")

        // Step 1: Ensure we have the absolute latest APK
        val release = latestReleaseInfo
        if (release != null && !ApkUpdateManager.isCachedUpToDate(this, release)) {
            appendLog("⬇️ Downloading latest GoatsDrive build [${release.tagName}] from GitHub Releases...")
            updateProgress(0, "Downloading latest APK...")

            ApkUpdateManager.downloadLatestApk(
                context = this,
                release = release,
                onProgress = { percent, downloaded, total ->
                    mainHandler.post {
                        updateProgress(percent, "Downloading: $percent% (${downloaded / 1024} KB / ${total / 1024} KB)")
                    }
                },
                onComplete = { file, error ->
                    mainHandler.post {
                        if (file != null && file.exists()) {
                            appendLog("✅ Download complete! Verified latest GoatsDrive APK (${file.length() / 1024} KB).")
                            updateBuildStatusLabel()
                            proceedWithAdbInstall(ip, port, disableZlink)
                        } else {
                            appendLog("⚠️ GitHub download failed: ${error ?: "unknown"}. Falling back to cached/embedded APK.")
                            proceedWithAdbInstall(ip, port, disableZlink)
                        }
                    }
                }
            )
        } else {
            proceedWithAdbInstall(ip, port, disableZlink)
        }
    }

    private fun proceedWithAdbInstall(ip: String, port: Int, disableZlink: Boolean) {
        val apkBytes = ApkUpdateManager.getBestApkBytes(this) { log ->
            appendLog(log)
        }

        if (apkBytes.isEmpty()) {
            appendLog("❌ Aborting: No GoatsDrive APK binary available to install.")
            resetInstallState(false)
            return
        }

        provisioner.provision(ip, port, disableZlink, apkBytes, object : HeadUnitProvisioner.Listener {
            override fun onLog(message: String) {
                appendLog(message)
            }

            override fun onProgress(percent: Int) {
                mainHandler.post {
                    updateProgress(percent, "Uploading APK: $percent%")
                }
            }

            override fun onCompleted(success: Boolean) {
                mainHandler.post {
                    resetInstallState(success)
                    if (success) {
                        Toast.makeText(this@MainActivity, "GoatsDrive successfully deployed to Head Unit!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Installation failed. Check console log for details.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun updateProgress(percent: Int, statusText: String? = null) {
        progressBar.progress = percent
        progressPercentText.text = "$percent%"
        if (statusText != null && percent % 25 == 0) {
            appendLog(statusText)
        }
    }

    private fun resetInstallState(success: Boolean) {
        installButton.isEnabled = true
        testConnButton.isEnabled = true
        progressBar.visibility = View.INVISIBLE
        progressPercentText.visibility = View.GONE
    }

    private fun appendLog(msg: String) {
        mainHandler.post {
            logTextView.append("$msg\n")
            logScrollView.post {
                logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
}
