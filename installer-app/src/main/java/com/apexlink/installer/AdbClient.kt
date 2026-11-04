package com.apexlink.installer

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Common abstraction for ADB transport layer: TCP socket or USB bulk endpoints.
 */
interface AdbTransport : AutoCloseable {
    fun connect(timeoutMs: Int = 5000): Boolean
    fun readExact(buffer: ByteArray, offset: Int, length: Int)
    fun write(buffer: ByteArray, offset: Int, length: Int)
    val name: String
    val isUsb: Boolean
}

/**
 * Standard TCP ADB Transport (Wi-Fi, Hotspot, or USB Reverse Tethering / RNDIS).
 */
class TcpAdbTransport(val host: String, val port: Int = 5555) : AdbTransport {
    private var socket: Socket? = null
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null

    override val name: String get() = "TCP ($host:$port)"
    override val isUsb: Boolean get() = false

    override fun connect(timeoutMs: Int): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            s.soTimeout = 10000
            socket = s
            inStream = s.getInputStream()
            outStream = s.getOutputStream()
            true
        } catch (e: Exception) {
            Log.e("GD_TcpTransport", "TCP connect failed to $host:$port: ${e.message}")
            false
        }
    }

    override fun readExact(buffer: ByteArray, offset: Int, length: Int) {
        val input = inStream ?: throw IOException("TCP socket not connected")
        var readTotal = 0
        while (readTotal < length) {
            val r = input.read(buffer, offset + readTotal, length - readTotal)
            if (r < 0) throw EOFException("EOF reading from TCP socket")
            readTotal += r
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val out = outStream ?: throw IOException("TCP socket not connected")
        out.write(buffer, offset, length)
        out.flush()
    }

    override fun close() {
        try { socket?.close() } catch (ignored: Exception) {}
        socket = null
        inStream = null
        outStream = null
    }
}

/**
 * Direct USB ADB Transport (Host / OTG Mode).
 * Uses USB bulk endpoints with ADB interface class 255, subclass 66, protocol 1.
 */
class UsbAdbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) : AdbTransport {
    private var connection: UsbDeviceConnection? = null
    private var adbInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    override val name: String get() = "USB (${device.productName ?: device.deviceName})"
    override val isUsb: Boolean get() = true

