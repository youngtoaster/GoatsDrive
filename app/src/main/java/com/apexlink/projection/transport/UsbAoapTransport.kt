package com.apexlink.projection.transport

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import com.apexlink.projection.NativeBridge
import java.io.FileDescriptor
import java.nio.charset.StandardCharsets

class UsbAoapTransport(private val usbManager: UsbManager) {

    companion object {
        private const val TAG = "GD_UsbAoap"

        // Android Open Accessory Protocol (AOAP 2.0) Control Commands
        private const val AOA_GET_PROTOCOL = 51
        private const val AOA_SEND_STRING = 52
        private const val AOA_START_ACCESSORY = 53

        // String descriptor indexes
        private const val AOA_STRING_MANUFACTURER = 0
        private const val AOA_STRING_MODEL = 1
        private const val AOA_STRING_DESCRIPTION = 2
        private const val AOA_STRING_VERSION = 3
        private const val AOA_STRING_URI = 4
        private const val AOA_STRING_SERIAL = 5

        private const val GOOGLE_AOA_VID = 0x18D1
        private const val GOOGLE_AOA_PID_1 = 0x2D00 // Accessory only
        private const val GOOGLE_AOA_PID_2 = 0x2D01 // Accessory + ADB
    }

    private var currentPfd: ParcelFileDescriptor? = null
    private var activeFd: Int = -1

    /**
     * Inspects attached USB Device and initiates AOA 2.0 handshake switch.
     */
    fun handleDeviceAttached(device: UsbDevice): Boolean {
        Log.i(TAG, "Inspecting USB Device: VID=0x${Integer.toHexString(device.vendorId)}, PID=0x${Integer.toHexString(device.productId)}")

        if (device.vendorId == GOOGLE_AOA_VID &&
            (device.productId == GOOGLE_AOA_PID_1 || device.productId == GOOGLE_AOA_PID_2)) {
            Log.i(TAG, "Device is already operating in AOA mode. Awaiting UsbAccessory attached event.")
            return true
        }

        val connection: UsbDeviceConnection = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Failed to open UsbDeviceConnection. Ensure USB host permission is granted.")
            return false
        }

        try {
            // Step 1: Query AOA Protocol Version
            val versionBuffer = ByteArray(2)
            val versionLen = connection.controlTransfer(
                0xC0, // Endpoint Direction IN | Vendor | Device
                AOA_GET_PROTOCOL,
                0,
                0,
                versionBuffer,
                versionBuffer.size,
                1500
            )

            if (versionLen < 2) {
                Log.w(TAG, "Device does not respond to AOA version inquiry.")
                return false
            }

            val protocol = (versionBuffer[1].toInt() shl 8) or (versionBuffer[0].toInt() and 0xFF)
            Log.i(TAG, "Device supports AOA Protocol version: $protocol")
            if (protocol < 1) {
                Log.e(TAG, "Unsupported AOA protocol version: $protocol")
                return false
            }

            // Step 2: Transmit Identity Strings to Smartphone
            sendAoaString(connection, AOA_STRING_MANUFACTURER, "Android")
            sendAoaString(connection, AOA_STRING_MODEL, "Android Open Automotive Protocol")
            sendAoaString(connection, AOA_STRING_DESCRIPTION, "GoatsDrive Car Projection")
            sendAoaString(connection, AOA_STRING_VERSION, "2.0")
            sendAoaString(connection, AOA_STRING_URI, "https://github.com/apexlink/goatsdrive")
            sendAoaString(connection, AOA_STRING_SERIAL, "GD-MCU-001")

            // Step 3: Trigger Phone to Switch into Accessory Mode
            val startRes = connection.controlTransfer(
                0x40, // Endpoint Direction OUT | Vendor | Device
                AOA_START_ACCESSORY,
                0,
                0,
                null,
                0,
                1500
            )

            Log.i(TAG, "Triggered AOA switch result: $startRes. Device will re-enumerate on USB bus.")
            return startRes >= 0

        } catch (e: Exception) {
            Log.e(TAG, "Error negotiating AOA mode", e)
            return false
        } finally {
            connection.close()
        }
    }

    private fun sendAoaString(connection: UsbDeviceConnection, index: Int, string: String) {
        val bytes = (string + "\u0000").toByteArray(StandardCharsets.UTF_8)
        connection.controlTransfer(
            0x40,
            AOA_SEND_STRING,
            0,
            index,
            bytes,
            bytes.size,
            1000
        )
    }

    /**
     * Connects to re-enumerated AOA Accessory and passes the native POSIX FD directly to C++.
     */
    fun openAccessoryTransport(accessory: UsbAccessory): Boolean {
        close()
        Log.i(TAG, "Opening USB Accessory: ${accessory.manufacturer} - ${accessory.model}")

        val pfd = usbManager.openAccessory(accessory) ?: run {
            Log.e(TAG, "Failed to obtain ParcelFileDescriptor for accessory.")
            return false
        }

        currentPfd = pfd
        val nativeFd = extractNativeFd(pfd)
        if (nativeFd < 0) {
            Log.e(TAG, "Failed to resolve native integer FD from ParcelFileDescriptor.")
            pfd.close()
            currentPfd = null
            return false
        }

        activeFd = nativeFd
        Log.i(TAG, "Passing native USB FD ($activeFd) directly to C++ transport engine")
        return NativeBridge.nativeStartUsbTransport(activeFd)
    }

    private fun extractNativeFd(pfd: ParcelFileDescriptor): Int {
        return try {
            // Android SDK public getter
            pfd.fd
        } catch (e: Exception) {
            try {
                val fdField = FileDescriptor::class.java.getDeclaredField("descriptor")
                fdField.isAccessible = true
                fdField.getInt(pfd.fileDescriptor)
            } catch (ex: Exception) {
                -1
            }
        }
    }

    fun close() {
        if (activeFd >= 0) {
            activeFd = -1
        }
        currentPfd?.let {
            try {
                it.close()
            } catch (ignored: Exception) {}
            currentPfd = null
        }
    }
}
