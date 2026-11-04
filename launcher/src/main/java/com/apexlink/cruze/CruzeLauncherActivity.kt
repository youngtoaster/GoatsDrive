package com.apexlink.cruze

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.apexlink.cruze.apps.AppDrawerManager
import com.apexlink.cruze.apps.AppInfo
import com.apexlink.cruze.can.CanBusService
import com.apexlink.cruze.can.GmDeltaProtocol
import com.apexlink.cruze.climate.ClimateController
import com.apexlink.cruze.climate.FloatingClimateOverlayService
import com.apexlink.cruze.media.MediaManager
import com.apexlink.cruze.projection.ProjectionBridge
import com.apexlink.cruze.view.Cruze3dViewportView
import java.text.SimpleDateFormat
import java.util.*

class CruzeLauncherActivity : Activity(), CanBusService.VehicleStateListener {

    private lateinit var viewport3d: Cruze3dViewportView

    // Header UI
    private lateinit var clockTimeText: TextView
    private lateinit var clockDateText: TextView
    private lateinit var extTempText: TextView
    private lateinit var batteryVoltsText: TextView

    // Right Cards UI
    private lateinit var projectionStatusText: TextView
    private lateinit var mediaTitleText: TextView
    private lateinit var mediaArtistText: TextView
    private lateinit var mediaPlayPauseBtn: Button
    private lateinit var speedText: TextView
    private lateinit var rpmText: TextView
    private lateinit var fuelText: TextView
    private lateinit var fuelProgressBar: ProgressBar

    // Bottom Dock UI
    private lateinit var dockDriverTempText: TextView
    private lateinit var dockPassTempText: TextView
    private lateinit var dockDriverSeatBtn: TextView
    private lateinit var dockPassSeatBtn: TextView
    private lateinit var dockLayout: LinearLayout

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()

        // Start hardware CAN service
        startService(Intent(this, CanBusService::class.java))

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080A0E"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Top Automotive Header Bar
        val headerBar = createHeaderBar()
        rootLayout.addView(headerBar)

        // 2. Main Middle Stage (Split: 62% 3D Car Viewport | 38% Widget Stack)
        val middleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // Left: 3D Cruze Stage
        val leftStage = create3dStage()
        middleLayout.addView(leftStage)

        // Right: Modern Automotive Cards Stack
        val rightCards = createCardsStack()
        middleLayout.addView(rightCards)

        rootLayout.addView(middleLayout)

        // 3. Bottom Tesla-Style Dock
        dockLayout = createBottomDock()
        rootLayout.addView(dockLayout)

        setContentView(rootLayout)

        // Register CAN-bus listener
        CanBusService.registerListener(this)

        // Media updates
        MediaManager.registerListener { track ->
            mediaTitleText.text = track.title
            mediaArtistText.text = track.artist
            mediaPlayPauseBtn.text = if (track.isPlaying) "⏸ Pause" else "▶ Play"
        }

