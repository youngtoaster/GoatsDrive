package com.apexlink.cruze

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import java.util.Date
import java.util.Locale

/**
 * Next-Gen Tesla/Rivian-inspired Automotive Home Launcher for vertical portrait head units
 * in the 2012 Chevrolet Cruze LTZ.
 *
 * Fully responsive, zero-lag CAN-bus integration, full-screen immersive mode, and complete
 * bottom climate console replicating and upgrading the factory HVAC touch panel.
 */
class CruzeLauncherActivity : Activity(), CanBusService.VehicleStateListener {

    private val mainHandler = Handler(Looper.getMainLooper())

    // 3D Viewport
    private lateinit var cruzeViewport: Cruze3dViewportView

    // Header Views
    private lateinit var clockTimeText: TextView
    private lateinit var clockDateText: TextView
    private lateinit var outsideTempText: TextView
    private lateinit var batteryVoltageText: TextView
    private lateinit var phoneStatusBadge: TextView
    private lateinit var centralLockBadge: TextView

    // Projection Hero Card
    private lateinit var projectionStatusLabel: TextView
    private lateinit var projectionLaunchBtn: Button
    private lateinit var climateOverlaySwitch: CheckBox

    // Media Card
    private lateinit var mediaTitleText: TextView
    private lateinit var mediaArtistText: TextView
    private lateinit var mediaPlayPauseBtn: Button

    // Telemetry & TPMS Card
    private lateinit var speedGaugeText: TextView
    private lateinit var rpmGaugeText: TextView
    private lateinit var fuelProgressBar: ProgressBar
    private lateinit var fuelPercentText: TextView
    private lateinit var tpmsFLText: TextView
    private lateinit var tpmsFRText: TextView
    private lateinit var tpmsRLText: TextView
    private lateinit var tpmsRRText: TextView

    // Bottom Luxury HVAC & Media Console (Replicating and upgrading factory bottom bar)
    private lateinit var volumeLevelText: TextView
    private lateinit var muteBtn: Button
    private lateinit var hvacPowerBtn: Button
    private lateinit var dualSyncBtn: Button
    private lateinit var acCompressorBtn: Button
    private lateinit var maxAcBtn: Button
    private lateinit var recircBtn: Button
    private lateinit var autoClimateBtn: Button
    private lateinit var ventModeBtn: Button
    private lateinit var frontDefrostBtn: Button
    private lateinit var rearDefrostBtn: Button
    private lateinit var driverTempText: TextView
    private lateinit var passengerTempText: TextView
    private lateinit var driverSeatHeaterBtn: Button
    private lateinit var passengerSeatHeaterBtn: Button
    private lateinit var fanSpeedBarsContainer: LinearLayout
    private lateinit var fanSpeedLevelLabel: TextView

