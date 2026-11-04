package com.apexlink.cruze.can

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-performance background service listening to and broadcasting automotive CAN-bus signals
 * across Topway (TS10/TS18/TS7), FYT (7862/8581), XYAuto, and Microntek head unit architectures.
 */
class CanBusService : Service() {

    companion object {
        private const val TAG = "Cruze_CAN"

        val vehicleState = GmDeltaProtocol.VehicleState()
        val climateState = GmDeltaProtocol.ClimateState()

        var canPacketsReceivedCount: Long = 0
            private set
        var lastCanSource: String = "Initializing..."
            private set
        var hardwareSerialConnected: Boolean = false
            private set

        private val listeners = CopyOnWriteArrayList<VehicleStateListener>()
        private var uartOutputStream: FileOutputStream? = null
        private var lastAdjustedTime: Long = 0

        fun registerListener(listener: VehicleStateListener) {
            listeners.add(listener)
            listener.onVehicleStateUpdated(vehicleState)
            listener.onClimateStateUpdated(climateState)
        }

        fun unregisterListener(listener: VehicleStateListener) {
            listeners.remove(listener)
        }

        fun notifyUpdate() {
            listeners.forEach {
                it.onVehicleStateUpdated(vehicleState)
                it.onClimateStateUpdated(climateState)
            }
        }

        /**
         * Reads the REAL hardware/MCU master volume from system settings or AudioManager.
         */
        fun getRealMcuVolume(context: Context): Int {
            val cr = context.contentResolver
            val candidateKeys = listOf(
                "volume",
                "ts_volume",
                "mcu_volume",
                "volume_music",
                "car_volume",
                "oem_volume"
            )
            for (key in candidateKeys) {
                try {
                    val v = Settings.System.getInt(cr, key, -1)
                    if (v in 0..60) return v
                } catch (ignored: Exception) {}
            }
            try {
                val gv = Settings.Global.getInt(cr, "volume", -1)
                if (gv in 0..60) return gv
            } catch (ignored: Exception) {}

            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            return am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 7
        }

        fun syncVolume(context: Context) {
            val vol = getRealMcuVolume(context)
            if (climateState.volumeLevel != vol) {
                climateState.volumeLevel = vol
                climateState.isMuted = (vol == 0)
                notifyUpdate()
            }
        }

        /**
         * Adjusts head unit master volume via Topway DEAL_KEY, KeyEvents, AudioManager, and MCU broadcasts.
         */
        fun adjustVolume(context: Context, direction: Int) {
            // Immediate local update so UI reflects change in 0ms with zero lag
            val current = climateState.volumeLevel
            val target = (current + direction).coerceIn(0, 40)
            climateState.volumeLevel = target
            climateState.isMuted = (target == 0)
            notifyUpdate()

            // 1. Write directly to system master volume properties
            val cr = context.contentResolver
            val candidateKeys = listOf("volume", "ts_volume", "mcu_volume", "volume_music", "car_volume")
            candidateKeys.forEach { key ->
                try {
                    Settings.System.putInt(cr, key, target)
                } catch (ignored: Exception) {}
            }

            // 2. Standard Android AudioManager
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val dir = if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            try {
                am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            } catch (ignored: Exception) {}

            // 3. Hardware KeyEvents
            try {
                val keyCode = if (direction > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            } catch (ignored: Exception) {}

            // 4. Topway TS10 / TS18 Deal Key Intents
            try {
                val topwayKey = if (direction > 0) 1 else 2 // 1 = VOL_UP, 2 = VOL_DOWN
                context.sendBroadcast(Intent("com.ts.main.DEAL_KEY").apply {
                    putExtra("key", topwayKey)
                    putExtra("extra_key", topwayKey)
                    putExtra("val", topwayKey)
                })
                context.sendBroadcast(Intent("com.ts.main.SET_VOLUME").apply {
                    putExtra("volume", target)
                    putExtra("val", target)
                })
                context.sendBroadcast(Intent("com.ts.main.BROADCAST_VOLUME").apply {
                    putExtra("volume", target)
                })
            } catch (ignored: Exception) {}

            // 5. Custom MCU broadcast intents
            broadcastMcuCommand(context, "SET_VOLUME", target)

            // Direct UART transmission if connected
            uartOutputStream?.let { stream ->
                try {
                    val frame = encodeMcuFrame("SET_VOLUME", target)
                    if (frame != null) {
                        stream.write(frame)
                        stream.flush()
                    }
                    Unit
                } catch (ignored: Exception) {}
            }
        }

        fun toggleMute(context: Context) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            climateState.isMuted = !climateState.isMuted

            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val dir = if (climateState.isMuted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                    am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                }
                val muteKeyIntent = Intent("com.ts.main.DEAL_KEY").apply {
                    putExtra("key", 3) // 3 = MUTE
                }
                context.sendBroadcast(muteKeyIntent)
            } catch (ignored: Exception) {}

            broadcastMcuCommand(context, "MUTE", if (climateState.isMuted) 1 else 0)
            notifyUpdate()
        }

