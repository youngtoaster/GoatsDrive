package com.apexlink.installer

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Lightweight, zero-dependency ADB client communicating over TCP port 5555.
 * Implements ADB packet framing: [Command(4), Arg0(4), Arg1(4), DataLen(4), DataCrc(4), Magic(4)]
 */
class AdbClient(private val host: String, private val port: Int = 5555) : AutoCloseable {

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

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
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
                    errorMessage = "Connection refused or timed out on port $port. Check if Wireless ADB is enabled in Factory Settings."
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
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            s.soTimeout = 10000

            socket = s
            inputStream = s.getInputStream()
            outputStream = s.getOutputStream()

            // Send A_CNXN packet
            val payload = "host::GoatsInstaller\u0000".toByteArray(StandardCharsets.UTF_8)
            sendPacket(A_CNXN, ADB_VERSION, MAX_PAYLOAD, payload)

            // Read response
            val resp = readPacket()
            if (resp.command == A_CNXN) {
                Log.i(TAG, "ADB Handshake successful: ${String(resp.payload, StandardCharsets.UTF_8)}")
                true
            } else {
                Log.w(TAG, "Unexpected ADB response: 0x${Integer.toHexString(resp.command)}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "ADB Connection failed: ${e.message}")
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
     * Streams file bytes into remote path via ADB Sync protocol.
     */
    fun pushFile(
        fileBytes: ByteArray,
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

        val out = outputStream ?: return false

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
        var offset = 0
        val total = fileBytes.size

        while (offset < total) {
            val len = Math.min(chunkSize, total - offset)
            val dataHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            dataHeader.put("DATA".toByteArray(StandardCharsets.US_ASCII))
            dataHeader.putInt(len)

            val chunk = ByteArray(8 + len)
            System.arraycopy(dataHeader.array(), 0, chunk, 0, 8)
            System.arraycopy(fileBytes, offset, chunk, 8, len)

            sendPacket(A_WRTE, streamId, remoteStreamId, chunk)
            readPacket() // Read OKAY

            offset += len
            onProgress(offset.toFloat() / total.toFloat())
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

    private fun sendPacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val out = outputStream ?: return
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

        out.write(buf.array())
        out.flush()
    }

    private fun readPacket(): AdbPacket {
        val inStream = inputStream ?: throw IllegalStateException("Not connected")
        val header = ByteArray(24)
        var read = 0
        while (read < 24) {
            val r = inStream.read(header, read, 24 - read)
            if (r < 0) throw IllegalStateException("EOF reading ADB header")
            read += r
        }

        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buf.getInt()
        val arg0 = buf.getInt()
        val arg1 = buf.getInt()
        val len = buf.getInt()
        val crc = buf.getInt()
        val magic = buf.getInt()

        val payload = ByteArray(len)
        var pRead = 0
        while (pRead < len) {
            val r = inStream.read(payload, pRead, len - pRead)
            if (r < 0) throw IllegalStateException("EOF reading ADB payload")
            pRead += r
        }

        return AdbPacket(cmd, arg0, arg1, len, crc, magic, payload)
    }

    override fun close() {
        try {
            socket?.close()
        } catch (ignored: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
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
