package com.apexlink.installer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Manages USB connection state, USB ADB device discovery, USB tethering detection,
 * and USB device permissions.
 */
object UsbAdbManager {

    private const val TAG = "GD_UsbManager"
    const val ACTION_USB_PERMISSION = "com.apexlink.installer.USB_PERMISSION"

    enum class UsbMode {
        DIRECT_ADB_DEVICE,  // Direct USB Host/OTG device with ADB interface
        USB_TETHERING,      // USB Tethering network active (RNDIS/NCM/USB interface)
        USB_CABLE_ONLY,     // Phone charging/plugged via USB, but needs OTG or Tethering toggle
        DISCONNECTED        // No USB cable detected
    }

    data class UsbDetectionResult(
        val mode: UsbMode,
        val device: UsbDevice? = null,
        val tetherIp: String? = null,
        val statusMessage: String
    )

    /**
     * Inspects connected USB devices and network interfaces to detect the best USB connection.
     */
    fun detectUsbConnection(context: Context): UsbDetectionResult {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // Priority 1: Direct USB Host ADB Device (Class 255, Subclass 66, Protocol 1)
        val adbDevice = findAdbDevice(usbManager)
        if (adbDevice != null) {
            val name = adbDevice.productName ?: adbDevice.deviceName
            return UsbDetectionResult(
                mode = UsbMode.DIRECT_ADB_DEVICE,
                device = adbDevice,
                statusMessage = "USB ADB Device Detected: $name"
            )
        }

        // Priority 2: USB Reverse Tethering / RNDIS network interface
        val tetherIp = detectUsbTetherIp()
        if (tetherIp != null) {
            return UsbDetectionResult(
                mode = UsbMode.USB_TETHERING,
                tetherIp = tetherIp,
                statusMessage = "USB Network Active ($tetherIp:5555)"
            )
        }

        // Priority 3: USB Cable is physically plugged in
        if (isUsbCablePlugged(context)) {
            return UsbDetectionResult(
                mode = UsbMode.USB_CABLE_ONLY,
                statusMessage = "USB Cable Connected (Enable USB Tethering or use OTG adapter)"
            )
        }

        return UsbDetectionResult(
            mode = UsbMode.DISCONNECTED,
            statusMessage = "No USB connection detected"
        )
    }

    /**
     * Locates a connected USB device that provides an ADB interface.
     */
    fun findAdbDevice(usbManager: UsbManager): UsbDevice? {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == 0xFF && intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x01) {
                    return device
                }
            }
        }
        return null
    }

    fun hasPermission(context: Context, device: UsbDevice): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.hasPermission(device)
    }

    fun requestPermission(
        context: Context,
        device: UsbDevice,
        onResult: (Boolean) -> Unit
    ) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            onResult(true)
            return
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            flags
        )

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    ctx.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB Permission result: granted=$granted")
                    onResult(granted)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * Checks if a USB tethering network interface (rndis0, usb0, ncm0) is active.
     */
    fun detectUsbTetherIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                if ((name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm")) && intf.isUp) {
                    val addrs = intf.inetAddresses
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            // If phone is 192.168.42.x, head unit is commonly 192.168.42.129 or default gateway
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking USB network interfaces: ${e.message}")
        }
        return null
    }

    /**
     * Checks whether the device is currently plugged into USB for power/data.
     */
    fun isUsbCablePlugged(context: Context): Boolean {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            plugged == BatteryManager.BATTERY_PLUGGED_USB
        } catch (e: Exception) {
            false
        }
    }
}