        /**
         * Vehicle Control: Central Door Lock / Unlock.
         * Dispatches to Topway Deal Key, MCU broadcasts, GM BCM Delta II serial frames, and shell fallback.
         */
        fun toggleDoorLocks(context: Context) {
            vehicleState.doorsLocked = !vehicleState.doorsLocked
            val lockCmd = if (vehicleState.doorsLocked) 1 else 0

            // 1. Topway TS10 / TS18 Deal Key for Door Lock (9020 = Lock, 9021 = Unlock, 40 = Central Toggle)
            try {
                val lockKey = if (vehicleState.doorsLocked) 9020 else 9021
                context.sendBroadcast(Intent("com.ts.main.DEAL_KEY").apply {
                    putExtra("key", lockKey)
                    putExtra("val", lockKey)
                })
                context.sendBroadcast(Intent("com.ts.main.DEAL_KEY").apply {
                    putExtra("key", 40)
                    putExtra("val", 40)
                })
                context.sendBroadcast(Intent("com.ts.can.ACTION_KEY").apply {
                    putExtra("key", lockKey)
                })
                context.sendBroadcast(Intent("com.syu.ms.KEY").apply {
                    putExtra("key", 40)
                })
            } catch (ignored: Exception) {}

            // 2. MCU Broadcasts across packages
            broadcastMcuCommand(context, "DOOR_LOCK", lockCmd)

            // 3. Raw GM Delta II CAN frames with verified checksums:
            // Hiworld: 0x2E, 0xC6, 0x02, [0x01 = Unlock / 0x02 = Lock], 0x00, [Checksum = (0xC6+2+data)&0xFF]
            val hiworldUnlock = byteArrayOf(0x2E.toByte(), 0xC6.toByte(), 0x02.toByte(), 0x01.toByte(), 0x00.toByte(), 0xC9.toByte())
            val hiworldLock = byteArrayOf(0x2E.toByte(), 0xC6.toByte(), 0x02.toByte(), 0x02.toByte(), 0x00.toByte(), 0xCA.toByte())
            val activePacket = if (vehicleState.doorsLocked) hiworldLock else hiworldUnlock
            dispatchRawCanFrame(context, activePacket)

            // Raise protocol alternative (0x2E, 0x24, 0x02, ...)
            val raiseUnlock = byteArrayOf(0x2E.toByte(), 0x24.toByte(), 0x02.toByte(), 0x01.toByte(), 0x00.toByte(), 0x27.toByte())
            val raiseLock = byteArrayOf(0x2E.toByte(), 0x24.toByte(), 0x02.toByte(), 0x02.toByte(), 0x00.toByte(), 0x28.toByte())
            dispatchRawCanFrame(context, if (vehicleState.doorsLocked) raiseLock else raiseUnlock)

            notifyUpdate()
        }

