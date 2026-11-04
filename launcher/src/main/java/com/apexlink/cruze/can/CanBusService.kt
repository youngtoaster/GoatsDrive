package com.apexlink.cruze.can

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-performance background service listening to and broadcasting automotive CAN-bus signals
 * across FYT, Topway (TS10/TS18), XYAuto, and Microntek head unit architectures.
 */
class CanBusService : Service() {

    companion object {
        private const val TAG = "Cruze_CAN"

        val vehicleState = GmDeltaProtocol.VehicleState()
        val climateState = GmDeltaProtocol.ClimateState()

        private val listeners = CopyOnWriteArrayList<VehicleStateListener>()
        private var uartOutputStream: FileOutputStream? = null

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
         * Broadcasts a climate or vehicle command to aftermarket head unit MCU daemons
         * and writes directly to hardware UART if connected.
         */
        fun broadcastMcuCommand(context: Context, actionType: String, value: Int) {
            val targets = listOf(
                "com.ts.canbus",
                "com.ts.key.ac",
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
                    }
                    context.sendBroadcast(intent)
                } catch (ignored: Exception) {}
            }

            // Direct UART transmission if MCU serial bus is open
            uartOutputStream?.let { stream ->
                try {
                    val frame = encodeMcuFrame(actionType, value)
                    if (frame != null) {
                        stream.write(frame)
                        stream.flush()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "UART write failed: ${e.message}")
                }
                Unit
            }
        }

        /**
         * Sends suppression/hide broadcasts to native MCU overlays (e.g. factory bottom AC bar).
         */
        fun hideFactoryBottomBar(context: Context) {
            val hideActions = listOf(
                "com.xyauto.aircondition.HIDE",
                "com.ts.air.hide",
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
        }

        /**
         * Adjusts head unit master volume via Android AudioManager and MCU.
         */
        fun adjustVolume(context: Context, direction: Int) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val dir = if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                climateState.volumeLevel = current
                climateState.isMuted = (current == 0)
            } else {
                climateState.volumeLevel = (climateState.volumeLevel + direction).coerceIn(0, 30)
            }
            broadcastMcuCommand(context, "SET_VOLUME", climateState.volumeLevel)
            notifyUpdate()
        }

        fun toggleMute(context: Context) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            climateState.isMuted = !climateState.isMuted
            if (am != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val dir = if (climateState.isMuted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                }
            }
            broadcastMcuCommand(context, "MUTE", if (climateState.isMuted) 1 else 0)
            notifyUpdate()
        }

        /**
         * Vehicle Control: Central Door Locks.
         */
        fun toggleDoorLocks(context: Context) {
            vehicleState.doorsLocked = !vehicleState.doorsLocked
            broadcastMcuCommand(context, "DOOR_LOCK", if (vehicleState.doorsLocked) 1 else 0)
            notifyUpdate()
        }

        /**
         * Vehicle Control: Solenoid Trunk Pop.
         */
        fun popTrunk(context: Context) {
            vehicleState.trunkOpen = !vehicleState.trunkOpen
            broadcastMcuCommand(context, "TRUNK_POP", 1)
            notifyUpdate()
        }

        /**
         * Vehicle Control: Front Fog Lamps.
         */
        fun toggleFogLights(context: Context) {
            vehicleState.fogLightsOn = !vehicleState.fogLightsOn
            broadcastMcuCommand(context, "TOGGLE_FOG_LIGHTS", if (vehicleState.fogLightsOn) 1 else 0)
            notifyUpdate()
        }

        /**
         * Vehicle Control: Hazard Flashers.
         */
        fun toggleHazardLights(context: Context) {
            vehicleState.hazardLightsOn = !vehicleState.hazardLightsOn
            broadcastMcuCommand(context, "TOGGLE_HAZARD", if (vehicleState.hazardLightsOn) 1 else 0)
            notifyUpdate()
        }

        private fun encodeMcuFrame(cmd: String, value: Int): ByteArray? {
            // Standard FYT / Raise MCU serial command packet: 0x2E, PID, LEN, DATA..., CHECKSUM
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
            packet[2] = 0x01.toByte() // 1 byte payload
            packet[3] = (value and 0xFF).toByte()
            packet[4] = ((packet[1] + packet[2] + packet[3]) and 0xFF).toByte() // simple checksum
            return packet
        }

        /**
         * Launches the head unit's native factory vehicle settings & climate console.
         */
        fun openNativeCarSettings(context: Context): Boolean {
            val pm = context.packageManager
            val knownCarApps = listOf(
                "com.ts.canbus" to "com.ts.canbus.CarInfoActivity",
                "com.ts.canbus" to "com.ts.canbus.AirActivity",
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

            // Fallback: try package launch intent
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

            // Fallback 2: General settings
            try {
                val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
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

    inner class LocalBinder : Binder() {
        fun getService(): CanBusService = this@CanBusService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        registerMcuBroadcasts()
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
    }

    private fun registerMcuBroadcasts() {
        val filter = IntentFilter().apply {
            addAction("com.fyt.system.canbus")
            addAction("com.ts.canbus")
            addAction("com.xyauto.canbus")
            addAction("com.xyauto.aircondition")
            addAction("com.microntek.canbus")
            addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let {
                    val packetId = it.getIntExtra("id", -1)
                    val data = it.getByteArrayExtra("data")
                    if (packetId != -1 && data != null) {
                        val changed = GmDeltaProtocol.parseCanPacket(packetId, data, vehicleState, climateState)
                        if (changed) {
                            mainHandler.post { notifyUpdate() }
                        }
                    }
                }
            }
        }
        try {
            registerReceiver(receiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Broadcast registration fallback: ${e.message}")
        }
    }

    private fun startSerialReader() {
        serialThread = Thread {
            val candidatePorts = listOf("/dev/ttyMT0", "/dev/ttyS0", "/dev/ttyS1", "/dev/ttyS2")
            var activeStream: FileInputStream? = null

            for (path in candidatePorts) {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    try {
                        activeStream = FileInputStream(f)
                        if (f.canWrite()) {
                            uartOutputStream = FileOutputStream(f)
                        }
                        Log.i(TAG, "Attached to hardware CAN port: $path")
                        break
                    } catch (ignored: Exception) {}
                }
            }

            if (activeStream == null) {
                Log.i(TAG, "CAN active via system MCU intents.")
                return@Thread
            }

            val buf = ByteArray(128)
            while (isRunning) {
                try {
                    val read = activeStream.read(buf)
                    if (read > 0) {
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
