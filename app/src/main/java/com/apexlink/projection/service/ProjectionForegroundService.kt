package com.apexlink.projection.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.apexlink.projection.transport.UsbAoapTransport
import com.apexlink.projection.transport.WirelessSocketManager

class ProjectionForegroundService : Service() {

    companion object {
        private const val TAG = "GD_ProjectionSvc"
        private const val NOTIFICATION_CHANNEL_ID = "gd_projection_daemon"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_INITIALIZE_HARDWARE = "com.apexlink.projection.ACTION_INIT"
        const val ACTION_STOP_PROJECTION = "com.apexlink.projection.ACTION_STOP"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var usbTransport: UsbAoapTransport
    private lateinit var wirelessManager: WirelessSocketManager
    private lateinit var audioFocusController: AudioFocusController

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating ProjectionForegroundService")

        // Elevate service thread priority for realtime display & processing
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usbTransport = UsbAoapTransport(usbManager)
        wirelessManager = WirelessSocketManager(this)
        audioFocusController = AudioFocusController(this)

        acquireLocks()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY

        when (intent.action) {
            ACTION_INITIALIZE_HARDWARE -> {
                handleHardwareTrigger(intent)
            }
            ACTION_STOP_PROJECTION -> {
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun handleHardwareTrigger(intent: Intent) {
        // Check for direct USB Accessory
        val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }

        if (accessory != null) {
            Log.i(TAG, "Initiating USB Accessory transport...")
            audioFocusController.requestMediaFocus()
            usbTransport.openAccessoryTransport(accessory)
            return
        }

        // Check for USB Device needing AOA 2.0 handshake
        val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        if (usbDevice != null) {
            Log.i(TAG, "Starting USB AOA 2.0 negotiation for VID=0x${Integer.toHexString(usbDevice.vendorId)}")
            usbTransport.handleDeviceAttached(usbDevice)
            return
        }

        // Check for Bluetooth Wireless Trigger
        val btDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        if (btDevice != null) {
            Log.i(TAG, "Initiating Wireless Projection State Machine with ${btDevice.address}")
            audioFocusController.requestMediaFocus()
            wirelessManager.startWirelessListener(btDevice)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoatsDrive:CpuWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GoatsDrive:WifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GoatsDrive Projection Daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Automotive projection receiver pipeline active"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("GoatsDrive Active")
            .setContentText("Zero-copy projection stream running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Destroying ProjectionForegroundService")

        audioFocusController.abandonFocus()
        usbTransport.close()
        wirelessManager.stop()

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