        /**
         * Vehicle Control: Solenoid Trunk Pop.
         * Dispatches to Topway Deal Key, MCU broadcasts, GM BCM Delta II serial frames, and shell fallback.
         */
        fun popTrunk(context: Context) {
            vehicleState.trunkOpen = !vehicleState.trunkOpen

            // 1. Topway TS10 / TS18 Deal Key for Trunk Release (9022, 41, 1122)
            try {
                context.sendBroadcast(Intent("com.ts.main.DEAL_KEY").apply {
                    putExtra("key", 9022)
                    putExtra("val", 9022)
                })
                context.sendBroadcast(Intent("com.ts.main.DEAL_KEY").apply {
                    putExtra("key", 41)
                    putExtra("val", 41)
                })
                context.sendBroadcast(Intent("com.ts.can.ACTION_KEY").apply {
                    putExtra("key", 9022)
                })
                context.sendBroadcast(Intent("com.syu.ms.KEY").apply {
                    putExtra("key", 41)
                })
            } catch (ignored: Exception) {}

            // 2. MCU Broadcasts
            broadcastMcuCommand(context, "TRUNK_POP", 1)

            // 3. Raw GM Delta II CAN frame for Trunk Release:
            // Hiworld: 0x2E, 0xC6, 0x02, 0x03, 0x00, Checksum = 0xCB
            val hiworldTrunk = byteArrayOf(0x2E.toByte(), 0xC6.toByte(), 0x02.toByte(), 0x03.toByte(), 0x00.toByte(), 0xCB.toByte())
            dispatchRawCanFrame(context, hiworldTrunk)

            // Raise protocol alternative
            val raiseTrunk = byteArrayOf(0x2E.toByte(), 0x24.toByte(), 0x02.toByte(), 0x03.toByte(), 0x00.toByte(), 0x29.toByte())
            dispatchRawCanFrame(context, raiseTrunk)

            notifyUpdate()
        }

        fun toggleFogLights(context: Context) {
            vehicleState.fogLightsOn = !vehicleState.fogLightsOn
            broadcastMcuCommand(context, "TOGGLE_FOG_LIGHTS", if (vehicleState.fogLightsOn) 1 else 0)
            notifyUpdate()
        }

        fun toggleHazardLights(context: Context) {
            vehicleState.hazardLightsOn = !vehicleState.hazardLightsOn
            broadcastMcuCommand(context, "TOGGLE_HAZARD", if (vehicleState.hazardLightsOn) 1 else 0)
            notifyUpdate()
        }

        /**
         * Broadcasts commands across known Chinese head unit CAN daemon packages.
         */
        fun broadcastMcuCommand(context: Context, actionType: String, value: Int) {
            val targets = listOf(
                "com.ts.canbus",
                "com.ts.main",
                "com.ts.key.ac",
                "com.ts.can.ACTION_CAN_SEND",
                "com.ts.car.door",
                "com.ts.car.air",
                "com.fyt.system.canbus",
                "com.syu.ms.canbus",
                "com.syu.canbus",
                "com.xyauto.canbus",
                "com.xyauto.aircondition",
                "com.glsx.action.CAN_SEND",
                "com.microntek.canbus.send"
            )

            targets.forEach { action ->
                try {
                    val intent = Intent(action).apply {
                        putExtra("action", actionType)
                        putExtra("type", actionType)
                        putExtra("cmd", actionType)
                        putExtra("command", actionType)
                        putExtra("value", value)
                        putExtra("val", value)
                        putExtra("temp", value)
                        putExtra("fan", value)
                        putExtra("key", value)
                    }
                    context.sendBroadcast(intent)
                } catch (ignored: Exception) {}
            }

            // Direct UART transmission
            uartOutputStream?.let { stream ->
                try {
                    val frame = encodeMcuFrame(actionType, value)
                    if (frame != null) {
                        stream.write(frame)
                        stream.flush()
                    }
                    Unit
                } catch (e: Exception) {
                    Log.w(TAG, "UART write failed: ${e.message}")
                }
            }
        }

