package com.apexlink.projection.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import com.apexlink.projection.NativeBridge
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class WirelessSocketManager(private val context: Context) {

    companion object {
        private const val TAG = "GD_WirelessMgr"

        // Official Android Auto Wireless Handshake RFCOMM UUID
        private val AAP_RFCOMM_UUID: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        private const val PROJECTION_PORT = 5277
    }

    private val isRunning = AtomicBoolean(false)
    private var rfcommServer: BluetoothServerSocket? = null
    private var projectionServer: ServerSocket? = null
    private var activeClientSocket: Socket? = null

    // Wi-Fi Direct Manager
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null

    init {
        p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        p2pChannel = p2pManager?.initialize(context, Looper.getMainLooper(), null)
    }

    @SuppressLint("MissingPermission")
    fun startWirelessListener(btDevice: BluetoothDevice?) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Wireless listener already running")
            return
        }

        Thread({
            setupWifiDirectGroup { ssid, passphrase, ip ->
                runRfcommHandshake(btDevice, ssid, passphrase, ip)
            }
        }, "GD_WirelessInit").start()
    }

    @SuppressLint("MissingPermission")
    private fun setupWifiDirectGroup(onReady: (ssid: String, psk: String, ip: String) -> Unit) {
        val manager = p2pManager
        val channel = p2pChannel

        if (manager != null && channel != null) {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    createGroup(manager, channel, onReady)
                }

                override fun onFailure(reason: Int) {
                    createGroup(manager, channel, onReady)
                }
            })
        } else {
            // Fallback for custom automotive MCU hotspot IP
            onReady("GoatsDrive-5G", "projectionPass123", "192.168.43.1")
        }
    }

    @SuppressLint("MissingPermission")
    private fun createGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        onReady: (ssid: String, psk: String, ip: String) -> Unit
    ) {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.requestGroupInfo(channel) { group: WifiP2pGroup? ->
                    val ssid = group?.networkName ?: "DIRECT-GoatsDrive"
                    val psk = group?.passphrase ?: "12345678"
                    val ip = "192.168.49.1" // Standard Android Wi-Fi Direct Group Owner IP
                    Log.i(TAG, "Wi-Fi Direct Group established: SSID=$ssid, PSK=$psk, IP=$ip")
                    onReady(ssid, psk, ip)
                }
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to create Wi-Fi Direct group ($reason). Using local fallback.")
                onReady("GoatsDrive-Auto", "12345678", "192.168.43.1")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun runRfcommHandshake(
        targetDevice: BluetoothDevice?,
        ssid: String,
        passphrase: String,
        serverIp: String
    ) {
        val btAdapter = BluetoothAdapter.getDefaultAdapter() ?: return

        try {
            Log.i(TAG, "Starting Bluetooth RFCOMM service socket listener...")
            rfcommServer = btAdapter.listenUsingInsecureRfcommWithServiceRecord("GoatsDrive", AAP_RFCOMM_UUID)

            // Start High-Speed TCP Server Socket concurrently
            startTcpProjectionServer()

            val socket: BluetoothSocket = rfcommServer?.accept(30000) ?: run {
                Log.e(TAG, "RFCOMM accept timeout or cancelled.")
                return
            }

            Log.i(TAG, "RFCOMM connection accepted from: ${socket.remoteDevice.address}")
            handleRfcommExchange(socket, ssid, passphrase, serverIp)

        } catch (e: Exception) {
            Log.e(TAG, "RFCOMM Handshake error", e)
        } finally {
            try {
                rfcommServer?.close()
                rfcommServer = null
            } catch (ignored: Exception) {}
        }
    }

    private fun handleRfcommExchange(
        socket: BluetoothSocket,
        ssid: String,
        passphrase: String,
        serverIp: String
    ) {
        try {
            val input: InputStream = socket.inputStream
            val output: OutputStream = socket.outputStream

            // Read client Hello / Version
            val rxBuf = ByteArray(256)
            val readCount = input.read(rxBuf)
            Log.i(TAG, "Received client RFCOMM hello ($readCount bytes)")

            // Format JSON credential payload for wireless association
            val handshakeJson = """
                {
                    "ssid": "$ssid",
                    "key": "$passphrase",
                    "bssid": "",
                    "ip": "$serverIp",
                    "port": $PROJECTION_PORT
                }
            """.trimIndent()

            val payload = handshakeJson.toByteArray(StandardCharsets.UTF_8)
            output.write(payload)
            output.flush()

            Log.i(TAG, "Dispatched Wi-Fi Direct credentials over RFCOMM to smartphone.")

            // Keep socket open briefly for phone handshake acknowledgment
            Thread.sleep(1000)
            socket.close()

        } catch (e: Exception) {
            Log.e(TAG, "Error in RFCOMM credential exchange", e)
        }
    }

    private fun startTcpProjectionServer() {
        Thread({
            try {
                projectionServer = ServerSocket().apply {
                    reuseAddress = true
                    receiveBufferSize = 2 * 1024 * 1024
                    bind(InetSocketAddress(PROJECTION_PORT))
                }

                Log.i(TAG, "TCP projection socket server listening on port $PROJECTION_PORT...")
                val client = projectionServer?.accept() ?: return@Thread
                activeClientSocket = client

                // Disable Nagle's algorithm & optimize buffers
                client.tcpNoDelay = true
                client.receiveBufferSize = 2 * 1024 * 1024
                client.sendBufferSize = 128 * 1024

                val nativeFd = extractSocketFd(client)
                Log.i(TAG, "Smartphone connected to TCP projection socket! Native FD: $nativeFd")

                if (nativeFd >= 0) {
                    NativeBridge.nativeStartSocketTransport(nativeFd)
                } else {
                    Log.e(TAG, "Unable to extract native socket FD from Java Socket")
                }

            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Projection TCP server encountered an error", e)
                }
            }
        }, "GD_TcpAcceptor").start()
    }

    private fun extractSocketFd(socket: Socket): Int {
        return try {
            val implField = Socket::class.java.getDeclaredField("impl")
            implField.isAccessible = true
            val impl = implField.get(socket)

            val fdField = impl.javaClass.getDeclaredField("fd")
            fdField.isAccessible = true
            val fileDescriptor = fdField.get(impl) as FileDescriptor

            val descField = FileDescriptor::class.java.getDeclaredField("descriptor")
            descField.isAccessible = true
            descField.getInt(fileDescriptor)
        } catch (e: Exception) {
            Log.e(TAG, "Reflection error accessing socket native FD", e)
            -1
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            rfcommServer?.close()
            rfcommServer = null
        } catch (ignored: Exception) {}

        try {
            activeClientSocket?.close()
            activeClientSocket = null
        } catch (ignored: Exception) {}

        try {
            projectionServer?.close()
            projectionServer = null
        } catch (ignored: Exception) {}

        p2pManager?.let { mgr ->
            p2pChannel?.let { ch ->
                mgr.removeGroup(ch, null)
            }
        }
    }
}
