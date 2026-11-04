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

    // Quick Climate Card
    private lateinit var driverTempLabel: TextView
    private lateinit var passengerTempLabel: TextView
    private lateinit var fanSpeedLabel: TextView
    private lateinit var acToggleBtn: Button
    private lateinit var driverSeatBtn: Button
    private lateinit var passSeatBtn: Button

    // Bottom Navigation Dock
    private lateinit var dockLayout: LinearLayout

    // Clock updater
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

        val rootScrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#07080B")) // Deep luxury obsidian
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#07080B"))
            setPadding(0, 0, 0, 12)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootScrollView.addView(rootLayout)

        // 1. Top Automotive Status Bar (52dp)
        rootLayout.addView(createHeaderBar())

        // 2. Full-Width 3D Cruze Stage (320dp height)
        rootLayout.addView(create3dStage())

        // 3. Primary Automotive Cards (CarPlay/AA + Media)
        rootLayout.addView(createPrimaryHeroCards())

        // 4. Secondary Control Cards (Climate Quick Deck + Telemetry)
        rootLayout.addView(createSecondaryControlCards())

        // 5. Persistent Bottom Automotive Dock
        dockLayout = createBottomDock()
        rootLayout.addView(dockLayout)

        setContentView(rootScrollView)

        // Register CAN-bus listener
        CanBusService.registerListener(this)

        // Media updates
        MediaManager.registerListener { track ->
            mediaTitleText.text = track.title
            mediaArtistText.text = track.artist
            mediaPlayPauseBtn.text = if (track.isPlaying) "⏸" else "▶"
        }

        // Check overlay permission for floating climate HUD
        checkOverlayPermission()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        mainHandler.post(clockRunnable)
        AppDrawerManager.loadInstalledApps(this)
        updateProjectionUI()
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

    private fun updateProjectionUI() {
        val installed = ProjectionBridge.findInstalledProjectionPackage(this)
        val engineName = installed?.second ?: "CarPlay / Android Auto"
        projectionLaunchBtn.text = "⚡ Launch $engineName"
        projectionStatusLabel.text = "● Connected via USB / Bluetooth"
        projectionStatusLabel.setTextColor(Color.parseColor("#10B981"))
    }

    // ==========================================
    // 1. TOP AUTOMOTIVE STATUS HEADER
    // ==========================================
    private fun createHeaderBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 10, 20, 10)
            setBackgroundColor(Color.parseColor("#0C0E14"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Brand Mark
            val brandLayout = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            val brandText = TextView(this@CruzeLauncherActivity).apply {
                text = "CRUZE LTZ"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FFFFFF"))
                letterSpacing = 0.12f
            }
            brandLayout.addView(brandText)

            phoneStatusBadge = TextView(this@CruzeLauncherActivity).apply {
                text = "● PHONE CONNECTED"
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#10B981"))
                letterSpacing = 0.08f
            }
            brandLayout.addView(phoneStatusBadge)
            addView(brandLayout)

            // Center: Digital Clock
            val clockLayout = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f)
            }
            clockTimeText = TextView(this@CruzeLauncherActivity).apply {
                text = "12:00 PM"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            clockLayout.addView(clockTimeText)

            clockDateText = TextView(this@CruzeLauncherActivity).apply {
                text = "THURSDAY, NOV 5"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#8E9AA8"))
                gravity = Gravity.CENTER
                letterSpacing = 0.1f
            }
            clockLayout.addView(clockDateText)
            addView(clockLayout)

            // Right: Telemetry Chips & Factory Settings Gear
            val rightLayout = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            }

            outsideTempText = createStatusPill("72°F", "#00E5FF")
            rightLayout.addView(outsideTempText)

            batteryVoltageText = createStatusPill("14.2V", "#81C784")
            rightLayout.addView(batteryVoltageText)

            val gearBtn = Button(this@CruzeLauncherActivity).apply {
                text = "⚙"
                textSize = 17f
                setTextColor(Color.parseColor("#8E9AA8"))
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(8, 0, 8, 0)
                setOnClickListener { CanBusService.openNativeCarSettings(this@CruzeLauncherActivity) }
            }
            rightLayout.addView(gearBtn)

            addView(rightLayout)
        }
    }

    private fun createStatusPill(text: String, accentHex: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(accentHex))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#141923"))
                cornerRadius = 12f
                setStroke(1, Color.parseColor("#222A3B"))
            }
            setPadding(10, 5, 10, 5)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4, 0, 4, 0) }
        }
    }

    // ==========================================
    // 2. MAIN 3D CRUZE VIEWPORT STAGE
    // ==========================================
    private fun create3dStage(): FrameLayout {
        val stageFrame = FrameLayout(this).apply {
            val displayMetrics = resources.displayMetrics
            val stageHeight = (displayMetrics.heightPixels * 0.40f).toInt().coerceIn(600, 950)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                stageHeight
            ).apply { setMargins(12, 8, 12, 8) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0B0E14"))
                cornerRadius = 20f
                setStroke(1, Color.parseColor("#1B2230"))
            }
        }

        // Hardware-accelerated WebView hosting Three.js PBR stage
        cruzeViewport = Cruze3dViewportView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            callback = object : Cruze3dViewportView.Callback {
                override fun onModelReady() {
                    // Ready
                }
                override fun onPartInteracted(part: String, isOpen: Boolean) {
                    Toast.makeText(this@CruzeLauncherActivity, "$part ${if (isOpen) "Opened" else "Closed"}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        stageFrame.addView(cruzeViewport)

        // Floating Quick Vehicle Actions Bar (top-left of stage)
        val actionPills = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(14, 14, 0, 0)
            }
        }

        actionPills.addView(createStageButton("DOORS") {
            val state = CanBusService.vehicleState
            val nextOpen = !state.doorFL
            cruzeViewport.setDoor("FL", nextOpen)
            cruzeViewport.setDoor("FR", nextOpen)
            CanBusService.broadcastMcuCommand(this, "TOGGLE_LOCK", if (nextOpen) 0 else 1)
        })

        actionPills.addView(createStageButton("TRUNK") {
            val state = CanBusService.vehicleState
            state.trunkOpen = !state.trunkOpen
            cruzeViewport.setDoor("trunk", state.trunkOpen)
            CanBusService.broadcastMcuCommand(this, "TOGGLE_TRUNK", if (state.trunkOpen) 1 else 0)
        })

        actionPills.addView(createStageButton("LIGHTS") {
            val state = CanBusService.vehicleState
            state.headlightsOn = !state.headlightsOn
            cruzeViewport.setLights(state.headlightsOn, brakeOn = false, leftSignal = false, rightSignal = false)
            CanBusService.broadcastMcuCommand(this, "TOGGLE_LIGHTS", if (state.headlightsOn) 1 else 0)
        })

        actionPills.addView(createStageButton("CAN SIM") {
            showCanSimDialog()
        })

        stageFrame.addView(actionPills)

        return stageFrame
    }

    private fun createStageButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#C2CBD6"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC121722"))
                cornerRadius = 14f
                setStroke(1, Color.parseColor("#263248"))
            }
            setPadding(12, 6, 12, 6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(3, 0, 3, 0) }
            setOnClickListener { onClick() }
        }
    }

    // ==========================================
    // 3. PRIMARY HERO CARDS (CARPLAY/AA + MEDIA)
    // ==========================================
    private fun createPrimaryHeroCards(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 0, 12, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Card 1: CarPlay & Android Auto Hero
            val projectionCard = createCardLayout().apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 6
                }
            }

            val cardTitle = createCardHeader("PHONE PROJECTION", "#00E5FF")
            projectionCard.addView(cardTitle)

            projectionStatusLabel = TextView(this@CruzeLauncherActivity).apply {
                text = "● Connected via USB / Bluetooth"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#10B981"))
                setPadding(0, 4, 0, 10)
            }
            projectionCard.addView(projectionStatusLabel)

            projectionLaunchBtn = Button(this@CruzeLauncherActivity).apply {
                text = "⚡ Launch CarPlay / Android Auto"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#080A0E"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#00E5FF"))
                    cornerRadius = 14f
                }
                setPadding(16, 12, 16, 12)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    ProjectionBridge.launchProjection(this@CruzeLauncherActivity)
                }
            }
            projectionCard.addView(projectionLaunchBtn)

            climateOverlaySwitch = CheckBox(this@CruzeLauncherActivity).apply {
                text = "Keep Climate Floating HUD over Navigation"
                isChecked = true
                setTextColor(Color.parseColor("#8E9AA8"))
                textSize = 11f
                setPadding(6, 8, 0, 0)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && Settings.canDrawOverlays(this@CruzeLauncherActivity)) {
                        FloatingClimateOverlayService.start(this@CruzeLauncherActivity)
                    } else if (!isChecked) {
                        FloatingClimateOverlayService.stop(this@CruzeLauncherActivity)
                    }
                }
            }
            projectionCard.addView(climateOverlaySwitch)
            addView(projectionCard)

            // Card 2: Universal Automotive Media Player
            val mediaCard = createCardLayout().apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 6
                }
            }

            val mediaHeader = createCardHeader("AUDIO SYSTEM", "#81C784")
            mediaCard.addView(mediaHeader)

            mediaTitleText = TextView(this@CruzeLauncherActivity).apply {
                text = "Cruze Audio System"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 1
                setPadding(0, 4, 0, 2)
            }
            mediaCard.addView(mediaTitleText)

            mediaArtistText = TextView(this@CruzeLauncherActivity).apply {
                text = "Bluetooth / Car Radio Active"
                textSize = 11.5f
                setTextColor(Color.parseColor("#8E9AA8"))
                maxLines = 1
                setPadding(0, 0, 0, 10)
            }
            mediaCard.addView(mediaArtistText)

            val mediaControlsRow = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val prevBtn = createMediaButton("⏮") { MediaManager.skipPrevious(this@CruzeLauncherActivity) }
            mediaControlsRow.addView(prevBtn)

            mediaPlayPauseBtn = createMediaButton("⏸") { MediaManager.togglePlayPause(this@CruzeLauncherActivity) }
            mediaControlsRow.addView(mediaPlayPauseBtn)

            val nextBtn = createMediaButton("⏭") { MediaManager.skipNext(this@CruzeLauncherActivity) }
            mediaControlsRow.addView(nextBtn)

            mediaCard.addView(mediaControlsRow)
            addView(mediaCard)
        }
    }

    private fun createMediaButton(icon: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = icon
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#19212E"))
                cornerRadius = 14f
                setStroke(1, Color.parseColor("#273448"))
            }
            layoutParams = LinearLayout.LayoutParams(0, 46, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    // ==========================================
    // 4. SECONDARY CARDS (TELEMETRY + QUICK CLIMATE)
    // ==========================================
    private fun createSecondaryControlCards(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 0, 12, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Card 3: Instrument Telemetry & TPMS
            val telemCard = createCardLayout().apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 6
                }
            }
            telemCard.addView(createCardHeader("INSTRUMENTS & TPMS", "#FFB74D"))

            val gaugeRow = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 8)
            }
            speedGaugeText = TextView(this@CruzeLauncherActivity).apply {
                text = "0 MPH"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            gaugeRow.addView(speedGaugeText)

            rpmGaugeText = TextView(this@CruzeLauncherActivity).apply {
                text = "0 RPM"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#8E9AA8"))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            gaugeRow.addView(rpmGaugeText)
            telemCard.addView(gaugeRow)

            // Fuel bar
            val fuelHeaderRow = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val fuelLabel = TextView(this@CruzeLauncherActivity).apply {
                text = "Fuel Level"
                textSize = 10.5f
                setTextColor(Color.parseColor("#8E9AA8"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            fuelHeaderRow.addView(fuelLabel)

            fuelPercentText = TextView(this@CruzeLauncherActivity).apply {
                text = "85%"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#81C784"))
            }
            fuelHeaderRow.addView(fuelPercentText)
            telemCard.addView(fuelHeaderRow)

            fuelProgressBar = ProgressBar(this@CruzeLauncherActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                progress = 85
                max = 100
                progressDrawable = GradientDrawable().apply {
                    setColor(Color.parseColor("#81C784"))
                    cornerRadius = 4f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    7
                ).apply { setMargins(0, 3, 0, 8) }
            }
            telemCard.addView(fuelProgressBar)

            // TPMS 4-Corner Grid
            val tpmsGrid = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            tpmsFLText = createTpmsItem("FL 32 PSI")
            tpmsGrid.addView(tpmsFLText)
            tpmsFRText = createTpmsItem("FR 32 PSI")
            tpmsGrid.addView(tpmsFRText)
            tpmsRLText = createTpmsItem("RL 32 PSI")
            tpmsGrid.addView(tpmsRLText)
            tpmsRRText = createTpmsItem("RR 32 PSI")
            tpmsGrid.addView(tpmsRRText)
            telemCard.addView(tpmsGrid)

            addView(telemCard)

            // Card 4: Quick Dual-Zone Climate & Factory Car Settings
            val climateCard = createCardLayout().apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 6
                }
            }
            climateCard.addView(createCardHeader("HVAC & VEHICLE CONSOLE", "#80D8FF"))

            // Dual Temps & Blower
            val tempsRow = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 8)
            }
            driverTempLabel = createTempBox("DRIVER", "68°") {
                ClimateController.adjustDriverTemp(this@CruzeLauncherActivity, 1.0f)
            }
            tempsRow.addView(driverTempLabel)

            fanSpeedLabel = createTempBox("FAN", "3") {
                ClimateController.stepFanSpeed(this@CruzeLauncherActivity)
            }
            tempsRow.addView(fanSpeedLabel)

            passengerTempLabel = createTempBox("PASS", "70°") {
                ClimateController.adjustPassengerTemp(this@CruzeLauncherActivity, 1.0f)
            }
            tempsRow.addView(passengerTempLabel)
            climateCard.addView(tempsRow)

            // Seat Heaters & AC Toggle
            val seatRow = LinearLayout(this@CruzeLauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 8)
            }
            driverSeatBtn = createSmallButton("D-SEAT 0") {
                val s = ClimateController.cycleDriverSeatHeater(this@CruzeLauncherActivity)
                driverSeatBtn.text = "D-SEAT $s"
                driverSeatBtn.setTextColor(if (s > 0) Color.parseColor("#FF9100") else Color.parseColor("#8E9AA8"))
            }
            seatRow.addView(driverSeatBtn)

            acToggleBtn = createSmallButton("A/C ON") {
                ClimateController.toggleAc(this@CruzeLauncherActivity)
            }
            seatRow.addView(acToggleBtn)

            passSeatBtn = createSmallButton("P-SEAT 0") {
                val s = ClimateController.cyclePassengerSeatHeater(this@CruzeLauncherActivity)
                passSeatBtn.text = "P-SEAT $s"
                passSeatBtn.setTextColor(if (s > 0) Color.parseColor("#FF9100") else Color.parseColor("#8E9AA8"))
            }
            seatRow.addView(passSeatBtn)
            climateCard.addView(seatRow)

            // Direct 1-Tap Launcher into Car's Factory Settings & Climate Console
            val factorySettingsBtn = Button(this@CruzeLauncherActivity).apply {
                text = "⚙️ Factory Car Settings & AC"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E2738"))
                    cornerRadius = 12f
                    setStroke(1, Color.parseColor("#34435C"))
                }
                setPadding(10, 8, 10, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    CanBusService.openNativeCarSettings(this@CruzeLauncherActivity)
                }
            }
            climateCard.addView(factorySettingsBtn)

            addView(climateCard)
        }
    }

    private fun createTempBox(label: String, initial: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = "$label\n$initial"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151D2A"))
                cornerRadius = 10f
            }
            setPadding(8, 6, 8, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(2, 0, 2, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createSmallButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E9AA8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151D2A"))
                cornerRadius = 10f
            }
            setPadding(4, 4, 4, 4)
            layoutParams = LinearLayout.LayoutParams(0, 38, 1f).apply {
                setMargins(2, 0, 2, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createTpmsItem(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#C2CBD6"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#141A24"))
                cornerRadius = 8f
            }
            setPadding(6, 4, 6, 4)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(2, 0, 2, 0)
            }
        }
    }

    // ==========================================
    // 5. BOTTOM AUTOMOTIVE DOCK
    // ==========================================
    private fun createBottomDock(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F131C"))
                cornerRadius = 20f
                setStroke(1, Color.parseColor("#222A3B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 4, 16, 12)
            }

            addView(createDockItem("⊞", "Apps") { showAppDrawerSheet() })
            addView(createDockItem("⚡", "CarPlay") { ProjectionBridge.launchProjection(this@CruzeLauncherActivity) })
            addView(createDockItem("🚗", "Vehicle") { cruzeViewport.setCameraPreset("beauty") })
            addView(createDockItem("❄", "Climate") {
                if (FloatingClimateOverlayService.isOverlayActive) {
                    FloatingClimateOverlayService.stop(this@CruzeLauncherActivity)
                } else if (Settings.canDrawOverlays(this@CruzeLauncherActivity)) {
                    FloatingClimateOverlayService.start(this@CruzeLauncherActivity)
                } else {
                    checkOverlayPermission()
                }
            })
            addView(createDockItem("⚙", "Car Setup") { CanBusService.openNativeCarSettings(this@CruzeLauncherActivity) })
        }
    }

    private fun createDockItem(icon: String, label: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(12, 6, 12, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val iconView = TextView(this@CruzeLauncherActivity).apply {
                text = icon
                textSize = 19f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            addView(iconView)

            val labelView = TextView(this@CruzeLauncherActivity).apply {
                text = label
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#8E9AA8"))
                gravity = Gravity.CENTER
                setPadding(0, 2, 0, 0)
            }
            addView(labelView)

            setOnClickListener { onClick() }
        }
    }

    // ==========================================
    // CAN STATE & CLIMATE EVENT CALLBACKS
    // ==========================================
    override fun onVehicleStateUpdated(state: GmDeltaProtocol.VehicleState) {
        speedGaugeText.text = "${state.speedMph} MPH"
        rpmGaugeText.text = "${state.engineRpm} RPM"
        fuelPercentText.text = "${state.fuelPercent}%"
        fuelProgressBar.progress = state.fuelPercent
        outsideTempText.text = "${state.exteriorTempF}°F"
        batteryVoltageText.text = String.format(Locale.US, "%.1fV", state.batteryVolts)

        cruzeViewport.setSpeed(state.speedMph)
        cruzeViewport.setSteeringAngle(state.steeringAngleDeg)
        cruzeViewport.setLights(state.headlightsOn, brakeOn = false, leftSignal = false, rightSignal = false)
        cruzeViewport.setDoor("FL", state.doorFL)
        cruzeViewport.setDoor("FR", state.doorFR)
        cruzeViewport.setDoor("RL", state.doorRL)
        cruzeViewport.setDoor("RR", state.doorRR)
        cruzeViewport.setDoor("trunk", state.trunkOpen)
    }

    override fun onClimateStateUpdated(climate: GmDeltaProtocol.ClimateState) {
        driverTempLabel.text = "DRIVER\n${climate.driverTempF.toInt()}°"
        passengerTempLabel.text = "PASS\n${climate.passengerTempF.toInt()}°"
        fanSpeedLabel.text = "FAN\n${climate.fanSpeed}"
        acToggleBtn.text = if (climate.acEnabled) "A/C ON" else "A/C OFF"
        acToggleBtn.setTextColor(if (climate.acEnabled) Color.parseColor("#00E5FF") else Color.parseColor("#8E9AA8"))
        driverSeatBtn.text = "D-SEAT ${climate.driverSeatHeater}"
        driverSeatBtn.setTextColor(if (climate.driverSeatHeater > 0) Color.parseColor("#FF9100") else Color.parseColor("#8E9AA8"))
        passSeatBtn.text = "P-SEAT ${climate.passengerSeatHeater}"
        passSeatBtn.setTextColor(if (climate.passengerSeatHeater > 0) Color.parseColor("#FF9100") else Color.parseColor("#8E9AA8"))
    }

    // ==========================================
    // UTILITY BUILDERS & HELPERS
    // ==========================================
    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0C1017"))
                cornerRadius = 18f
                setStroke(1, Color.parseColor("#1C2433"))
            }
        }
    }

    private fun createCardHeader(title: String, accentHex: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(accentHex))
            letterSpacing = 0.1f
            setPadding(0, 0, 0, 6)
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                startActivity(intent)
            } catch (ignored: Exception) {}
        }
    }

    // ==========================================
    // FULLSCREEN APP DRAWER
    // ==========================================
    private fun showAppDrawerSheet() {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).create()
        val drawerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0C12"))
            setPadding(24, 28, 24, 24)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        val title = TextView(this).apply {
            text = "ALL APPLICATIONS"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.parseColor("#8E9AA8"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(closeBtn)
        drawerLayout.addView(header)

        // Search Bar
        val searchBox = EditText(this).apply {
            hint = "Search apps..."
            setHintTextColor(Color.parseColor("#5F6B7A"))
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#141923"))
                cornerRadius = 14f
                setStroke(1, Color.parseColor("#263248"))
            }
            setPadding(18, 14, 18, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
        }
        drawerLayout.addView(searchBox)

        // Apps Grid in ScrollView
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val gridLayout = GridLayout(this).apply {
            columnCount = 4
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(gridLayout)
        drawerLayout.addView(scroll)

        fun populateApps(apps: List<AppInfo>) {
            gridLayout.removeAllViews()
            val screenWidth = resources.displayMetrics.widthPixels - 48
            val colWidth = screenWidth / 4

            for (app in apps) {
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(8, 14, 8, 14)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = colWidth
                    }

                    val icon = ImageView(this@CruzeLauncherActivity).apply {
                        setImageDrawable(app.icon)
                        layoutParams = LinearLayout.LayoutParams(62, 62)
                    }
                    addView(icon)

                    val name = TextView(this@CruzeLauncherActivity).apply {
                        text = app.label
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        maxLines = 1
                        setPadding(0, 6, 0, 0)
                    }
                    addView(name)

                    setOnClickListener {
                        AppDrawerManager.launchApp(this@CruzeLauncherActivity, app)
                        dialog.dismiss()
                    }
                }
                gridLayout.addView(item)
            }
        }

        populateApps(AppDrawerManager.getApps())

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                populateApps(AppDrawerManager.getApps(s?.toString() ?: ""))
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        dialog.setView(drawerLayout)
        dialog.show()
    }

    // ==========================================
    // CAN SIMULATOR DIALOG FOR TESTING
    // ==========================================
    private fun showCanSimDialog() {
        val dialog = AlertDialog.Builder(this).create()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.parseColor("#121722"))
        }

        val title = TextView(this).apply {
            text = "CAN-Bus Hardware Simulator"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        fun createSimButton(label: String, action: () -> Unit) {
            val btn = Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1F2838"))
                    cornerRadius = 10f
                }
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
            }
            layout.addView(btn)
        }

        createSimButton("Simulate Open Driver Door") {
            val state = CanBusService.vehicleState
            state.doorFL = !state.doorFL
            CanBusService.notifyUpdate()
        }

        createSimButton("Simulate Open Trunk") {
            val state = CanBusService.vehicleState
            state.trunkOpen = !state.trunkOpen
            CanBusService.notifyUpdate()
        }

        createSimButton("Simulate 45 MPH / 2200 RPM") {
            val state = CanBusService.vehicleState
            state.speedMph = if (state.speedMph == 0) 45 else 0
            state.engineRpm = if (state.speedMph > 0) 2200 else 0
            CanBusService.notifyUpdate()
        }

        createSimButton("Close") { dialog.dismiss() }

        dialog.setView(layout)
        dialog.show()
    }

    // Steering wheel key interception
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                MediaManager.skipNext(this)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                MediaManager.skipPrevious(this)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                MediaManager.togglePlayPause(this)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