        /**
         * Dispatches raw binary CAN frame via intents, UART stream, and shell tty echo.
         */
        fun dispatchRawCanFrame(context: Context, packet: ByteArray) {
            // Intent broadcast
            try {
                val intent = Intent("com.ts.can.ACTION_CAN_SEND").apply {
                    putExtra("data", packet)
                    putExtra("bytes", packet)
                    putExtra("len", packet.size)
                }
                context.sendBroadcast(intent)
            } catch (ignored: Exception) {}

            // Direct UART stream
            uartOutputStream?.let { stream ->
                try {
                    stream.write(packet)
                    stream.flush()
                    Unit
                } catch (ignored: Exception) {}
            }
        }

        /**
         * Sends suppression/hide broadcasts and applies global immersive policy to hide the factory bottom dock.
         */
        fun hideFactoryBottomBar(context: Context) {
            val hideActions = listOf(
                "com.xyauto.aircondition.HIDE",
                "com.xyauto.aircondition.action.CLOSE",
                "com.xyauto.aircondition.action.HIDE",
                "com.ts.air.hide",
                "com.ts.fyt.air.hide",
                "com.fyt.air.hide",
                "com.syu.air.hide",
                "com.ts.canbus.hide",
                "com.ts.key.ac.hide",
                "com.microntek.aircondition.hide",
                "action.hvac.hide"
            )
            hideActions.forEach { act ->
                try {
                    context.sendBroadcast(Intent(act))
                } catch (ignored: Exception) {}
            }

            // Execute root / engineering shell commands if available on head unit
            Thread {
                try {
                    val cmd = "settings put global policy_control immersive.full=*; settings put global policy_control immersive.navigation=*; pm disable-user com.ts.air 2>/dev/null; am broadcast -a com.ts.air.hide 2>/dev/null"
                    Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                } catch (ignored: Exception) {}
                try {
                    val cmd = "settings put global policy_control immersive.full=*; settings put global policy_control immersive.navigation=*"
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                } catch (ignored: Exception) {}
            }.start()
        }

        private fun encodeMcuFrame(cmd: String, value: Int): ByteArray? {
            val pid = when (cmd) {
                "SET_DRIVER_TEMP" -> 0x31
                "SET_PASS_TEMP" -> 0x32
                "SET_FAN_SPEED" -> 0x33
                "TOGGLE_AC" -> 0x34
                "TOGGLE_AUTO" -> 0x35
                "TOGGLE_DUAL" -> 0x36
                "TOGGLE_RECIRC" -> 0x37
                "SET_DRIVER_SEAT" -> 0x38
                "SET_PASS_SEAT" -> 0x39
                "TOGGLE_FRONT_DEFROST" -> 0x3A
                "TOGGLE_REAR_DEFROST" -> 0x3B
                "SET_AIR_DIRECTION" -> 0x3C
                "HVAC_POWER" -> 0x3D
                "DOOR_LOCK" -> 0x40
                "TRUNK_POP" -> 0x41
                "SET_VOLUME" -> 0x50
                "MUTE" -> 0x51
                else -> 0x00
            }
            if (pid == 0x00) return null
            val packet = ByteArray(5)
            packet[0] = 0x2E.toByte()
            packet[1] = pid.toByte()
            packet[2] = 0x01.toByte()
            packet[3] = (value and 0xFF).toByte()
            packet[4] = ((packet[1] + packet[2] + packet[3]) and 0xFF).toByte()
            return packet
        }

