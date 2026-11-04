package com.apexlink.cruze.can

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-performance background service listening to aftermarket car head unit CAN-bus hardware.
 */
class CanBusService : Service() {

    companion object {
        private const val TAG = "Cruze_CAN"

        val vehicleState = GmDeltaProtocol.VehicleState()
        val climateState = GmDeltaProtocol.ClimateState()

        private val listeners = CopyOnWriteArrayList<VehicleStateListener>()

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
    }

    private fun registerMcuBroadcasts() {
        val filter = IntentFilter().apply {
            addAction("com.fyt.system.canbus")
            addAction("com.ts.canbus")
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
            // Common MCU serial UART nodes on FYT, Topway, and T3L platforms
            val candidatePorts = listOf("/dev/ttyMT0", "/dev/ttyS0", "/dev/ttyS1", "/dev/ttyS2")
            var activeStream: FileInputStream? = null

            for (path in candidatePorts) {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    try {
                        activeStream = FileInputStream(f)
                        Log.i(TAG, "Attached to hardware CAN port: $path")
                        break
                    } catch (ignored: Exception) {}
                }
            }

            if (activeStream == null) {
                Log.i(TAG, "Running in CAN simulator/standby mode (No active hardware UART found on host).")
                return@Thread
            }

            val buf = ByteArray(128)
            while (isRunning) {
                try {
                    val read = activeStream.read(buf)
                    if (read > 0) {
                        // Frame parser (0x2E sync header)
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
