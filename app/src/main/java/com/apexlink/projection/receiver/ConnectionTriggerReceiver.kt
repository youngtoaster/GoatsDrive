package com.apexlink.projection.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.apexlink.projection.MainActivity
import com.apexlink.projection.service.ProjectionForegroundService

class ConnectionTriggerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GD_TriggerRx"
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Hardware trigger received: $action")

        when (action) {
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                }
                Log.i(TAG, "USB Accessory Attached: ${accessory?.model}")
                triggerProjection(context, usbAccessory = accessory)
            }

            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                Log.i(TAG, "USB Device Attached: VID=0x${Integer.toHexString(device?.vendorId ?: 0)}")
                triggerProjection(context, usbDevice = device)
            }

            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val btDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

                if (btDevice == null) return

                val isPhone = btDevice.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
                Log.i(TAG, "Bluetooth Device Connected: ${btDevice.name ?: btDevice.address} (isPhone=$isPhone)")

                if (isPhone) {
                    triggerProjection(context, btDevice = btDevice)
                }
            }
        }
    }

    private fun triggerProjection(
        context: Context,
        usbDevice: UsbDevice? = null,
        usbAccessory: UsbAccessory? = null,
        btDevice: BluetoothDevice? = null
    ) {
        // Step 1: Start persistent Foreground Daemon with elevated scheduling
        val serviceIntent = Intent(context, ProjectionForegroundService::class.java).apply {
            action = ProjectionForegroundService.ACTION_INITIALIZE_HARDWARE
            usbDevice?.let { putExtra(UsbManager.EXTRA_DEVICE, it) }
            usbAccessory?.let { putExtra(UsbManager.EXTRA_ACCESSORY, it) }
            btDevice?.let { putExtra(BluetoothDevice.EXTRA_DEVICE, it) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // Step 2: Elevate UI SurfaceView into foreground
        val activityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            usbDevice?.let { putExtra(UsbManager.EXTRA_DEVICE, it) }
            usbAccessory?.let { putExtra(UsbManager.EXTRA_ACCESSORY, it) }
            btDevice?.let { putExtra(BluetoothDevice.EXTRA_DEVICE, it) }
        }
        context.startActivity(activityIntent)
    }
}