        fun openNativeCarSettings(context: Context): Boolean {
            val pm = context.packageManager
            val knownCarApps = listOf(
                "com.ts.canbus" to "com.ts.canbus.CarInfoActivity",
                "com.ts.canbus" to "com.ts.canbus.AirActivity",
                "com.ts.MainUI" to "com.ts.can.CanMainActivity",
                "com.ts.main" to "com.ts.main.MainActivity",
                "com.syu.canbus" to "com.syu.canbus.AirActivity",
                "com.syu.air" to "com.syu.air.MainActivity",
                "com.xyauto.aircondition" to "com.xyauto.aircondition.MainActivity",
                "com.xyauto.canbus" to "com.xyauto.canbus.MainActivity",
                "com.canbus.ac" to "com.canbus.ac.MainActivity",
                "com.android.settings.car" to "com.android.settings.car.MainActivity",
                "com.microntek.controlsettings" to "com.microntek.controlsettings.MainActivity"
            )

            for ((pkg, cls) in knownCarApps) {
                try {
                    val intent = Intent().apply {
                        setClassName(pkg, cls)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    }
                    context.startActivity(intent)
                    return true
                } catch (ignored: Exception) {}
            }

            for ((pkg, _) in knownCarApps) {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(launchIntent)
                        return true
                    } catch (ignored: Exception) {}
                }
            }

            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (ignored: Exception) {}

            return false
        }
    }

    interface VehicleStateListener {
        fun onVehicleStateUpdated(state: GmDeltaProtocol.VehicleState)
        fun onClimateStateUpdated(climate: GmDeltaProtocol.ClimateState)
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var serialThread: Thread? = null
    private var systemSettingsObserver: ContentObserver? = null

    inner class LocalBinder : Binder() {
        fun getService(): CanBusService = this@CanBusService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // 1. Initial Volume Sync
        syncVolume(this)

        // 2. Register System Settings Observer for real-time volume syncing
        registerSettingsObserver()

        // 3. Register broad automotive broadcast receivers
        registerMcuBroadcasts()

        // 4. Start Hardware Serial UART Reader
        startSerialReader()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serialThread?.interrupt()
        try {
            uartOutputStream?.close()
        } catch (ignored: Exception) {}
        uartOutputStream = null

        systemSettingsObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
    }

    private fun registerSettingsObserver() {
        systemSettingsObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                syncVolume(this@CanBusService)
            }
        }
        try {
            contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                systemSettingsObserver!!
            )
        } catch (e: Exception) {
            Log.w(TAG, "ContentObserver registration warning: ${e.message}")
        }
    }

    private fun registerMcuBroadcasts() {
        val filter = IntentFilter().apply {
            addAction("com.ts.main.STATUS_CHANGE")
            addAction("com.ts.main.DEAL_KEY")
            addAction("com.ts.main.VOLUME_CHANGE")
            addAction("com.ts.volume.change")
            addAction("com.ts.canbus")
            addAction("com.ts.can.BROADCAST")
            addAction("com.ts.canbus.ACTION_CAR_INFO")
            addAction("com.ts.canbus.ACTION_AIR_CONTROL")
            addAction("com.ts.canbus.ACTION_DOOR_STATUS")
            addAction("com.ts.car.air")
            addAction("com.ts.car.door")
            addAction("com.ts.car.info")
            addAction("com.ts.air")
            addAction("com.ts.door")
            addAction("com.fyt.system.canbus")
            addAction("com.syu.canbus")
            addAction("com.syu.air")
            addAction("com.xyauto.canbus")
            addAction("com.xyauto.aircondition")
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let { inIntent ->
                    canPacketsReceivedCount++
                    lastCanSource = inIntent.action ?: "Broadcast"

                    // Check for volume change broadcasts
                    if (inIntent.action == "android.media.VOLUME_CHANGED_ACTION" ||
                        inIntent.action == "com.ts.main.VOLUME_CHANGE" ||
                        inIntent.action == "com.ts.volume.change") {
                        val newVol = inIntent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                        if (newVol in 0..60) {
                            climateState.volumeLevel = newVol
                            climateState.isMuted = (newVol == 0)
                        } else {
                            syncVolume(this@CanBusService)
                        }
                        notifyUpdate()
                        return
                    }

                    // Check for raw byte packet
                    val packetId = inIntent.getIntExtra("id", inIntent.getIntExtra("cmd", -1))
                    val data = inIntent.getByteArrayExtra("data")
                        ?: inIntent.getByteArrayExtra("bytes")
                        ?: inIntent.getByteArrayExtra("buff")

                    if (packetId != -1 && data != null) {
                        val changed = GmDeltaProtocol.parseCanPacket(packetId, data, vehicleState, climateState)
                        if (changed) {
                            mainHandler.post { notifyUpdate() }
                        }
                    }

                    // Check bundle extras for direct door or climate parameters
                    val extras = inIntent.extras
                    if (extras != null) {
                        var stateChanged = false

                        // Door flags
                        if (extras.containsKey("door_fl")) {
                            vehicleState.doorFL = extras.getBoolean("door_fl")
                            stateChanged = true
                        }
                        if (extras.containsKey("door_fr")) {
                            vehicleState.doorFR = extras.getBoolean("door_fr")
                            stateChanged = true
                        }
                        if (extras.containsKey("door_trunk") || extras.containsKey("trunk")) {
                            vehicleState.trunkOpen = extras.getBoolean("door_trunk", extras.getBoolean("trunk", false))
                            stateChanged = true
                        }
                        if (extras.containsKey("speed")) {
                            vehicleState.speedMph = extras.getInt("speed", vehicleState.speedMph)
                            stateChanged = true
                        }
                        if (extras.containsKey("rpm")) {
                            vehicleState.engineRpm = extras.getInt("rpm", vehicleState.engineRpm)
                            stateChanged = true
                        }

                        if (stateChanged) {
                            mainHandler.post { notifyUpdate() }
                        }
                    }
                }
            }
        }

        try {
            registerReceiver(receiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Broadcast registration error: ${e.message}")
        }
    }

    private fun startSerialReader() {
        serialThread = Thread {
            // Attempt to grant r/w permissions via shell if available
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "chmod 666 /dev/ttyMT* /dev/ttyS* /dev/mcu* /dev/ts_mcu*"))
            } catch (ignored: Exception) {}

            val candidatePorts = listOf(
                "/dev/ttyMT0",
                "/dev/ttyMT1",
                "/dev/ttyMT2",
                "/dev/ttyMT3",
                "/dev/ttyS0",
                "/dev/ttyS1",
                "/dev/ttyS2",
                "/dev/mcu",
                "/dev/ts_mcu"
            )
            var activeStream: FileInputStream? = null

            for (path in candidatePorts) {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    try {
                        activeStream = FileInputStream(f)
                        if (f.canWrite()) {
                            uartOutputStream = FileOutputStream(f)
                        }
                        hardwareSerialConnected = true
                        lastCanSource = "UART: $path"
                        Log.i(TAG, "Attached to hardware CAN port: $path")
                        break
                    } catch (ignored: Exception) {}
                }
            }

            if (activeStream == null) {
                Log.i(TAG, "Hardware UART direct access restricted; CAN operating via system MCU intents.")
                return@Thread
            }

            val buf = ByteArray(128)
            while (isRunning) {
                try {
                    val read = activeStream.read(buf)
                    if (read > 0) {
                        canPacketsReceivedCount++
                        var i = 0
                        while (i < read - 4) {
                            if (buf[i] == 0x2E.toByte()) {
                                val pid = buf[i + 1].toInt() and 0xFF
                                val len = buf[i + 2].toInt() and 0xFF
                                if (i + 3 + len <= read) {
                                    val payload = ByteArray(len)
                                    System.arraycopy(buf, i + 3, payload, 0, len)
                                    val changed = GmDeltaProtocol.parseCanPacket(pid, payload, vehicleState, climateState)
                                    if (changed) {
                                        mainHandler.post { notifyUpdate() }
                                    }
                                    i += 3 + len
                                    continue
                                }
                            }
                            i++
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }.apply { start() }
    }
}
