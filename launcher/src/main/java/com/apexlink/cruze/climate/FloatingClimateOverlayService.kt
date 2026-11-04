package com.apexlink.cruze.climate

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.apexlink.cruze.can.CanBusService
import com.apexlink.cruze.can.GmDeltaProtocol

/**
 * Floating Automotive Climate HUD that stays persistently visible on top of
 * GoatsDrive / CarPlay / Android Auto sessions via SYSTEM_ALERT_WINDOW.
 */
class FloatingClimateOverlayService : Service(), CanBusService.VehicleStateListener {

    companion object {
        var isOverlayActive = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingClimateOverlayService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingClimateOverlayService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayRoot: View? = null

    // UI elements
    private lateinit var driverTempText: TextView
    private lateinit var passengerTempText: TextView
    private lateinit var driverSeatText: TextView
    private lateinit var passengerSeatText: TextView
    private lateinit var fanSpeedText: TextView
    private lateinit var acStatusText: TextView
    private lateinit var expandedDrawer: LinearLayout

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createOverlayView()
        CanBusService.registerListener(this)
        isOverlayActive = true
    }

    override fun onDestroy() {
        super.onDestroy()
        CanBusService.unregisterListener(this)
        overlayRoot?.let { windowManager.removeView(it) }
        overlayRoot = null
        isOverlayActive = false
    }

    private fun createOverlayView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Expanded Drawer (Air direction, Max AC, Recirc, Defrost)
        expandedDrawer = createExpandedDrawer().apply {
            visibility = View.GONE
        }
        container.addView(expandedDrawer)

        // Main Bottom Dock Bar (Tesla Style)
        val dockBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 10, 24, 10)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60D1117")) // 90% blur dark glass
                setStroke(1, Color.parseColor("#263238"))
                cornerRadius = 16f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16, 0, 16, 12) }
        }

        // --- Driver Controls ---
        val driverGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }

        driverSeatText = createIconButton("♨️ 0").apply {
            setOnClickListener {
                val stage = ClimateController.cycleDriverSeatHeater()
                text = "♨️ $stage"
                setTextColor(if (stage > 0) Color.parseColor("#FF5722") else Color.parseColor("#90A4AE"))
            }
        }
        driverGroup.addView(driverSeatText)

        val driverMinus = createStepButton("-") { ClimateController.adjustDriverTemp(-0.5f) }
        driverGroup.addView(driverMinus)

        driverTempText = TextView(this).apply {
            text = "70°"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(12, 0, 12, 0)
        }
        driverGroup.addView(driverTempText)

        val driverPlus = createStepButton("+") { ClimateController.adjustDriverTemp(0.5f) }
        driverGroup.addView(driverPlus)
        dockBar.addView(driverGroup)

        // --- Center Quick Blower & AC Badge ---
        val centerGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
        }

        fanSpeedText = createIconButton("❄️ Fan 3").apply {
            setOnClickListener { ClimateController.stepFanSpeed() }
        }
        centerGroup.addView(fanSpeedText)

        acStatusText = createIconButton("A/C").apply {
            setOnClickListener { ClimateController.toggleAc() }
        }
        centerGroup.addView(acStatusText)

        val expandBtn = createIconButton("▲ Menu").apply {
            setOnClickListener {
                expandedDrawer.visibility = if (expandedDrawer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        centerGroup.addView(expandBtn)
        dockBar.addView(centerGroup)

        // --- Passenger Controls ---
        val passGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }

        val passMinus = createStepButton("-") { ClimateController.adjustPassengerTemp(-0.5f) }
        passGroup.addView(passMinus)

        passengerTempText = TextView(this).apply {
            text = "70°"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(12, 0, 12, 0)
        }
        passGroup.addView(passengerTempText)

        val passPlus = createStepButton("+") { ClimateController.adjustPassengerTemp(0.5f) }
        passGroup.addView(passPlus)

        passengerSeatText = createIconButton("♨️ 0").apply {
            setOnClickListener {
                val stage = ClimateController.cyclePassengerSeatHeater()
                text = "♨️ $stage"
                setTextColor(if (stage > 0) Color.parseColor("#FF5722") else Color.parseColor("#90A4AE"))
            }
        }
        passGroup.addView(passengerSeatText)
        dockBar.addView(passGroup)

        container.addView(dockBar)
        overlayRoot = container

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createExpandedDrawer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2161F2C"))
                cornerRadius = 16f
                setStroke(1, Color.parseColor("#00E5FF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }

            addView(createDrawerButton("Auto") { ClimateController.toggleAuto() })
            addView(createDrawerButton("Max A/C") { ClimateController.toggleMaxAc() })
            addView(createDrawerButton("Recirc") { ClimateController.toggleRecirculation() })
            addView(createDrawerButton("Front Defrost") { ClimateController.toggleFrontDefrost() })
            addView(createDrawerButton("Rear Defrost") { ClimateController.toggleRearDefrost() })
            addView(createDrawerButton("Sync") {
                ClimateController.state.dualZoneSync = !ClimateController.state.dualZoneSync
                CanBusService.notifyUpdate()
            })
        }
    }

    private fun createStepButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#263238"))
                cornerRadius = 24f
            }
            layoutParams = LinearLayout.LayoutParams(64, 64)
            setOnClickListener { onClick() }
        }
    }

    private fun createIconButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CFD8DC"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 14f
            }
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4, 0, 4, 0) }
        }
    }

    private fun createDrawerButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11.5f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 12f
            }
            setPadding(14, 8, 14, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener { onClick() }
        }
    }

    override fun onVehicleStateUpdated(state: GmDeltaProtocol.VehicleState) {}

    override fun onClimateStateUpdated(climate: GmDeltaProtocol.ClimateState) {
        overlayRoot?.post {
            driverTempText.text = ClimateController.formatTemp(climate.driverTempF)
            passengerTempText.text = ClimateController.formatTemp(climate.passengerTempF)
            fanSpeedText.text = if (climate.fanSpeed == 0) "Fan Off" else "❄️ Fan ${climate.fanSpeed}"
            acStatusText.text = if (climate.acEnabled) "A/C ON" else "A/C OFF"
            acStatusText.setTextColor(if (climate.acEnabled) Color.parseColor("#00E5FF") else Color.parseColor("#90A4AE"))

            driverSeatText.text = "♨️ ${climate.driverSeatHeater}"
            driverSeatText.setTextColor(if (climate.driverSeatHeater > 0) Color.parseColor("#FF5722") else Color.parseColor("#90A4AE"))

            passengerSeatText.text = "♨️ ${climate.passengerSeatHeater}"
            passengerSeatText.setTextColor(if (climate.passengerSeatHeater > 0) Color.parseColor("#FF5722") else Color.parseColor("#90A4AE"))
        }
    }
}
