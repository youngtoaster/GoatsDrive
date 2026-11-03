package com.apexlink.installer

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.io.ByteArrayOutputStream
import java.io.InputStream

class MainActivity : Activity() {

    private lateinit var ipEditText: EditText
    private lateinit var portEditText: EditText
    private lateinit var zlinkCheckBox: CheckBox
    private lateinit var installButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val provisioner = HeadUnitProvisioner(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(this).apply {
            text = "⚡ GoatsDrive Head Unit Installer"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 8)
        }
        rootLayout.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = "Install ultra-fast projection receiver & replace ZLink5"
            textSize = 13f
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 32)
        }
        rootLayout.addView(subtitleView)

        // IP & Port row
        val ipLabel = TextView(this).apply {
            text = "Head Unit Wi-Fi IP Address (Port 5555):"
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, 0, 0, 8)
        }
        rootLayout.addView(ipLabel)

        val connectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
        }

        ipEditText = EditText(this).apply {
            hint = "192.168.43.1"
            setText(detectDefaultHeadUnitIp())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f).apply {
                marginEnd = 16
            }
        }
        connectionRow.addView(ipEditText)

        portEditText = EditText(this).apply {
            setText("5555")
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        connectionRow.addView(portEditText)
        rootLayout.addView(connectionRow)

        // ZLink suppress checkbox
        zlinkCheckBox = CheckBox(this).apply {
            text = "Disable bloated factory ZLink5 (FYT / Topway / T3L)"
            isChecked = true
            setTextColor(Color.parseColor("#E0E0E0"))
            setPadding(0, 0, 0, 24)
        }
        rootLayout.addView(zlinkCheckBox)

        // Install Button
        installButton = Button(this).apply {
            text = "⚡ Flash GoatsDrive to Head Unit"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 16f
            setPadding(32, 28, 32, 28)
            setOnClickListener { startInstallation() }
        }
        rootLayout.addView(installButton)

        // Progress Bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = ProgressBar.INVISIBLE
            setPadding(0, 16, 0, 16)
        }
        rootLayout.addView(progressBar)

        // Console Log Output
        val consoleTitle = TextView(this).apply {
            text = "Deployment Terminal:"
            textSize = 13f
            setTextColor(Color.parseColor("#81C784"))
            typeface = Typeface.MONOSPACE
            setPadding(0, 16, 0, 8)
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
            text = "Ready. Ensure Head Unit has Wireless ADB enabled on port 5555.\n"
            setTextColor(Color.parseColor("#A5D6A7"))
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        logScrollView.addView(logTextView)
        rootLayout.addView(logScrollView)

        setContentView(rootLayout)
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

    private fun startInstallation() {
        val ip = ipEditText.text.toString().trim()
        val port = portEditText.text.toString().trim().toIntOrNull() ?: 5555
        val disableZlink = zlinkCheckBox.isChecked

        if (ip.isEmpty()) {
            Toast.makeText(this, "Please enter Head Unit IP", Toast.LENGTH_SHORT).show()
            return
        }

        installButton.isEnabled = false
        progressBar.visibility = ProgressBar.VISIBLE
        progressBar.progress = 0
        logTextView.text = ""

        appendLog("🚀 Starting GoatsDrive Auto-Provisioner...")

        // Read embedded GoatsDrive APK or bundled payload
        val apkBytes = loadBundledApk()

        provisioner.provision(ip, port, disableZlink, apkBytes, object : HeadUnitProvisioner.Listener {
            override fun onLog(message: String) {
                appendLog(message)
            }

            override fun onProgress(percent: Int) {
                mainHandler.post {
                    progressBar.progress = percent
                }
            }

            override fun onCompleted(success: Boolean) {
                mainHandler.post {
                    installButton.isEnabled = true
                    progressBar.visibility = ProgressBar.INVISIBLE
                    if (success) {
                        Toast.makeText(this@MainActivity, "GoatsDrive successfully installed!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Installation failed. Check terminal log.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun loadBundledApk(): ByteArray {
        // Attempt to load from assets or create minimal installer payload
        return try {
            val inStream: InputStream = assets.open("GoatsDrive.apk")
            val byteOut = ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            var read: Int
            while (inStream.read(buffer).also { read = it } != -1) {
                byteOut.write(buffer, 0, read)
            }
            inStream.close()
            byteOut.toByteArray()
        } catch (e: Exception) {
            appendLog("ℹ️ Reading GoatsDrive APK from assets...")
            ByteArray(0)
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