    override fun connect(timeoutMs: Int): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == 0xFF && intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x01) {
                adbInterface = intf
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            inEndpoint = ep
                        } else {
                            outEndpoint = ep
                        }
                    }
                }
                break
            }
        }

        if (adbInterface == null || inEndpoint == null || outEndpoint == null) {
            Log.w("GD_UsbTransport", "Could not locate ADB endpoints on USB device ${device.deviceName}")
            return false
        }

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.e("GD_UsbTransport", "Failed to open USB device connection (permission required)")
            return false
        }

        if (!conn.claimInterface(adbInterface, true)) {
            Log.e("GD_UsbTransport", "Failed to claim USB ADB interface")
            conn.close()
            return false
        }

        connection = conn
        return true
    }

    override fun readExact(buffer: ByteArray, offset: Int, length: Int) {
        val conn = connection ?: throw IOException("USB connection closed")
        val epIn = inEndpoint ?: throw IOException("No USB IN endpoint")
        var readTotal = 0
        val timeout = 10000

        while (readTotal < length) {
            val chunk = Math.min(length - readTotal, epIn.maxPacketSize.coerceAtLeast(16384))
            val temp = ByteArray(chunk)
            val r = conn.bulkTransfer(epIn, temp, chunk, timeout)
            if (r < 0) throw IOException("USB bulkTransfer IN failed with code $r")
            if (r == 0) continue
            System.arraycopy(temp, 0, buffer, offset + readTotal, r)
            readTotal += r
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val conn = connection ?: throw IOException("USB connection closed")
        val epOut = outEndpoint ?: throw IOException("No USB OUT endpoint")
        var writtenTotal = 0
        val timeout = 10000

        while (writtenTotal < length) {
            val chunk = Math.min(length - writtenTotal, epOut.maxPacketSize.coerceAtLeast(16384))
            val temp = ByteArray(chunk)
            System.arraycopy(buffer, offset + writtenTotal, temp, 0, chunk)
            val w = conn.bulkTransfer(epOut, temp, chunk, timeout)
            if (w < 0) throw IOException("USB bulkTransfer OUT failed with code $w")
            writtenTotal += w
        }
    }

    override fun close() {
        try {
            adbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (ignored: Exception) {}
        connection = null
    }
}

/**
 * High-performance, zero-dependency ADB client.
 * Supports both TCP (Wi-Fi/Hotspot/Tethering) and direct USB Host/OTG connections.
 */
class AdbClient(val transport: AdbTransport) : AutoCloseable {

    constructor(host: String, port: Int = 5555) : this(TcpAdbTransport(host, port))
    constructor(usbManager: UsbManager, device: UsbDevice) : this(UsbAdbTransport(usbManager, device))

    companion object {
        private const val TAG = "GD_AdbClient"

        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257

        const val ADB_VERSION = 0x01000000
        const val MAX_PAYLOAD = 64 * 1024
    }

    private var localStreamId = 1

    data class TestResult(
        val success: Boolean,
        val model: String = "",
        val androidVersion: String = "",
        val platform: String = "",
        val errorMessage: String = ""
    )

    fun testConnection(timeoutMs: Int = 4000): TestResult {
        return try {
            if (!connect(timeoutMs)) {
                TestResult(
                    success = false,
                    errorMessage = "Connection refused or timed out (${transport.name}). Check if USB Debugging / Wireless ADB is enabled."
                )
            } else {
                val model = executeShell("getprop ro.product.model").trim()
                val androidVer = executeShell("getprop ro.build.version.release").trim()
                val platform = executeShell("getprop ro.board.platform").trim()
                TestResult(
                    success = true,
                    model = if (model.isNotEmpty()) model else "Generic Car Head Unit",
                    androidVersion = if (androidVer.isNotEmpty()) androidVer else "Unknown",
                    platform = if (platform.isNotEmpty()) platform else "ARM"
                )
            }
        } catch (e: Exception) {
            TestResult(success = false, errorMessage = e.message ?: "Connection error")
        } finally {
            close()
        }
    }

    fun connect(timeoutMs: Int = 5000): Boolean {
        return try {
            if (!transport.connect(timeoutMs)) {
                return false
            }

            // Send A_CNXN packet
            val payload = "host::GoatsInstaller\u0000".toByteArray(StandardCharsets.UTF_8)
            sendPacket(A_CNXN, ADB_VERSION, MAX_PAYLOAD, payload)

            // Read response
            val resp = readPacket()
            if (resp.command == A_CNXN) {
                Log.i(TAG, "ADB Handshake successful via ${transport.name}: ${String(resp.payload, StandardCharsets.UTF_8)}")
                true
            } else {
                Log.w(TAG, "Unexpected ADB response: 0x${Integer.toHexString(resp.command)}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "ADB Handshake failed: ${e.message}")
            false
        }
    }

    /**
     * Executes a remote shell command and returns output.
     */
    fun executeShell(command: String): String {
        val streamId = localStreamId++
        val dest = "shell:$command\u0000".toByteArray(StandardCharsets.UTF_8)

        sendPacket(A_OPEN, streamId, 0, dest)

        var remoteStreamId = -1
        val output = ByteArrayOutputStream()

        while (true) {
            val pkt = readPacket()
            when (pkt.command) {
                A_OKAY -> {
                    remoteStreamId = pkt.arg0
                }
                A_WRTE -> {
                    output.write(pkt.payload)
                    // Ack with A_OKAY
                    sendPacket(A_OKAY, streamId, remoteStreamId, ByteArray(0))
                }
                A_CLSE -> {
                    // Stream closed
                    break
                }
            }
        }

        return output.toString("UTF-8")
    }

    /**
     * Streams file bytes from an InputStream into remote path via ADB Sync protocol.
     * Memory-efficient: never loads multi-megabyte APKs entirely into heap.
     */
    fun pushStream(
        inputStream: InputStream,
        totalBytes: Long,
        remotePath: String,
        onProgress: (Float) -> Unit
    ): Boolean {
        val streamId = localStreamId++
        val dest = "sync:\u0000".toByteArray(StandardCharsets.UTF_8)

        sendPacket(A_OPEN, streamId, 0, dest)

        var remoteStreamId = -1
        val openResp = readPacket()
        if (openResp.command != A_OKAY) {
            return false
        }
        remoteStreamId = openResp.arg0

        // Send SEND request: "SEND" + pathLen + path + ",33206" (octal mode 0666)
        val pathBytes = "$remotePath,33206".toByteArray(StandardCharsets.UTF_8)
        val sendHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        sendHeader.put("SEND".toByteArray(StandardCharsets.US_ASCII))
        sendHeader.putInt(pathBytes.size)

        val fullSendReq = ByteArray(8 + pathBytes.size)
        System.arraycopy(sendHeader.array(), 0, fullSendReq, 0, 8)
        System.arraycopy(pathBytes, 0, fullSendReq, 8, pathBytes.size)

        sendPacket(A_WRTE, streamId, remoteStreamId, fullSendReq)
        readPacket() // Read OKAY

        // Stream DATA chunks
        val chunkSize = 32 * 1024
        val chunkBuf = ByteArray(chunkSize)
        var totalRead = 0L
        var r: Int

        while (inputStream.read(chunkBuf).also { r = it } != -1) {
            val dataHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            dataHeader.put("DATA".toByteArray(StandardCharsets.US_ASCII))
            dataHeader.putInt(r)

            val chunk = ByteArray(8 + r)
            System.arraycopy(dataHeader.array(), 0, chunk, 0, 8)
            System.arraycopy(chunkBuf, 0, chunk, 8, r)

            sendPacket(A_WRTE, streamId, remoteStreamId, chunk)
            readPacket() // Read OKAY

            totalRead += r
            if (totalBytes > 0) {
                onProgress(totalRead.toFloat() / totalBytes.toFloat())
            }
        }

        // Send DONE request: "DONE" + timestamp
        val doneHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        doneHeader.put("DONE".toByteArray(StandardCharsets.US_ASCII))
        doneHeader.putInt((System.currentTimeMillis() / 1000).toInt())

        sendPacket(A_WRTE, streamId, remoteStreamId, doneHeader.array())
        readPacket() // Read OKAY

        // Close sync stream
        sendPacket(A_CLSE, streamId, remoteStreamId, ByteArray(0))
        return true
    }

    /**
     * Streams file bytes into remote path via ADB Sync protocol.
     */
    fun pushFile(
        fileBytes: ByteArray,
        remotePath: String,
        onProgress: (Float) -> Unit
    ): Boolean {
        val inStream = ByteArrayInputStream(fileBytes)
        return pushStream(inStream, fileBytes.size.toLong(), remotePath, onProgress)
    }

    private fun sendPacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val buf = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)

        buf.putInt(cmd)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)

        var crc = 0
        for (b in payload) {
            crc += (b.toInt() and 0xFF)
        }
        buf.putInt(crc)
        buf.putInt(cmd xor -0x1) // Magic is ~cmd

        if (payload.isNotEmpty()) {
            buf.put(payload)
        }

        val array = buf.array()
        transport.write(array, 0, array.size)
    }

    private fun readPacket(): AdbPacket {
        val header = ByteArray(24)
        transport.readExact(header, 0, 24)

        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buf.getInt()
        val arg0 = buf.getInt()
        val arg1 = buf.getInt()
        val len = buf.getInt()
        val crc = buf.getInt()
        val magic = buf.getInt()

        val payload = ByteArray(len)
        if (len > 0) {
            transport.readExact(payload, 0, len)
        }

        return AdbPacket(cmd, arg0, arg1, len, crc, magic, payload)
    }

    override fun close() {
        try {
            transport.close()
        } catch (ignored: Exception) {}
    }

    data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val length: Int,
        val crc: Int,
        val magic: Int,
        val payload: ByteArray
    )
}