        // Check overlay permission for floating climate HUD
        checkOverlayPermission()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        mainHandler.post(clockRunnable)
        AppDrawerManager.loadInstalledApps(this)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(clockRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        CanBusService.unregisterListener(this)
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    private fun updateClock() {
        val now = Date()
        clockTimeText.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
        clockDateText.text = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(now).uppercase()
    }

    private fun createHeaderBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 14, 32, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0E131A"))
                setStroke(1, Color.parseColor("#1A2330"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Brand / Model
            val brandLayout = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            val brandBadge = TextView(this@CruzeLauncherActivity).apply {
                text = "⚡ CRUZE LTZ"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#00E5FF"))
                letterSpacing = 0.1f
            }
            brandLayout.addView(brandBadge)
            addView(brandLayout)

            // Center Clock
            val clockLayout = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            }
            clockTimeText = TextView(this@CruzeLauncherActivity).apply {
                text = "12:00 PM"
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }
            clockLayout.addView(clockTimeText)

            clockDateText = TextView(this@CruzeLauncherActivity).apply {
                text = "WEDNESDAY, NOV 4"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#80D8FF"))
                letterSpacing = 0.08f
            }
            clockLayout.addView(clockDateText)
            addView(clockLayout)

            // Right Telemetry Badges (Exterior Temp & Voltage)
            val rightBadges = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            }

            extTempText = TextView(this@CruzeLauncherActivity).apply {
                text = "🌡️ 72°F"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E2E8F0"))
                setPadding(0, 0, 20, 0)
            }
            rightBadges.addView(extTempText)

            batteryVoltsText = TextView(this@CruzeLauncherActivity).apply {
                text = "🔋 14.2V"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#81C784"))
            }
            rightBadges.addView(batteryVoltsText)
            addView(rightBadges)
        }
    }

    private fun create3dStage(): RelativeLayout {
        val container = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.6f)
        }

        viewport3d = Cruze3dViewportView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            callback = object : Cruze3dViewportView.Callback {
                override fun onModelReady() {
                    Toast.makeText(this@CruzeLauncherActivity, "Cruze LTZ 3D Ready!", Toast.LENGTH_SHORT).show()
                }

                override fun onPartInteracted(part: String, isOpen: Boolean) {
                    Toast.makeText(this@CruzeLauncherActivity, "$part ${if (isOpen) "Opened" else "Closed"}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        container.addView(viewport3d)

        // Exterior Paint Color Switcher Floating Bar (Top Right of 3D Canvas)
        val colorBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 6, 8, 6)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0E141E"))
                cornerRadius = 16f
                setStroke(1, Color.parseColor("#263238"))
            }
            val p = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                setMargins(0, 16, 16, 0)
            }
            layoutParams = p
        }

        val colors = listOf(
            Pair("Summit White", "#F5F7FA"),
            Pair("Black Granite", "#121417"),
            Pair("Crystal Red", "#8A0D15"),
            Pair("Blue Topaz", "#1B4D89"),
            Pair("Silver Ice", "#C0C5CB")
        )

        colors.forEach { (name, hex) ->
            val colorPip = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(hex))
                    cornerRadius = 24f
                    setStroke(2, Color.parseColor("#FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(28, 28).apply {
                    setMargins(6, 0, 6, 0)
                }
                setOnClickListener {
                    viewport3d.setExteriorColor(hex)
                    Toast.makeText(this@CruzeLauncherActivity, "Paint: $name", Toast.LENGTH_SHORT).show()
                }
            }
            colorBar.addView(colorPip)
        }
        container.addView(colorBar)

        // Interactive Car Action Pills (Bottom Center of 3D Canvas)
        val actionsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0E141E"))
                cornerRadius = 20f
                setStroke(1, Color.parseColor("#1E293B"))
            }
            val p = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                addRule(RelativeLayout.CENTER_HORIZONTAL)
                setMargins(0, 0, 0, 16)
            }
            layoutParams = p
        }

        actionsBar.addView(createStageButton("🔓 Unlock All") {
            val state = CanBusService.vehicleState
            state.doorFL = !state.doorFL
            state.doorFR = state.doorFL
            state.doorRL = state.doorFL
            state.doorRR = state.doorFL
            CanBusService.notifyUpdate()
        })

        actionsBar.addView(createStageButton("🚗 Trunk") {
            val state = CanBusService.vehicleState
            state.trunkOpen = !state.trunkOpen
            CanBusService.notifyUpdate()
        })

        actionsBar.addView(createStageButton("💡 Lights") {
            val state = CanBusService.vehicleState
            state.headlightsOn = !state.headlightsOn
            CanBusService.notifyUpdate()
        })

        actionsBar.addView(createStageButton("🧪 CAN Sim") {
            showCanSimulatorDialog()
        })

        container.addView(actionsBar)
        return container
    }

    private fun createStageButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 14f
            }
            setPadding(14, 8, 14, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun createCardsStack(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)

            // Card 1: Projection (CarPlay / Android Auto)
            addView(createProjectionCard())

            // Card 2: Universal Media Controller
            addView(createMediaCard())

            // Card 3: Real-Time Telemetry & TPMS
            addView(createTelemetryCard())
        }
    }

    private fun createProjectionCard(): LinearLayout {
        return createBaseCard().apply {
            val headerRow = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
            }

            val icon = TextView(this@CruzeLauncherActivity).apply {
                text = "⚡"
                textSize = 16f
                setPadding(0, 0, 8, 0)
            }
            headerRow.addView(icon)

            projectionStatusText = TextView(this@CruzeLauncherActivity).apply {
                text = ProjectionBridge.getStatusLabel()
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#80D8FF"))
            }
            headerRow.addView(projectionStatusText)
            addView(headerRow)

            val launchBtn = Button(this@CruzeLauncherActivity).apply {
                text = "🚀 Open CarPlay / Android Auto"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0288D1"))
                    cornerRadius = 12f
                }
                setPadding(16, 14, 16, 14)
                setOnClickListener {
                    ProjectionBridge.launchProjection(this@CruzeLauncherActivity)
                }
            }
            addView(launchBtn)

            val overlayToggle = CheckBox(this@CruzeLauncherActivity).apply {
                text = "Keep Climate Floating HUD over CarPlay/AA"
                isChecked = true
                setTextColor(Color.parseColor("#90A4AE"))
                textSize = 11.5f
                setPadding(4, 6, 0, 0)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        FloatingClimateOverlayService.start(this@CruzeLauncherActivity)
                    } else {
                        FloatingClimateOverlayService.stop(this@CruzeLauncherActivity)
                    }
                }
            }
            addView(overlayToggle)
        }
    }

    private fun createMediaCard(): LinearLayout {
        return createBaseCard().apply {
            val header = TextView(this@CruzeLauncherActivity).apply {
                text = "🎵 AUDIO SYSTEM"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#81C784"))
                letterSpacing = 0.08f
                setPadding(0, 0, 0, 4)
            }
            addView(header)

            mediaTitleText = TextView(this@CruzeLauncherActivity).apply {
                text = MediaManager.currentTrack.title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }
            addView(mediaTitleText)

            mediaArtistText = TextView(this@CruzeLauncherActivity).apply {
                text = MediaManager.currentTrack.artist
                textSize = 12.5f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 0, 0, 10)
            }
            addView(mediaArtistText)

            // Playback controls row
            val controlsRow = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val prevBtn = createIconButton("⏮ Prev") { MediaManager.skipPrevious(this@CruzeLauncherActivity) }
            controlsRow.addView(prevBtn)

            mediaPlayPauseBtn = createIconButton("⏸ Pause") { MediaManager.togglePlayPause(this@CruzeLauncherActivity) }
            controlsRow.addView(mediaPlayPauseBtn)

            val nextBtn = createIconButton("⏭ Next") { MediaManager.skipNext(this@CruzeLauncherActivity) }
            controlsRow.addView(nextBtn)

            addView(controlsRow)
        }
    }

    private fun createTelemetryCard(): LinearLayout {
        return createBaseCard().apply {
            val header = TextView(this@CruzeLauncherActivity).apply {
                text = "🏎️ INSTRUMENT TELEMETRY & TPMS"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#00E5FF"))
                letterSpacing = 0.08f
                setPadding(0, 0, 0, 6)
            }
            addView(header)

            val gaugesRow = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
            }

            speedText = TextView(this@CruzeLauncherActivity).apply {
                text = "0 MPH"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            gaugesRow.addView(speedText)

            rpmText = TextView(this@CruzeLauncherActivity).apply {
                text = "800 RPM"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            gaugesRow.addView(rpmText)
            addView(gaugesRow)

            // Fuel Level
            val fuelRow = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 4)
            }
            fuelText = TextView(this@CruzeLauncherActivity).apply {
                text = "⛽ Fuel: 85%"
                textSize = 11.5f
                setTextColor(Color.parseColor("#CBD5E1"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            fuelRow.addView(fuelText)
            addView(fuelRow)

            fuelProgressBar = ProgressBar(this@CruzeLauncherActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 85
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    12
                ).apply { setMargins(0, 0, 0, 10) }
            }
            addView(fuelProgressBar)

            // 4-Wheel TPMS Badges
            val tpmsRow = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            tpmsRow.addView(createTpmsBadge("FL: 32 PSI"))
            tpmsRow.addView(createTpmsBadge("FR: 32 PSI"))
            tpmsRow.addView(createTpmsBadge("RL: 32 PSI"))
            tpmsRow.addView(createTpmsBadge("RR: 32 PSI"))
            addView(tpmsRow)
        }
    }

    private fun createBaseCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121820"))
                cornerRadius = 14f
                setStroke(1, Color.parseColor("#1F2937"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
        }
    }

    private fun createIconButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 10f
            }
            setPadding(14, 8, 14, 8)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun createTpmsBadge(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#80D8FF"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = 8f
            }
            setPadding(10, 6, 10, 6)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { setMargins(3, 0, 3, 0) }
            gravity = Gravity.CENTER
        }
    }

    private fun createBottomDock(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 12, 24, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0B0F15"))
                setStroke(1, Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Quick App Drawer
            addView(createDockButton("📂 All Apps") { showAppDrawerDialog() })

            // CarPlay / Android Auto Direct
            addView(createDockButton("📱 Projection") {
                ProjectionBridge.launchProjection(this@CruzeLauncherActivity)
            })

            // Full Climate Modal
            addView(createDockButton("❄️ Climate") { showFullClimateDialog() })

            // Driver Temp Quick Stepper
            val driverTempGroup = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 0, 12, 0)
            }
            dockDriverSeatBtn = TextView(this@CruzeLauncherActivity).apply {
                text = "♨️ 0"
                textSize = 13f
                setTextColor(Color.parseColor("#90A4AE"))
                setPadding(0, 0, 8, 0)
                setOnClickListener {
                    val s = ClimateController.cycleDriverSeatHeater()
                    text = "♨️ $s"
                    setTextColor(if (s > 0) Color.parseColor("#FF5722") else Color.parseColor("#90A4AE"))
                }
            }
            driverTempGroup.addView(dockDriverSeatBtn)

            driverTempGroup.addView(createStepButton("-") { ClimateController.adjustDriverTemp(-0.5f) })
            dockDriverTempText = TextView(this@CruzeLauncherActivity).apply {
                text = "70°"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(8, 0, 8, 0)
            }
            driverTempGroup.addView(dockDriverTempText)
            driverTempGroup.addView(createStepButton("+") { ClimateController.adjustDriverTemp(0.5f) })
            addView(driverTempGroup)

            // Spacer
            val spacer = View(this@CruzeLauncherActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            addView(spacer)

            // Passenger Temp Quick Stepper
            val passTempGroup = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 0, 12, 0)
            }
            passTempGroup.addView(createStepButton("-") { ClimateController.adjustPassengerTemp(-0.5f) })
            dockPassTempText = TextView(this@CruzeLauncherActivity).apply {
                text = "70°"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(8, 0, 8, 0)
            }
            passTempGroup.addView(dockPassTempText)
            passTempGroup.addView(createStepButton("+") { ClimateController.adjustPassengerTemp(0.5f) })

            dockPassSeatBtn = TextView(this@CruzeLauncherActivity).apply {
                text = "♨️ 0"
                textSize = 13f
                setTextColor(Color.parseColor("#90A4AE"))
                setPadding(8, 0, 0, 0)
                setOnClickListener {
                    val s = ClimateController.cyclePassengerSeatHeater()
                    text = "♨️ $s"
                    setTextColor(if (s > 0) Color.parseColor("#FF5722") else Color.parseColor("#90A4AE"))
                }
            }
            passTempGroup.addView(dockPassSeatBtn)
            addView(passTempGroup)

            // Master Volume
            addView(createDockButton("🔊 Volume") {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            })
        }
    }

    private fun createDockButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A2433"))
                cornerRadius = 12f
            }
            setPadding(16, 10, 16, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun createStepButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1F2937"))
                cornerRadius = 20f
            }
            layoutParams = LinearLayout.LayoutParams(54, 54)
            setOnClickListener { onClick() }
        }
    }

    // --- App Drawer Fullscreen Sheet Dialog ---
    private fun showAppDrawerDialog() {
        val apps = AppDrawerManager.loadInstalledApps(this)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E131A"))
            setPadding(32, 28, 32, 24)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val title = TextView(this).apply {
            text = "📂 Installed Applications (${apps.size})"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(title)
        dialogView.addView(headerRow)

        // Search Bar
        val searchBox = EditText(this).apply {
            hint = "Search apps..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1B2330"))
            setPadding(20, 16, 20, 16)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }
        dialogView.addView(searchBox)

        // Apps Grid Container
        val gridScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                650
            )
        }

        val gridContainer = GridLayout(this).apply {
            columnCount = 4
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val alertDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(dialogView)
            .setNegativeButton("Close", null)
            .create()

        fun populateGrid(filtered: List<AppInfo>) {
            gridContainer.removeAllViews()
            filtered.forEach { app ->
                val appCell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(16, 16, 16, 16)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#161E2A"))
                        cornerRadius = 14f
                    }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 160
                        height = 160
                        setMargins(10, 10, 10, 10)
                    }
                    setOnClickListener {
                        AppDrawerManager.launchApp(this@CruzeLauncherActivity, app)
                        alertDialog.dismiss()
                    }
                }

                val iconView = ImageView(this).apply {
                    setImageDrawable(app.icon)
                    layoutParams = LinearLayout.LayoutParams(64, 64)
                }
                appCell.addView(iconView)

                val labelView = TextView(this).apply {
                    text = app.label
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, 6, 0, 0)
                }
                appCell.addView(labelView)
                gridContainer.addView(appCell)
            }
        }

        populateGrid(apps)
        gridScrollView.addView(gridContainer)
        dialogView.addView(gridScrollView)

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateGrid(AppDrawerManager.getApps(s.toString()))
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        alertDialog.show()
    }

    // --- Full HVAC Climate Control Dialog ---
    private fun showFullClimateDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E131A"))
            setPadding(36, 28, 36, 28)
        }

        val title = TextView(this).apply {
            text = "❄️ Cruze LTZ Climate Control System"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        dialogView.addView(title)

        // Temperatures Row
        val tempRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        val driverLabel = TextView(this).apply {
            text = "Driver Temp: ${ClimateController.formatTemp(ClimateController.state.driverTempF)}"
            textSize = 15f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tempRow.addView(driverLabel)

        val passLabel = TextView(this).apply {
            text = "Passenger: ${ClimateController.formatTemp(ClimateController.state.passengerTempF)}"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tempRow.addView(passLabel)
        dialogView.addView(tempRow)

        // Action Buttons Row (Auto, Max AC, Recirc, Defrost)
        val actRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 20)
        }
        actRow.addView(createStageButton("Auto") { ClimateController.toggleAuto() })
        actRow.addView(createStageButton("Max A/C") { ClimateController.toggleMaxAc() })
        actRow.addView(createStageButton("Recirc") { ClimateController.toggleRecirculation() })
        actRow.addView(createStageButton("Rear Defrost") { ClimateController.toggleRearDefrost() })
        actRow.addView(createStageButton("Front Defrost") { ClimateController.toggleFrontDefrost() })
        dialogView.addView(actRow)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(dialogView)
            .setPositiveButton("Done", null)
            .show()
    }

    // --- Interactive CAN-Bus Testing Simulator Dialog ---
    private fun showCanSimulatorDialog() {
        val state = CanBusService.vehicleState

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E131A"))
            setPadding(32, 24, 32, 24)
        }

        val title = TextView(this).apply {
            text = "🧪 Interactive CAN-Bus Hardware Simulator"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00E5FF"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(title)

        fun addToggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val cb = CheckBox(this).apply {
                text = label
                isChecked = get()
                setTextColor(Color.WHITE)
                textSize = 13f
                setOnCheckedChangeListener { _, c ->
                    set(c)
                    CanBusService.notifyUpdate()
                }
            }
            layout.addView(cb)
        }

        addToggle("Driver Door (FL)", { state.doorFL }, { state.doorFL = it })
        addToggle("Passenger Door (FR)", { state.doorFR }, { state.doorFR = it })
        addToggle("Rear Left Door (RL)", { state.doorRL }, { state.doorRL = it })
        addToggle("Rear Right Door (RR)", { state.doorRR }, { state.doorRR = it })
        addToggle("Trunk Lid Open", { state.trunkOpen }, { state.trunkOpen = it })
        addToggle("Headlights On", { state.headlightsOn }, { state.headlightsOn = it })
        addToggle("Brake Pedal Pressed", { state.brakePressed }, { state.brakePressed = it })

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(layout)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try { startActivity(intent) } catch (ignored: Exception) {}
        }
    }

    // --- CAN-Bus State Updates Callback ---
    override fun onVehicleStateUpdated(state: GmDeltaProtocol.VehicleState) {
        mainHandler.post {
            // Update 3D Model Doors
            viewport3d.setDoor("FL", state.doorFL)
            viewport3d.setDoor("FR", state.doorFR)
            viewport3d.setDoor("RL", state.doorRL)
            viewport3d.setDoor("RR", state.doorRR)

            // Update 3D Model Lights
            viewport3d.setLights(state.headlightsOn, state.brakePressed, state.turnSignalLeft, state.turnSignalRight)

            // Update 3D Model Steering & Speed
            viewport3d.setSteeringAngle(state.steeringAngleDeg)
            viewport3d.setSpeed(state.speedMph)

            // Update Digital Gauges
            speedText.text = "${state.speedMph} MPH"
            rpmText.text = "${state.engineRpm} RPM"
            fuelText.text = "⛽ Fuel: ${state.fuelPercent}%"
            fuelProgressBar.progress = state.fuelPercent
            extTempText.text = "🌡️ ${state.exteriorTempF}°F"
            batteryVoltsText.text = "🔋 ${String.format("%.1f", state.batteryVolts)}V"
        }
    }

    override fun onClimateStateUpdated(climate: GmDeltaProtocol.ClimateState) {
        mainHandler.post {
            dockDriverTempText.text = ClimateController.formatTemp(climate.driverTempF)
            dockPassTempText.text = ClimateController.formatTemp(climate.passengerTempF)
        }
    }
}
