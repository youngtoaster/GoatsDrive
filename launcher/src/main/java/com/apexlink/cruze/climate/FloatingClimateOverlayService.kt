package com.apexlink.cruze.climate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
 * Non-intrusive floating automotive climate HUD bar running as an overlay (TYPE_APPLICATION_OVERLAY)
 * across full-screen Apple CarPlay, Android Auto, or navigation apps.
 */
class FloatingClimateOverlayService : Service(), CanBusService.VehicleStateListener {

    companion object {
        private const val CHANNEL_ID = "cruze_floating_climate"
        private const val NOTIF_ID = 2002
        var isOverlayActive = false

        fun start(context: Context) {
            val intent = Intent(context, FloatingClimateOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingClimateOverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayRoot: View? = null

    // UI elements
    private lateinit var driverTempText: TextView
    private lateinit var passengerTempText: TextView
    private lateinit var fanSpeedText: TextView
    private lateinit var acStatusText: TextView
    private lateinit var driverSeatText: TextView
    private lateinit var passengerSeatText: TextView
    private lateinit var expandedDrawer: LinearLayout

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
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
            y = 12 // comfortable margin above bottom screen bezel
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

        // Main Sleek Floating Dock
        val dockBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE0D1017")) // Deep obsidian glass
                cornerRadius = 24f
                setStroke(1, Color.parseColor("#283042"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(20, 0, 20, 10)
            }
        }

        // --- Driver Controls ---
        val driverGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }

        driverSeatText = createIconButton("SEAT 0").apply {
            setOnClickListener {
                val stage = ClimateController.cycleDriverSeatHeater(this@FloatingClimateOverlayService)
                text = "SEAT $stage"
                setTextColor(if (stage > 0) Color.parseColor("#FF9100") else Color.parseColor("#8E9AA8"))
            }
        }
        driverGroup.addView(driverSeatText)

        val driverMinus = createStepButton("-") { ClimateController.adjustDriverTemp(this@FloatingClimateOverlayService, -0.5f) }
        driverGroup.addView(driverMinus)

        driverTempText = TextView(this).apply {
            text = "68°"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(10, 0, 10, 0)
        }
        driverGroup.addView(driverTempText)

        val driverPlus = createStepButton("+") { ClimateController.adjustDriverTemp(this@FloatingClimateOverlayService, 0.5f) }
        driverGroup.addView(driverPlus)
        dockBar.addView(driverGroup)

        // --- Center Quick Blower & AC Badge ---
        val centerGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
        }

        fanSpeedText = createIconButton("FAN 3").apply {
            setOnClickListener { ClimateController.stepFanSpeed(this@FloatingClimateOverlayService) }
        }
        centerGroup.addView(fanSpeedText)

        acStatusText = createIconButton("A/C").apply {
            setOnClickListener { ClimateController.toggleAc(this@FloatingClimateOverlayService) }
        }
        centerGroup.addView(acStatusText)

        val expandBtn = createIconButton("CLIMATE ▲").apply {
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

        val passMinus = createStepButton("-") { ClimateController.adjustPassengerTemp(this@FloatingClimateOverlayService, -0.5f) }
        passGroup.addView(passMinus)

        passengerTempText = TextView(this).apply {
            text = "70°"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(10, 0, 10, 0)
        }
        passGroup.addView(passengerTempText)

        val passPlus = createStepButton("+") { ClimateController.adjustPassengerTemp(this@FloatingClimateOverlayService, 0.5f) }
        passGroup.addView(passPlus)

        passengerSeatText = createIconButton("SEAT 0").apply {
            setOnClickListener {
                val stage = ClimateController.cyclePassengerSeatHeater(this@FloatingClimateOverlayService)
                text = "SEAT $stage"
                setTextColor(if (stage > 0) Color.parseColor("#FF9100") else Color.parseColor("#8E9AA8"))
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
            setPadding(14, 10, 14, 10)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4121722"))
                cornerRadius = 16f
                setStroke(1, Color.parseColor("#283042"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }

            addView(createDrawerButton("AUTO") { ClimateController.toggleAuto(this@FloatingClimateOverlayService) })
            addView(createDrawerButton("MAX A/C") { ClimateController.toggleMaxAc(this@FloatingClimateOverlayService) })
            addView(createDrawerButton("RECIRC") { ClimateController.toggleRecirculation(this@FloatingClimateOverlayService) })
            addView(createDrawerButton("FRONT DEF") { ClimateController.toggleFrontDefrost(this@FloatingClimateOverlayService) })
            addView(createDrawerButton("REAR DEF") { ClimateController.toggleRearDefrost(this@FloatingClimateOverlayService) })
            addView(createDrawerButton("SETTINGS") {
                CanBusService.openNativeCarSettings(this@FloatingClimateOverlayService)
            })
        }
    }

    private fun createStepButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1B2230"))
                cornerRadius = 12f
            }
            setPadding(10, 4, 10, 4)
            layoutParams = LinearLayout.LayoutParams(40, 40)
            setOnClickListener { onClick() }
        }
    }

    private fun createIconButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E9AA8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151C28"))
                cornerRadius = 12f
            }
            setPadding(12, 8, 12, 8)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4, 0, 4, 0) }
        }
    }

    private fun createDrawerButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#D0D7DE"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C2433"))
                cornerRadius = 10f
            }
            setPadding(12, 7, 12, 7)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener { onClick() }
        }
    }

    override fun onVehicleStateUpdated(state: GmDeltaProtocol.VehicleState) {}

    override fun onClimateStateUpdated(climate: GmDeltaProtocol.ClimateState) {
        driverTempText.text = if (climate.driverTempF <= 60.0f) "LO" else if (climate.driverTempF >= 85.0f) "HI" else "${climate.driverTempF.toInt()}°"
        passengerTempText.text = if (climate.passengerTempF <= 60.0f) "LO" else if (climate.passengerTempF >= 85.0f) "HI" else "${climate.passengerTempF.toInt()}°"
        fanSpeedText.text = if (climate.fanSpeed == 0) "OFF" else "FAN ${climate.fanSpeed}"
        acStatusText.setTextColor(if (climate.acEnabled) Color.parseColor("#00E5FF") else Color.parseColor("#8E9AA8"))
        driverSeatText.text = "SEAT ${climate.driverSeatHeater}"
        driverSeatText.setTextColor(if (climate.driverSeatHeater > 0) Color.parseColor("#FF9100") else Color.parseColor("#8E9AA8"))
        passengerSeatText.text = "SEAT ${climate.passengerSeatHeater}"
        passengerSeatText.setTextColor(if (climate.passengerSeatHeater > 0) Color.parseColor("#FF9100") else Color.parseColor("#8E9AA8"))
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cruze Climate Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps HVAC climate controls accessible over CarPlay and Android Auto"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Cruze Climate Active")
                .setContentText("HVAC controls active over projection")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Cruze Climate Active")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        }
        startForeground(NOTIF_ID, notification)
    }
}
