package com.apexlink.installer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_PICK_APK = 1001
        private const val MODE_GOATSDRIVE = 0
        private const val MODE_CUSTOM_APK = 1
    }

    private var activeTargetMode = MODE_GOATSDRIVE

    // Selected custom APK data
    private var customApkUri: Uri? = null
    private var customApkTitle: String = ""
    private var customApkPackage: String = ""
    private var customApkSize: Long = 0L

    // UI - Header & Guide
    private lateinit var guideButton: Button

    // UI - Mode Toggle
    private lateinit var tabGoatsDrive: Button
    private lateinit var tabCustomApk: Button
    private lateinit var goatsDriveContainer: LinearLayout
    private lateinit var customApkContainer: LinearLayout

    // UI - GoatsDrive Payload
    private lateinit var updateStatusText: TextView
    private lateinit var checkUpdatesButton: Button
    private lateinit var zlinkCheckBox: CheckBox

    // UI - Custom APK Payload
    private lateinit var pickApkButton: Button
    private lateinit var customApkInfoText: TextView
    private lateinit var customApkClearButton: Button
    private lateinit var customLaunchCheckBox: CheckBox

    // UI - Connection Section
    private lateinit var usbStatusBadge: TextView
    private lateinit var installUsbButton: Button
    private lateinit var installWifiButton: Button
    private lateinit var ipEditText: EditText
    private lateinit var portEditText: EditText
    private lateinit var testConnButton: Button
    private lateinit var detectIpButton: Button
    private lateinit var wifiStatusBadge: TextView

    // UI - Progress & Terminal
    private lateinit var progressBar: ProgressBar
    private lateinit var progressPercentText: TextView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val provisioner = HeadUnitProvisioner(this)

    private var latestReleaseInfo: ApkUpdateManager.ReleaseInfo? = null
    private var isCheckingUpdate = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateUsbStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootScrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootScrollView.addView(rootLayout)

        // --- App Title ---
        val titleView = TextView(this).apply {
            text = "⚡ GoatsDrive & App Deployer"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 4)
        }
        rootLayout.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = "Fast USB & Wi-Fi Sideloading for Car Head Units"
            textSize = 12.5f
            setTextColor(Color.parseColor("#81C784"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(subtitleView)

        // --- Setup Guide Banner ---
        guideButton = Button(this).apply {
            text = "📖 Connection & Setup Guide (FYT / Topway / T3L)"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#80D8FF"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A2B3C"))
                setStroke(2, Color.parseColor("#0288D1"))
                cornerRadius = 16f
            }
            setPadding(24, 18, 24, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
            setOnClickListener { SetupGuideDialog.show(this@MainActivity) }
        }
        rootLayout.addView(guideButton)

        // --- Target App Mode Selector (Tabs) ---
        val modeToggleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }

        tabGoatsDrive = Button(this).apply {
            text = "⚡ GoatsDrive Receiver"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6
            }
            setOnClickListener { setTargetMode(MODE_GOATSDRIVE) }
        }
        modeToggleLayout.addView(tabGoatsDrive)

        tabCustomApk = Button(this).apply {
            text = "📁 Custom APK from Phone"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { setTargetMode(MODE_CUSTOM_APK) }
        }
        modeToggleLayout.addView(tabCustomApk)
        rootLayout.addView(modeToggleLayout)

        // --- Target Card ---
        val targetCard = createCardView().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }

        // Panel 1: GoatsDrive Options
        goatsDriveContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
            text = "📦 GoatsDrive Zero-Copy Engine:"
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
            setPadding(8, 4, 8, 4)
            setOnClickListener { checkForUpdates(manual = true) }
        }
        buildHeaderRow.addView(checkUpdatesButton)
        goatsDriveContainer.addView(buildHeaderRow)

        updateStatusText = TextView(this).apply {
            text = "Checking for latest release on GitHub..."
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 0, 0, 8)
        }
        goatsDriveContainer.addView(updateStatusText)

        zlinkCheckBox = CheckBox(this).apply {
            text = "Disable bloated factory ZLink5 (Prevents 2GB RAM LMK crash)"
            isChecked = true
            setTextColor(Color.parseColor("#FFCC80"))
            textSize = 12f
        }
        goatsDriveContainer.addView(zlinkCheckBox)
        targetCard.addView(goatsDriveContainer)

        // Panel 2: Custom APK Sideload Options
        customApkContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val customHeader = TextView(this).apply {
            text = "📂 Sideload Any App to Head Unit:"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E0E0E0"))
            setPadding(0, 0, 0, 8)
        }
        customApkContainer.addView(customHeader)

        val pickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }
        }

        pickApkButton = Button(this).apply {
            text = "📂 Select APK File from Phone"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#283593"))
                cornerRadius = 10f
            }
            setPadding(20, 14, 20, 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setOnClickListener { openApkFilePicker() }
        }
        pickRow.addView(pickApkButton)

        customApkClearButton = Button(this).apply {
            text = "Clear"
            textSize = 11.5f
            setTextColor(Color.parseColor("#FF8A80"))
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener { clearCustomApkSelection() }
        }
        pickRow.addView(customApkClearButton)
        customApkContainer.addView(pickRow)

        customApkInfoText = TextView(this).apply {
            text = "No APK selected. Tap above to pick Spotify, Waze, YouTube Music, etc."
            textSize = 12f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, 0, 0, 8)
        }
        customApkContainer.addView(customApkInfoText)

        customLaunchCheckBox = CheckBox(this).apply {
            text = "Launch app on Head Unit screen immediately after install"
            isChecked = true
            setTextColor(Color.parseColor("#81C784"))
            textSize = 12f
        }
        customApkContainer.addView(customLaunchCheckBox)
        targetCard.addView(customApkContainer)

        rootLayout.addView(targetCard)

        // --- Connection & Deployment Card ---
        val deployCard = createCardView().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }

        val deployTitle = TextView(this).apply {
            text = "🚀 Choose Deployment Method:"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E0E0E0"))
            setPadding(0, 0, 0, 10)
        }
        deployCard.addView(deployTitle)

        // USB Action Row
        installUsbButton = Button(this).apply {
            text = "🔌 Install via USB Cable (Instant)"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00838F"))
                cornerRadius = 14f
            }
            setPadding(24, 22, 24, 22)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 6) }
            setOnClickListener { startInstallation(useUsb = true) }
        }
        deployCard.addView(installUsbButton)

        usbStatusBadge = TextView(this).apply {
            text = "• USB Status: Checking connection..."
            textSize = 11.5f
            setTextColor(Color.parseColor("#80DEEA"))
            setPadding(4, 0, 0, 16)
        }
        deployCard.addView(usbStatusBadge)

        // Wi-Fi Action Section
        val wifiHeader = TextView(this).apply {
            text = "📶 Or Install over Wi-Fi / Hotspot (Port 5555):"
            textSize = 12.5f
            setTextColor(Color.parseColor("#CFD8DC"))
            setPadding(0, 0, 0, 8)
        }
        deployCard.addView(wifiHeader)

        val ipPortRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }
        }

        ipEditText = EditText(this).apply {
            hint = "192.168.43.1"
            setText(detectDefaultHeadUnitIp())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(18, 16, 18, 16)
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f).apply {
                marginEnd = 10
            }
        }
        ipPortRow.addView(ipEditText)

        portEditText = EditText(this).apply {
            setText("5555")
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(18, 16, 18, 16)
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }
        ipPortRow.addView(portEditText)
        deployCard.addView(ipPortRow)

        val wifiToolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
        }

        testConnButton = Button(this).apply {
            text = "🔍 Test ADB"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#37474F"))
            setPadding(12, 10, 12, 10)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6
            }
            setOnClickListener { runConnectionTest() }
        }
        wifiToolsRow.addView(testConnButton)

        detectIpButton = Button(this).apply {
            text = "🔄 Auto-Detect IP"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#263238"))
            setPadding(12, 10, 12, 10)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            setOnClickListener {
                val ip = detectDefaultHeadUnitIp()
                ipEditText.setText(ip)
                Toast.makeText(this@MainActivity, "Detected Gateway IP: $ip", Toast.LENGTH_SHORT).show()
            }
        }
        wifiToolsRow.addView(detectIpButton)
        deployCard.addView(wifiToolsRow)

        installWifiButton = Button(this).apply {
            text = "⚡ Install via Wi-Fi"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2E7D32"))
                cornerRadius = 12f
            }
            setPadding(20, 18, 20, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 6) }
            setOnClickListener { startInstallation(useUsb = false) }
        }
        deployCard.addView(installWifiButton)

        wifiStatusBadge = TextView(this).apply {
            text = "• Wi-Fi Status: Ready to connect"
            textSize = 11.5f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(4, 0, 0, 0)
        }
        deployCard.addView(wifiStatusBadge)

        rootLayout.addView(deployCard)

        // --- Progress Section ---
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
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

        // --- Console Log ---
        val consoleTitle = TextView(this).apply {
            text = "💻 Deployment Console:"
            textSize = 12.5f
            setTextColor(Color.parseColor("#81C784"))
            typeface = Typeface.MONOSPACE
            setPadding(0, 8, 0, 4)
        }
        rootLayout.addView(consoleTitle)

        logScrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                420
            )
            setPadding(16, 16, 16, 16)
        }

        logTextView = TextView(this).apply {
            text = "Ready. Plug USB cable or connect via Wi-Fi to begin.\n"
            setTextColor(Color.parseColor("#A5D6A7"))
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
        }
        logScrollView.addView(logTextView)
        rootLayout.addView(logScrollView)

        setContentView(rootScrollView)

        // Register USB change notifications
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(usbReceiver, filter)

        // Initial setup
        setTargetMode(MODE_GOATSDRIVE)
        updateUsbStatus()
        updateBuildStatusLabel()
        checkForUpdates(manual = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (ignored: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        updateUsbStatus()
    }

    private fun setTargetMode(mode: Int) {
        activeTargetMode = mode
        if (mode == MODE_GOATSDRIVE) {
            tabGoatsDrive.background = GradientDrawable().apply {
                setColor(Color.parseColor("#2E7D32"))
                cornerRadius = 10f
            }
            tabGoatsDrive.setTextColor(Color.WHITE)

            tabCustomApk.background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2A2A"))
                cornerRadius = 10f
            }
            tabCustomApk.setTextColor(Color.parseColor("#B0BEC5"))

            goatsDriveContainer.visibility = View.VISIBLE
            customApkContainer.visibility = View.GONE
            installUsbButton.text = "🔌 Install GoatsDrive via USB"
            installWifiButton.text = "⚡ Install GoatsDrive via Wi-Fi"
        } else {
            tabGoatsDrive.background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2A2A"))
                cornerRadius = 10f
            }
            tabGoatsDrive.setTextColor(Color.parseColor("#B0BEC5"))

            tabCustomApk.background = GradientDrawable().apply {
                setColor(Color.parseColor("#1565C0"))
                cornerRadius = 10f
            }
            tabCustomApk.setTextColor(Color.WHITE)

            goatsDriveContainer.visibility = View.GONE
            customApkContainer.visibility = View.VISIBLE
            val appLabel = if (customApkTitle.isNotEmpty()) customApkTitle else "Custom App"
            installUsbButton.text = "🔌 Sideload $appLabel via USB"
            installWifiButton.text = "⚡ Sideload $appLabel via Wi-Fi"
        }
    }

    private fun openApkFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.android.package-archive",
                "application/octet-stream",
                "*/*"
            ))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Choose APK file from Phone"), REQUEST_PICK_APK)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not launch file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_APK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            inspectAndSetCustomApk(uri)
        }
    }

    private fun inspectAndSetCustomApk(uri: Uri) {
        customApkUri = uri
        customApkClearButton.visibility = View.VISIBLE
        customApkInfoText.text = "Reading APK details..."
        appendLog("📂 Analyzing selected file: $uri")

        Thread {
            try {
                var displayName = "custom_app.apk"
                var size = 0L

                val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex != -1) displayName = it.getString(nameIndex) ?: displayName
                        if (sizeIndex != -1) size = it.getLong(sizeIndex)
                    }
                }

                // Copy to cache to extract package details via PackageManager
                val cacheApk = File(cacheDir, "inspect_staged.apk")
                contentResolver.openInputStream(uri)?.use { inStream ->
                    FileOutputStream(cacheApk).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }

                if (size == 0L) {
                    size = cacheApk.length()
                }

                val pkgInfo = packageManager.getPackageArchiveInfo(cacheApk.absolutePath, 0)
                val packageName = pkgInfo?.packageName ?: "custom.package"
                val versionName = pkgInfo?.versionName ?: ""

                var appLabel = displayName
                try {
                    pkgInfo?.applicationInfo?.let { appInfo ->
                        appInfo.sourceDir = cacheApk.absolutePath
                        appInfo.publicSourceDir = cacheApk.absolutePath
                        appLabel = packageManager.getApplicationLabel(appInfo).toString()
                    }
                } catch (ignored: Exception) {}

                mainHandler.post {
                    customApkTitle = appLabel
                    customApkPackage = packageName
                    customApkSize = size

                    val sizeMb = String.format("%.1f MB", size.toDouble() / (1024 * 1024))
                    customApkInfoText.text = "✅ Ready: $appLabel ($sizeMb)\nPackage: $packageName | Ver: $versionName"
                    customApkInfoText.setTextColor(Color.parseColor("#A5D6A7"))

                    setTargetMode(MODE_CUSTOM_APK)
                    appendLog("📦 Loaded APK: $appLabel [$packageName] ($sizeMb)")
                }

            } catch (e: Exception) {
                mainHandler.post {
                    customApkInfoText.text = "⚠️ Selected: ${uri.lastPathSegment} (${e.message})"
                    customApkTitle = uri.lastPathSegment ?: "Custom App"
                    customApkPackage = "custom.app"
                    setTargetMode(MODE_CUSTOM_APK)
                }
            }
        }.start()
    }

    private fun clearCustomApkSelection() {
        customApkUri = null
        customApkTitle = ""
        customApkPackage = ""
        customApkSize = 0L
        customApkClearButton.visibility = View.GONE
        customApkInfoText.text = "No APK selected. Tap above to pick any .apk from your phone."
        customApkInfoText.setTextColor(Color.parseColor("#90A4AE"))
        setTargetMode(MODE_GOATSDRIVE)
    }

    private fun updateUsbStatus() {
        val detection = UsbAdbManager.detectUsbConnection(this)
        when (detection.mode) {
            UsbAdbManager.UsbMode.DIRECT_ADB_DEVICE -> {
                usbStatusBadge.text = "• USB Status: ✅ ${detection.statusMessage} (Ready to Flash)"
                usbStatusBadge.setTextColor(Color.parseColor("#81C784"))
            }
            UsbAdbManager.UsbMode.USB_TETHERING -> {
                usbStatusBadge.text = "• USB Status: ✅ ${detection.statusMessage} (Connected via Cable)"
                usbStatusBadge.setTextColor(Color.parseColor("#80D8FF"))
            }
            UsbAdbManager.UsbMode.USB_CABLE_ONLY -> {
                usbStatusBadge.text = "• USB Status: 🔌 Cable plugged in. Enable 'USB Tethering' or use OTG adapter."
                usbStatusBadge.setTextColor(Color.parseColor("#FFD54F"))
            }
            UsbAdbManager.UsbMode.DISCONNECTED -> {
                usbStatusBadge.text = "• USB Status: 🔌 Disconnected. Plug USB cable into head unit."
                usbStatusBadge.setTextColor(Color.parseColor("#B0BEC5"))
            }
        }
    }

    private fun startInstallation(useUsb: Boolean) {
        val disableZlink = zlinkCheckBox.isChecked
        val isGoatsDrive = (activeTargetMode == MODE_GOATSDRIVE)

        if (!isGoatsDrive && customApkUri == null) {
            Toast.makeText(this, "Please select an APK file from your phone first!", Toast.LENGTH_LONG).show()
            openApkFilePicker()
            return
        }

        if (useUsb) {
            handleUsbInstallation(isGoatsDrive, disableZlink)
        } else {
            handleWifiInstallation(isGoatsDrive, disableZlink)
        }
    }

    private fun handleUsbInstallation(isGoatsDrive: Boolean, disableZlink: Boolean) {
        val detection = UsbAdbManager.detectUsbConnection(this)

        when (detection.mode) {
            UsbAdbManager.UsbMode.DIRECT_ADB_DEVICE -> {
                val device = detection.device ?: return
                appendLog("🔌 Preparing direct USB ADB transfer for ${device.productName ?: device.deviceName}...")

                if (!UsbAdbManager.hasPermission(this, device)) {
                    appendLog("🔐 Requesting USB host permission from system...")
                    UsbAdbManager.requestPermission(this, device) { granted ->
                        mainHandler.post {
                            if (granted) {
                                executeTargetDeploy(UsbAdbTransport(getSystemService(Context.USB_SERVICE) as UsbManager, device), isGoatsDrive, disableZlink)
                            } else {
                                appendLog("❌ USB permission denied by user.")
                                Toast.makeText(this, "USB permission denied", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    executeTargetDeploy(UsbAdbTransport(getSystemService(Context.USB_SERVICE) as UsbManager, device), isGoatsDrive, disableZlink)
                }
            }

            UsbAdbManager.UsbMode.USB_TETHERING -> {
                val ip = detection.tetherIp ?: "192.168.42.129"
                appendLog("🔌 Connecting over active USB Tether network ($ip:5555)...")
                executeTargetDeploy(TcpAdbTransport(ip, 5555), isGoatsDrive, disableZlink)
            }

            UsbAdbManager.UsbMode.USB_CABLE_ONLY -> {
                appendLog("💡 USB cable detected, but ADB channel is not active yet:")
                appendLog("👉 Method 1: Turn on 'USB Tethering' in phone Settings (Settings > Hotspot & Tethering > USB Tethering).")
                appendLog("👉 Method 2: If using USB OTG, ensure USB Debugging is ON in Head Unit Factory Settings.")
                Toast.makeText(this, "Turn on USB Tethering or use OTG adapter to continue", Toast.LENGTH_LONG).show()
            }

            UsbAdbManager.UsbMode.DISCONNECTED -> {
                appendLog("❌ No USB connection found. Plug USB cable between phone and car head unit, or use Wi-Fi.")
                Toast.makeText(this, "Please plug USB cable into Car Head Unit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleWifiInstallation(isGoatsDrive: Boolean, disableZlink: Boolean) {
        val ip = ipEditText.text.toString().trim()
        val port = portEditText.text.toString().trim().toIntOrNull() ?: 5555

        if (ip.isEmpty()) {
            Toast.makeText(this, "Please enter Head Unit IP address", Toast.LENGTH_SHORT).show()
            return
        }

        executeTargetDeploy(TcpAdbTransport(ip, port), isGoatsDrive, disableZlink)
    }

    private fun executeTargetDeploy(transport: AdbTransport, isGoatsDrive: Boolean, disableZlink: Boolean) {
        setBusyState(true)

        if (isGoatsDrive) {
            val release = latestReleaseInfo
            if (release != null && !ApkUpdateManager.isCachedUpToDate(this, release)) {
                appendLog("⬇️ Downloading latest GoatsDrive build [${release.tagName}] from GitHub...")
                updateProgress(0, "Downloading latest APK...")

                ApkUpdateManager.downloadLatestApk(
                    context = this,
                    release = release,
                    onProgress = { pct, downloaded, total ->
                        mainHandler.post {
                            updateProgress(pct, "Downloading: $pct% (${downloaded / 1024} KB / ${total / 1024} KB)")
                        }
                    },
                    onComplete = { file, error ->
                        mainHandler.post {
                            if (file != null && file.exists()) {
                                appendLog("✅ Download verified (${file.length() / 1024} KB).")
                                updateBuildStatusLabel()
                                dispatchInstall(transport, isGoatsDrive = true, disableZlink = disableZlink)
                            } else {
                                appendLog("⚠️ Download failed ($error). Using cached/embedded APK.")
                                dispatchInstall(transport, isGoatsDrive = true, disableZlink = disableZlink)
                            }
                        }
                    }
                )
            } else {
                dispatchInstall(transport, isGoatsDrive = true, disableZlink = disableZlink)
            }
        } else {
            dispatchInstall(transport, isGoatsDrive = false, disableZlink = false)
        }
    }

    private fun dispatchInstall(transport: AdbTransport, isGoatsDrive: Boolean, disableZlink: Boolean) {
        val task: HeadUnitProvisioner.InstallTask

        if (isGoatsDrive) {
            val apkBytes = ApkUpdateManager.getBestApkBytes(this) { log -> appendLog(log) }
            if (apkBytes.isEmpty()) {
                appendLog("❌ No GoatsDrive APK binary available.")
                setBusyState(false)
                return
            }
            task = HeadUnitProvisioner.InstallTask(
                transport = transport,
                apkBytes = apkBytes,
                totalSizeBytes = apkBytes.size.toLong(),
                appTitle = "GoatsDrive",
                packageName = HeadUnitProvisioner.GOATSDRIVE_PACKAGE,
                isGoatsDrive = true,
                disableZlink = disableZlink,
                launchAfterInstall = true
            )
        } else {
            val uri = customApkUri
            if (uri == null) {
                appendLog("❌ No custom APK selected.")
                setBusyState(false)
                return
            }
            task = HeadUnitProvisioner.InstallTask(
                transport = transport,
                apkStreamProvider = { contentResolver.openInputStream(uri) },
                totalSizeBytes = customApkSize,
                appTitle = customApkTitle.ifEmpty { "Custom App" },
                packageName = customApkPackage.ifEmpty { "custom.package" },
                isGoatsDrive = false,
                disableZlink = false,
                launchAfterInstall = customLaunchCheckBox.isChecked
            )
        }

        provisioner.executeInstall(task, object : HeadUnitProvisioner.Listener {
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
                    setBusyState(false)
                    if (success) {
                        Toast.makeText(this@MainActivity, "Deployed successfully to Head Unit!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Installation failed. Check console log.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun createCardView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C1C"))
                cornerRadius = 14f
                setStroke(1, Color.parseColor("#2C2C2C"))
            }
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
                "192.168.43.1"
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
        wifiStatusBadge.text = "• Wi-Fi Status: Probing $ip:$port..."
        wifiStatusBadge.setTextColor(Color.parseColor("#FFD54F"))
        appendLog("🔍 Probing ADB on $ip:$port...")

        Thread {
            val client = AdbClient(ip, port)
            val result = client.testConnection(timeoutMs = 4500)

            mainHandler.post {
                testConnButton.isEnabled = true
                if (result.success) {
                    wifiStatusBadge.text = "• Wi-Fi Status: ✅ Connected: ${result.model} (Android ${result.androidVersion})"
                    wifiStatusBadge.setTextColor(Color.parseColor("#81C784"))
                    appendLog("✅ Head Unit Connected: ${result.model} | Android ${result.androidVersion} | Platform: ${result.platform}")
                    Toast.makeText(this, "Connected: ${result.model}!", Toast.LENGTH_SHORT).show()
                } else {
                    wifiStatusBadge.text = "• Wi-Fi Status: ❌ Cannot reach $ip:$port"
                    wifiStatusBadge.setTextColor(Color.parseColor("#E57373"))
                    appendLog("❌ Connection Failed: ${result.errorMessage}")
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

    private fun updateProgress(percent: Int, statusText: String? = null) {
        progressBar.progress = percent
        progressPercentText.text = "$percent%"
        if (statusText != null && percent % 25 == 0) {
            appendLog(statusText)
        }
    }

    private fun setBusyState(busy: Boolean) {
        installUsbButton.isEnabled = !busy
        installWifiButton.isEnabled = !busy
        testConnButton.isEnabled = !busy
        pickApkButton.isEnabled = !busy
        progressBar.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        progressPercentText.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            progressBar.progress = 0
            progressPercentText.text = "0%"
        }
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