    // Clock updater
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val mediaListener: (MediaManager.TrackInfo) -> Unit = { track ->
        runOnUiThread {
            mediaTitleText.text = track.title
            mediaArtistText.text = track.artist
            mediaPlayPauseBtn.text = if (track.isPlaying) "⏸ Pause" else "▶ Play"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFullscreenFlags()

        // Start hardware CAN background service
        startService(Intent(this, CanBusService::class.java))

        // Suppress native Chinese MCU bottom bar overlay if present
        CanBusService.hideFactoryBottomBar(this)

        val rootScrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#07080B")) // Deep luxury obsidian
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#07080B"))
            setPadding(0, 0, 0, 16)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootScrollView.addView(rootLayout)

        // 1. TOP AUTOMOTIVE STATUS BAR (Clock, Date, Outside Temp, Battery, Central Locks)
        rootLayout.addView(buildTopStatusBar())

        // 2. 3D CRUZE LTZ VIEWPORT STAGE (Instant 0ms procedural load + interactive doors)
        rootLayout.addView(build3dCruzeStage())

        // 3. PRIMARY HERO ROW (CarPlay / Android Auto + Media Player)
        rootLayout.addView(buildHeroRow())

        // 4. SECONDARY ROW (Instruments / TPMS + Vehicle BCM Controls)
        rootLayout.addView(buildSecondaryRow())

        // 5. MASTER HVAC CONSOLE (Complete, luxury copy & upgrade of factory bottom bar)
        rootLayout.addView(buildMasterClimateConsole())

        setContentView(rootScrollView)

        // Register CAN-bus listener & start clock
        CanBusService.registerListener(this)
        mainHandler.post(clockRunnable)

        // Listen for media changes
        MediaManager.registerListener(mediaListener)
    }

    override fun onResume() {
        super.onResume()
        applyFullscreenFlags()
        CanBusService.hideFactoryBottomBar(this)
        updateProjectionUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        CanBusService.unregisterListener(this)
        mainHandler.removeCallbacks(clockRunnable)
        MediaManager.unregisterListener(mediaListener)
    }

    @Suppress("DEPRECATION")
    private fun applyFullscreenFlags() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyFullscreenFlags()
            CanBusService.hideFactoryBottomBar(this)
        }
    }

    // ==========================================
    // 1. TOP AUTOMOTIVE STATUS BAR
    // ==========================================
    private fun buildTopStatusBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 10, 20, 10)
            setBackgroundColor(Color.parseColor("#0C0E14"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Vehicle Badge & Central Lock Toggle
            centralLockBadge = TextView(this@CruzeLauncherActivity).apply {
                text = "⚡ CRUZE LTZ  🔒"
                setTextColor(Color.parseColor("#00E5FF"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(10, 5, 10, 5)
                background = createPillDrawable("#15202B", "#00E5FF")
                setOnClickListener {
                    CanBusService.toggleDoorLocks(this@CruzeLauncherActivity)
                }
            }
            addView(centralLockBadge)

            // Centered Clock & Date
            val clockContainer = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)

                clockTimeText = TextView(this@CruzeLauncherActivity).apply {
                    text = "12:00 PM"
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                }
                addView(clockTimeText)

                clockDateText = TextView(this@CruzeLauncherActivity).apply {
                    text = "THURSDAY, NOV 5"
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    letterSpacing = 0.08f
                }
                addView(clockDateText)
            }
            addView(clockContainer)

            // Outside Temp, Alternator Battery, and °F/°C Toggle
            val statusCluster = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                outsideTempText = TextView(this@CruzeLauncherActivity).apply {
                    text = "72°F"
                    setTextColor(Color.parseColor("#E2E8F0"))
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(12, 6, 8, 6)
                    setOnClickListener {
                        ClimateController.toggleMetricUnits(this@CruzeLauncherActivity)
                    }
                }
                addView(outsideTempText)

                batteryVoltageText = TextView(this@CruzeLauncherActivity).apply {
                    text = "14.2V"
                    setTextColor(Color.parseColor("#10B981"))
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(8, 6, 12, 6)
                }
                addView(batteryVoltageText)

                val carSettingsIcon = TextView(this@CruzeLauncherActivity).apply {
                    text = "⚙"
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 16f
                    setPadding(10, 4, 10, 4)
                    setOnClickListener {
                        CanBusService.openNativeCarSettings(this@CruzeLauncherActivity)
                    }
                }
                addView(carSettingsIcon)
            }
            addView(statusCluster)
        }
    }

    private fun updateClock() {
        val now = Date()
        val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
        val dateFmt = SimpleDateFormat("EEEE, MMM d", Locale.US)
        clockTimeText.text = timeFmt.format(now).uppercase()
        clockDateText.text = dateFmt.format(now).uppercase()
    }

    // ==========================================
    // 2. 3D CRUZE STAGE & QUICK ACTIONS
    // ==========================================
    private fun build3dCruzeStage(): View {
        val stageContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(320)
            )
            setBackgroundColor(Color.parseColor("#090B10"))
        }

        // Hardware-Accelerated 3D WebGL Viewport
        cruzeViewport = Cruze3dViewportView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            callback = object : Cruze3dViewportView.Callback {
                override fun onModelReady() {}
                override fun onPartInteracted(part: String, isOpen: Boolean) {
                    when (part) {
                        "FL" -> CanBusService.vehicleState.doorFL = isOpen
                        "FR" -> CanBusService.vehicleState.doorFR = isOpen
                        "RL" -> CanBusService.vehicleState.doorRL = isOpen
                        "RR" -> CanBusService.vehicleState.doorRR = isOpen
                        "trunk" -> CanBusService.vehicleState.trunkOpen = isOpen
                    }
                    CanBusService.notifyUpdate()
                }
            }
        }
        stageContainer.addView(cruzeViewport)

        // Top Overlay: Quick Vehicle Action Buttons (Doors, Trunk, Fog, Hazards)
        val actionOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12, 10, 12, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )

            addView(createQuickActionPill("DOORS") {
                val anyOpen = CanBusService.vehicleState.doorFL || CanBusService.vehicleState.doorFR
                val next = !anyOpen
                CanBusService.vehicleState.doorFL = next
                CanBusService.vehicleState.doorFR = next
                cruzeViewport.setDoor("FL", next)
                cruzeViewport.setDoor("FR", next)
                CanBusService.notifyUpdate()
            })

            addView(createQuickActionPill("TRUNK") {
                CanBusService.popTrunk(this@CruzeLauncherActivity)
                cruzeViewport.setDoor("trunk", CanBusService.vehicleState.trunkOpen)
            })

            addView(createQuickActionPill("LIGHTS") {
                val next = !CanBusService.vehicleState.headlightsOn
                CanBusService.vehicleState.headlightsOn = next
                cruzeViewport.setLights(next, brakeOn = false, leftSignal = false, rightSignal = false)
                CanBusService.notifyUpdate()
            })

            addView(createQuickActionPill("FOG") {
                CanBusService.toggleFogLights(this@CruzeLauncherActivity)
            })

            addView(createQuickActionPill("HAZARD") {
                CanBusService.toggleHazardLights(this@CruzeLauncherActivity)
            })
        }
        stageContainer.addView(actionOverlay)

        return stageContainer
    }

    private fun createQuickActionPill(text: String, onClick: () -> Unit): View {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(14, 6, 14, 6)
            background = createPillDrawable("#151B26", "#2D3748")
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4, 0, 4, 0) }
            layoutParams = params
            setOnClickListener {
                it.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).withEndAction {
                    it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(70).start()
                }.start()
                onClick()
            }
        }
    }

    // ==========================================
    // 3. PRIMARY HERO ROW (CarPlay / AA + Audio)
    // ==========================================
    private fun buildHeroRow(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(14, 10, 14, 6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Card 1: CarPlay & Android Auto Hero Card
            val projectionCard = createCardLayout().apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                    setMargins(0, 0, 6, 0)
                }

                addView(createCardHeader("PHONE PROJECTION", "#00E5FF"))

                projectionStatusLabel = TextView(this@CruzeLauncherActivity).apply {
                    text = "● Connected via USB / Bluetooth"
                    setTextColor(Color.parseColor("#10B981"))
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 4, 0, 8)
                }
                addView(projectionStatusLabel)

                // The Clean, Modern Projection Button (CarPlay / Android Auto)
                projectionLaunchBtn = Button(this@CruzeLauncherActivity).apply {
                    text = "⚡ CarPlay / Android Auto"
                    setTextColor(Color.parseColor("#08090E"))
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    background = createGradientDrawable("#00E5FF", "#10B981", 12f)
                    setPadding(12, 14, 12, 14)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(48)
                    )
                    setOnClickListener {
                        it.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).withEndAction {
                            it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(70).start()
                        }.start()
                        ProjectionBridge.launchProjection(this@CruzeLauncherActivity)
                    }
                }
                addView(projectionLaunchBtn)

                climateOverlaySwitch = CheckBox(this@CruzeLauncherActivity).apply {
                    text = "Keep Climate Floating HUD over CarPlay/AA"
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 10f
                    isChecked = true
                    setPadding(6, 6, 0, 0)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) FloatingClimateOverlayService.start(this@CruzeLauncherActivity)
                        else FloatingClimateOverlayService.stop(this@CruzeLauncherActivity)
                    }
                }
                addView(climateOverlaySwitch)
            }
            addView(projectionCard)

            // Card 2: Audio System Media Card
            val mediaCard = createCardLayout().apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                    setMargins(6, 0, 0, 0)
                }

                addView(createCardHeader("AUDIO SYSTEM", "#38BDF8"))

                mediaTitleText = TextView(this@CruzeLauncherActivity).apply {
                    text = "Cruze Audio"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    setPadding(0, 4, 0, 2)
                }
                addView(mediaTitleText)

                mediaArtistText = TextView(this@CruzeLauncherActivity).apply {
                    text = "Bluetooth Connected"
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 11f
                    maxLines = 1
                    setPadding(0, 0, 0, 8)
                }
                addView(mediaArtistText)

                // Playback Transport Buttons
                val transportRow = LinearLayout(this@CruzeLauncherActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(44)
                    )

                    val prevBtn = createTactileButton("⏮") {
                        MediaManager.skipPrevious(this@CruzeLauncherActivity)
                    }
                    addView(prevBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(0, 0, 4, 0) })

                    mediaPlayPauseBtn = createTactileButton("▶ Play") {
                        MediaManager.togglePlayPause(this@CruzeLauncherActivity)
                    }
                    addView(mediaPlayPauseBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.4f).apply { setMargins(2, 0, 2, 0) })

                    val nextBtn = createTactileButton("⏭") {
                        MediaManager.skipNext(this@CruzeLauncherActivity)
                    }
                    addView(nextBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(4, 0, 0, 0) })
                }
                addView(transportRow)
            }
            addView(mediaCard)
        }
    }

    private fun updateProjectionUI() {
        val installed = ProjectionBridge.findInstalledProjectionPackage(this)
        projectionLaunchBtn.text = "⚡ CarPlay / Android Auto"
        if (installed != null) {
            projectionStatusLabel.text = "● Ready to Connect (USB / BT)"
            projectionStatusLabel.setTextColor(Color.parseColor("#10B981"))
        } else {
            projectionStatusLabel.text = "○ Connect Phone via USB or BT"
            projectionStatusLabel.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    // ==========================================
    // 4. SECONDARY ROW (Telemetry & Car Settings)
    // ==========================================
    private fun buildSecondaryRow(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(14, 6, 14, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Card 3: Instruments & 4-Wheel TPMS
            val telemetryCard = createCardLayout().apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                    setMargins(0, 0, 6, 0)
                }

                addView(createCardHeader("INSTRUMENTS & TPMS", "#F59E0B"))

                val gaugesRow = LinearLayout(this@CruzeLauncherActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 4, 0, 4)

                    speedGaugeText = TextView(this@CruzeLauncherActivity).apply {
                        text = "0 MPH"
                        setTextColor(Color.WHITE)
                        textSize = 18f
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                    }
                    addView(speedGaugeText)

                    rpmGaugeText = TextView(this@CruzeLauncherActivity).apply {
                        text = "0 RPM"
                        setTextColor(Color.parseColor("#94A3B8"))
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                    }
                    addView(rpmGaugeText)
                }
                addView(gaugesRow)

                // Fuel bar
                val fuelRow = LinearLayout(this@CruzeLauncherActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 2, 0, 6)

                    fuelProgressBar = ProgressBar(this@CruzeLauncherActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        progress = 85
                        max = 100
                        layoutParams = LinearLayout.LayoutParams(0, dpToPx(6), 1.0f)
                    }
                    addView(fuelProgressBar)

                    fuelPercentText = TextView(this@CruzeLauncherActivity).apply {
                        text = " 85%"
                        setTextColor(Color.parseColor("#F59E0B"))
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    addView(fuelPercentText)
                }
                addView(fuelRow)

                // 4-Wheel TPMS Grid
                val tpmsGrid = LinearLayout(this@CruzeLauncherActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, 4, 0, 0)

                    tpmsFLText = createTpmsLabel("FL: 32")
                    tpmsFRText = createTpmsLabel("FR: 32")
                    tpmsRLText = createTpmsLabel("RL: 32")
                    tpmsRRText = createTpmsLabel("RR: 32")

                    addView(tpmsFLText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(tpmsFRText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(tpmsRLText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(tpmsRRText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
                addView(tpmsGrid)
            }
            addView(telemetryCard)

            // Card 4: Vehicle BCM Controls & Factory Car Settings
            val bcmCard = createCardLayout().apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                    setMargins(6, 0, 0, 0)
                }

                addView(createCardHeader("VEHICLE SYSTEMS (GM BCM)", "#10B981"))

                val btnRow1 = LinearLayout(this@CruzeLauncherActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 4, 0, 4)

                    val lockBtn = createTactileButton("🔒 Lock") {
                        CanBusService.toggleDoorLocks(this@CruzeLauncherActivity)
                    }
                    val trunkBtn = createTactileButton("🚗 Trunk") {
                        CanBusService.popTrunk(this@CruzeLauncherActivity)
                    }
                    addView(lockBtn, LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply { setMargins(0, 0, 4, 0) })
                    addView(trunkBtn, LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply { setMargins(4, 0, 0, 0) })
                }
                addView(btnRow1)

                val btnRow2 = LinearLayout(this@CruzeLauncherActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 2, 0, 4)

                    val fogBtn = createTactileButton("💡 Fog Lamps") {
                        CanBusService.toggleFogLights(this@CruzeLauncherActivity)
                    }
                    val hazardBtn = createTactileButton("⚠ Hazards") {
                        CanBusService.toggleHazardLights(this@CruzeLauncherActivity)
                    }
                    addView(fogBtn, LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply { setMargins(0, 0, 4, 0) })
                    addView(hazardBtn, LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply { setMargins(4, 0, 0, 0) })
                }
                addView(btnRow2)

                // Factory Car Settings launcher button
                val factorySettingsBtn = Button(this@CruzeLauncherActivity).apply {
                    text = "⚙ Factory Car Settings"
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    background = createButtonDrawable("#1E293B", "#334155", 8f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(36)
                    ).apply { setMargins(0, 4, 0, 0) }
                    setOnClickListener {
                        CanBusService.openNativeCarSettings(this@CruzeLauncherActivity)
                    }
                }
                addView(factorySettingsBtn)
            }
            addView(bcmCard)
        }
    }

    private fun createTpmsLabel(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(4, 4, 4, 4)
            background = createPillDrawable("#151D28", "#1E293B")
        }
    }

    // ==========================================
    // 5. MASTER HVAC CLIMATE CONSOLE (Complete Upgrade of Factory Bottom Bar)
    // ==========================================
    private fun buildMasterClimateConsole(): View {
        val consoleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0C0E14"))
            setPadding(12, 10, 12, 12)
            background = createCardDrawable("#0C0E14", "#1E293B", 16f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 4, 14, 14) }
            layoutParams = params
        }

        // Section Title & Units Toggle
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 8)

            val title = TextView(this@CruzeLauncherActivity).apply {
                text = "AUTOMOTIVE CLIMATE & COCKPIT CONSOLE"
                setTextColor(Color.parseColor("#00E5FF"))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(title)

            val unitSwitch = TextView(this@CruzeLauncherActivity).apply {
                text = if (CanBusService.vehicleState.useMetricUnits) "UNIT: °C (METRIC)" else "UNIT: °F (IMPERIAL)"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(10, 4, 10, 4)
                background = createPillDrawable("#15202B", "#00E5FF")
                setOnClickListener {
                    ClimateController.toggleMetricUnits(this@CruzeLauncherActivity)
                    text = if (CanBusService.vehicleState.useMetricUnits) "UNIT: °C (METRIC)" else "UNIT: °F (IMPERIAL)"
                }
            }
            addView(unitSwitch)
        }
        consoleLayout.addView(titleRow)

        // ROW A: MASTER HVAC MODE STRIP (OFF, DUAL, A/C, MAX A/C, RECIRC, AUTO, VENT, DEFROST)
        val modeStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(44)
            )

            hvacPowerBtn = createHvacModeBtn("OFF", active = false) {
                ClimateController.togglePower(this@CruzeLauncherActivity)
            }
            addView(hvacPowerBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(2, 0, 2, 0) })

            dualSyncBtn = createHvacModeBtn("DUAL", active = true) {
                ClimateController.toggleDual(this@CruzeLauncherActivity)
            }
            addView(dualSyncBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(2, 0, 2, 0) })

            acCompressorBtn = createHvacModeBtn("❄ A/C", active = true) {
                ClimateController.toggleAc(this@CruzeLauncherActivity)
            }
            addView(acCompressorBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(2, 0, 2, 0) })

            maxAcBtn = createHvacModeBtn("MAX A/C", active = false) {
                ClimateController.toggleMaxAc(this@CruzeLauncherActivity)
            }
            addView(maxAcBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f).apply { setMargins(2, 0, 2, 0) })

            recircBtn = createHvacModeBtn("↻ RECIRC", active = false) {
                ClimateController.toggleRecirculation(this@CruzeLauncherActivity)
            }
            addView(recircBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f).apply { setMargins(2, 0, 2, 0) })

            autoClimateBtn = createHvacModeBtn("AUTO", active = true) {
                ClimateController.toggleAuto(this@CruzeLauncherActivity)
            }
            addView(autoClimateBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(2, 0, 2, 0) })

            ventModeBtn = createHvacModeBtn("VENT", active = true) {
                ClimateController.cycleAirDirection(this@CruzeLauncherActivity)
            }
            addView(ventModeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(2, 0, 2, 0) })

            frontDefrostBtn = createHvacModeBtn("DEFROST", active = false) {
                ClimateController.toggleFrontDefrost(this@CruzeLauncherActivity)
            }
            addView(frontDefrostBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f).apply { setMargins(2, 0, 2, 0) })
        }
        consoleLayout.addView(modeStrip)

        // ROW B: COMFORT PODS (Driver Temp, Heated Seats, Blower Speed, Passenger Temp, Volume)
        val comfortRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // 1. VOLUME POD (Vol +, Level, Vol -)
            val volPod = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(8, 6, 8, 6)
                background = createCardDrawable("#121620", "#1E293B", 10f)
                layoutParams = LinearLayout.LayoutParams(dpToPx(72), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 6, 0)
                }

                val volUpBtn = createMicroButton("▲ VOL") {
                    CanBusService.adjustVolume(this@CruzeLauncherActivity, 1)
                }
                addView(volUpBtn)

                volumeLevelText = TextView(this@CruzeLauncherActivity).apply {
                    text = "14"
                    setTextColor(Color.parseColor("#00E5FF"))
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, 4, 0, 4)
                }
                addView(volumeLevelText)

                val volDownBtn = createMicroButton("▼ VOL") {
                    CanBusService.adjustVolume(this@CruzeLauncherActivity, -1)
                }
                addView(volDownBtn)
            }
            addView(volPod)

            // 2. DRIVER TEMPERATURE & SEAT HEATER POD
            val driverPod = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(8, 6, 8, 6)
                background = createCardDrawable("#121620", "#1E293B", 10f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                    setMargins(2, 0, 4, 0)
                }

                val driverHeader = TextView(this@CruzeLauncherActivity).apply {
                    text = "DRIVER"
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                }
                addView(driverHeader)

                driverTempText = TextView(this@CruzeLauncherActivity).apply {
                    text = "22.5°C"
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, 2, 0, 4)
                }
                addView(driverTempText)

                // Temp Steppers
                val steppers = LinearLayout(this@CruzeLauncherActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER

                    val minusBtn = createMicroButton("−") {
                        ClimateController.adjustDriverTemp(this@CruzeLauncherActivity, -1.0f)
                    }
                    val plusBtn = createMicroButton("+") {
                        ClimateController.adjustDriverTemp(this@CruzeLauncherActivity, 1.0f)
                    }
                    addView(minusBtn, LinearLayout.LayoutParams(dpToPx(38), dpToPx(32)).apply { setMargins(0, 0, 4, 0) })
                    addView(plusBtn, LinearLayout.LayoutParams(dpToPx(38), dpToPx(32)))
                }
                addView(steppers)

                // 3-Stage Heated Seat Button (0 -> 3 -> 2 -> 1 -> 0)
                driverSeatHeaterBtn = Button(this@CruzeLauncherActivity).apply {
                    text = "💺 SEAT 0"
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    background = createButtonDrawable("#1A202C", "#2D3748", 6f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(32)
                    ).apply { setMargins(0, 6, 0, 0) }
                    setOnClickListener {
                        ClimateController.cycleDriverSeatHeater(this@CruzeLauncherActivity)
                    }
                }
                addView(driverSeatHeaterBtn)
            }
            addView(driverPod)

            // 3. CENTER BLOWER FAN SPEED CONTROLLER
            val fanPod = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(8, 6, 8, 6)
                background = createCardDrawable("#121620", "#1E293B", 10f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply {
                    setMargins(2, 0, 2, 0)
                }

                fanSpeedLevelLabel = TextView(this@CruzeLauncherActivity).apply {
                    text = "FAN SPEED 3"
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                }
                addView(fanSpeedLevelLabel)

                // 8-Segment Fan Speed LED Bar
                fanSpeedBarsContainer = LinearLayout(this@CruzeLauncherActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, 8, 0, 8)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(24)
                    )
                    renderFanSpeedSegments(this, 3)
                }
                addView(fanSpeedBarsContainer)

                // Fan Speed Steppers
                val fanSteppers = LinearLayout(this@CruzeLauncherActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER

                    val fanDown = createMicroButton("FAN −") {
                        ClimateController.stepFanSpeed(this@CruzeLauncherActivity, -1)
                    }
                    val fanUp = createMicroButton("FAN +") {
                        ClimateController.stepFanSpeed(this@CruzeLauncherActivity, 1)
                    }
                    addView(fanDown, LinearLayout.LayoutParams(0, dpToPx(32), 1f).apply { setMargins(0, 0, 4, 0) })
                    addView(fanUp, LinearLayout.LayoutParams(0, dpToPx(32), 1f).apply { setMargins(4, 0, 0, 0) })
                }
                addView(fanSteppers)
            }
            addView(fanPod)

            // 4. PASSENGER TEMPERATURE & SEAT HEATER POD
            val passPod = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(8, 6, 8, 6)
                background = createCardDrawable("#121620", "#1E293B", 10f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                    setMargins(4, 0, 2, 0)
                }

                val passHeader = TextView(this@CruzeLauncherActivity).apply {
                    text = "PASSENGER"
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                }
                addView(passHeader)

                passengerTempText = TextView(this@CruzeLauncherActivity).apply {
                    text = "22.5°C"
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, 2, 0, 4)
                }
                addView(passengerTempText)

                // Temp Steppers
                val steppers = LinearLayout(this@CruzeLauncherActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER

                    val minusBtn = createMicroButton("−") {
                        ClimateController.adjustPassengerTemp(this@CruzeLauncherActivity, -1.0f)
                    }
                    val plusBtn = createMicroButton("+") {
                        ClimateController.adjustPassengerTemp(this@CruzeLauncherActivity, 1.0f)
                    }
                    addView(minusBtn, LinearLayout.LayoutParams(dpToPx(38), dpToPx(32)).apply { setMargins(0, 0, 4, 0) })
                    addView(plusBtn, LinearLayout.LayoutParams(dpToPx(38), dpToPx(32)))
                }
                addView(steppers)

                // 3-Stage Heated Seat Button
                passengerSeatHeaterBtn = Button(this@CruzeLauncherActivity).apply {
                    text = "💺 SEAT 0"
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    background = createButtonDrawable("#1A202C", "#2D3748", 6f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(32)
                    ).apply { setMargins(0, 6, 0, 0) }
                    setOnClickListener {
                        ClimateController.cyclePassengerSeatHeater(this@CruzeLauncherActivity)
                    }
                }
                addView(passengerSeatHeaterBtn)
            }
            addView(passPod)

            // 5. QUICK APP LAUNCHER POD (Apps Drawer & Setup)
            val quickPod = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(6, 6, 6, 6)
                background = createCardDrawable("#121620", "#1E293B", 10f)
                layoutParams = LinearLayout.LayoutParams(dpToPx(72), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(6, 0, 0, 0)
                }

                val appsBtn = createMicroButton("⊞ APPS") {
                    showAppDrawer()
                }
                addView(appsBtn)

                val carplayBtn = createMicroButton("⚡ PROJ") {
                    ProjectionBridge.launchProjection(this@CruzeLauncherActivity)
                }
                addView(carplayBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(32)).apply { setMargins(0, 4, 0, 4) })

                val carSetupBtn = createMicroButton("⚙ SETUP") {
                    CanBusService.openNativeCarSettings(this@CruzeLauncherActivity)
                }
                addView(carSetupBtn)
            }
            addView(quickPod)
        }
        consoleLayout.addView(comfortRow)

        return consoleLayout
    }

    private fun renderFanSpeedSegments(container: LinearLayout, currentLevel: Int) {
        container.removeAllViews()
        for (i in 1..8) {
            val segment = View(this).apply {
                val isActive = i <= currentLevel
                setBackgroundColor(if (isActive) Color.parseColor("#00E5FF") else Color.parseColor("#1E293B"))
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(2, 0, 2, 0)
                }
                layoutParams = params
                setOnClickListener {
                    ClimateController.setFanSpeed(this@CruzeLauncherActivity, i)
                }
            }
            container.addView(segment)
        }
    }

    private fun createHvacModeBtn(label: String, active: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            updateModeButtonState(this, active)
            setOnClickListener {
                it.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).withEndAction {
                    it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(60).start()
                }.start()
                onClick()
            }
        }
    }

    private fun updateModeButtonState(btn: Button, active: Boolean) {
        if (active) {
            btn.setTextColor(Color.parseColor("#00E5FF"))
            btn.background = createButtonDrawable("#152535", "#00E5FF", 8f)
        } else {
            btn.setTextColor(Color.parseColor("#94A3B8"))
            btn.background = createButtonDrawable("#121620", "#1E293B", 8f)
        }
    }

    private fun createMicroButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createButtonDrawable("#1A202C", "#334155", 6f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(30)
            )
            setOnClickListener {
                it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(60).withEndAction {
                    it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(60).start()
                }.start()
                onClick()
            }
        }
    }

    // ==========================================
    // CAN STATE & CLIMATE EVENT CALLBACKS
    // ==========================================
    override fun onVehicleStateUpdated(state: GmDeltaProtocol.VehicleState) {
        speedGaugeText.text = if (state.useMetricUnits) "${(state.speedMph * 1.60934f).toInt()} KM/H" else "${state.speedMph} MPH"
        rpmGaugeText.text = "${state.engineRpm} RPM"
        fuelPercentText.text = "${state.fuelPercent}%"
        fuelProgressBar.progress = state.fuelPercent
        outsideTempText.text = if (state.useMetricUnits) "${((state.exteriorTempF - 32) * 5 / 9)}°C" else "${state.exteriorTempF}°F"
        batteryVoltageText.text = String.format(Locale.US, "%.1fV", state.batteryVolts)

        centralLockBadge.text = if (state.doorsLocked) "⚡ CRUZE LTZ  🔒" else "⚡ CRUZE LTZ  🔓"
        centralLockBadge.setTextColor(if (state.doorsLocked) Color.parseColor("#00E5FF") else Color.parseColor("#F59E0B"))

        cruzeViewport.setSpeed(state.speedMph)
        cruzeViewport.setSteeringAngle(state.steeringAngleDeg)
        cruzeViewport.setLights(state.headlightsOn, brakeOn = false, leftSignal = state.turnSignalLeft, rightSignal = state.turnSignalRight)
        cruzeViewport.setDoor("FL", state.doorFL)
        cruzeViewport.setDoor("FR", state.doorFR)
        cruzeViewport.setDoor("RL", state.doorRL)
        cruzeViewport.setDoor("RR", state.doorRR)
        cruzeViewport.setDoor("trunk", state.trunkOpen)

        // TPMS
        val unit = if (state.useMetricUnits) "kPa" else "PSI"
        val factor = if (state.useMetricUnits) 6.89476f else 1.0f
        tpmsFLText.text = "FL: ${(state.tpmsFL * factor).toInt()} $unit"
        tpmsFRText.text = "FR: ${(state.tpmsFR * factor).toInt()} $unit"
        tpmsRLText.text = "RL: ${(state.tpmsRL * factor).toInt()} $unit"
        tpmsRRText.text = "RR: ${(state.tpmsRR * factor).toInt()} $unit"
    }

    override fun onClimateStateUpdated(climate: GmDeltaProtocol.ClimateState) {
        val isMetric = CanBusService.vehicleState.useMetricUnits
        driverTempText.text = climate.formatDriverTemp(isMetric)
        passengerTempText.text = climate.formatPassengerTemp(isMetric)

        // Volume
        volumeLevelText.text = "${climate.volumeLevel}"
        volumeLevelText.setTextColor(if (climate.isMuted) Color.parseColor("#EF4444") else Color.parseColor("#00E5FF"))

        // Heated Seats
        updateSeatHeaterBtn(driverSeatHeaterBtn, "💺 DRIVER", climate.driverSeatHeater)
        updateSeatHeaterBtn(passengerSeatHeaterBtn, "💺 PASS", climate.passengerSeatHeater)

        // Fan Speed
        fanSpeedLevelLabel.text = if (climate.fanSpeed == 0) "FAN OFF" else "FAN SPEED ${climate.fanSpeed}"
        renderFanSpeedSegments(fanSpeedBarsContainer, climate.fanSpeed)

        // Mode buttons
        updateModeButtonState(hvacPowerBtn, !climate.powerOn)
        hvacPowerBtn.text = if (climate.powerOn) "PWR ON" else "OFF"
        updateModeButtonState(dualSyncBtn, climate.dualZoneSync)
        updateModeButtonState(acCompressorBtn, climate.acEnabled)
        updateModeButtonState(maxAcBtn, climate.maxAcEnabled)
        updateModeButtonState(recircBtn, climate.recirculation)
        updateModeButtonState(autoClimateBtn, climate.autoMode)
        ventModeBtn.text = climate.airDistribution.label
        updateModeButtonState(ventModeBtn, true)
        updateModeButtonState(frontDefrostBtn, climate.frontDefrost)
    }

    private fun updateSeatHeaterBtn(btn: Button, prefix: String, level: Int) {
        btn.text = "$prefix $level"
        when (level) {
            3 -> {
                btn.setTextColor(Color.parseColor("#EF4444")) // High = Red
                btn.background = createButtonDrawable("#2A1215", "#EF4444", 6f)
            }
            2 -> {
                btn.setTextColor(Color.parseColor("#F59E0B")) // Med = Amber
                btn.background = createButtonDrawable("#251D12", "#F59E0B", 6f)
            }
            1 -> {
                btn.setTextColor(Color.parseColor("#00E5FF")) // Low = Cyan
                btn.background = createButtonDrawable("#152535", "#00E5FF", 6f)
            }
            else -> {
                btn.setTextColor(Color.parseColor("#64748B"))
                btn.background = createButtonDrawable("#1A202C", "#2D3748", 6f)
            }
        }
    }

    // ==========================================
    // UI FACTORY HELPERS (Obsidian Automotive Aesthetics)
    // ==========================================
    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1017"))
            background = createCardDrawable("#0D1017", "#1E293B", 12f)
            setPadding(14, 12, 14, 12)
        }
    }

    private fun createCardHeader(title: String, accentHex: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor(accentHex))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, 0, 0, 4)
        }
    }

    private fun createTactileButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            background = createButtonDrawable("#1A202C", "#334155", 8f)
            setOnClickListener {
                it.animate().scaleX(0.93f).scaleY(0.93f).setDuration(60).withEndAction {
                    it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(60).start()
                }.start()
                onClick()
            }
        }
    }

    private fun showAppDrawer() {
        val apps = AppDrawerManager.loadInstalledApps(this)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#08090E"))
            setPadding(24, 20, 24, 24)
        }

        val titleView = TextView(this).apply {
            text = "INSTALLED APPLICATIONS"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, 0, 0, 12)
        }
        dialogView.addView(titleView)

        val searchBox = EditText(this).apply {
            hint = "Search all apps..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = createCardDrawable("#151D2A", "#334155", 10f)
            setPadding(16, 12, 16, 12)
        }
        dialogView.addView(searchBox)

        val gridScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(420)
            ).apply { setMargins(0, 14, 0, 0) }
        }

        val gridLayout = GridLayout(this).apply {
            columnCount = 4
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        gridScrollView.addView(gridLayout)
        dialogView.addView(gridScrollView)

        var dialog: AlertDialog? = null

        fun populateGrid(query: String) {
            gridLayout.removeAllViews()
            val filtered = if (query.isEmpty()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
            val screenWidth = resources.displayMetrics.widthPixels - dpToPx(64)
            val itemWidth = (screenWidth / 4).coerceAtLeast(dpToPx(70))

            for (app in filtered) {
                val itemView = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(8, 12, 8, 12)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = itemWidth
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    val iconView = ImageView(this@CruzeLauncherActivity).apply {
                        setImageDrawable(app.icon)
                        layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))
                    }
                    val labelView = TextView(this@CruzeLauncherActivity).apply {
                        text = app.label
                        setTextColor(Color.WHITE)
                        textSize = 10f
                        maxLines = 1
                        gravity = Gravity.CENTER
                        setPadding(0, 6, 0, 0)
                    }
                    addView(iconView)
                    addView(labelView)
                    setOnClickListener {
                        dialog?.dismiss()
                        AppDrawerManager.launchApp(this@CruzeLauncherActivity, app)
                    }
                }
                gridLayout.addView(itemView)
            }
        }

        populateGrid("")

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { populateGrid(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        dialog.show()
    }

    private fun createCardDrawable(bgColorHex: String, strokeColorHex: String, cornerRadiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgColorHex))
            setStroke(dpToPx(1), Color.parseColor(strokeColorHex))
            cornerRadius = dpToPx(cornerRadiusDp.toInt()).toFloat()
        }
    }

    private fun createButtonDrawable(bgColorHex: String, strokeColorHex: String, cornerRadiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgColorHex))
            setStroke(dpToPx(1), Color.parseColor(strokeColorHex))
            cornerRadius = dpToPx(cornerRadiusDp.toInt()).toFloat()
        }
    }

    private fun createGradientDrawable(startColorHex: String, endColorHex: String, cornerRadiusDp: Float): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor(startColorHex), Color.parseColor(endColorHex))
        ).apply {
            cornerRadius = dpToPx(cornerRadiusDp.toInt()).toFloat()
        }
    }

    private fun createPillDrawable(bgColorHex: String, strokeColorHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgColorHex))
            setStroke(dpToPx(1), Color.parseColor(strokeColorHex))
            cornerRadius = 9999f
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
