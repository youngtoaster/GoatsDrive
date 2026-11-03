package com.apexlink.projection

import android.view.Surface

/**
 * Direct JNI Bridge to high-performance C++17 projection engine.
 * All method invocations operate on native pre-allocated buffers or critical arrays.
 */
object NativeBridge {
    init {
        System.loadLibrary("goatsdrive_engine")
    }

    external fun nativeInit(surface: Surface, width: Int, height: Int): Boolean
    external fun nativeStartSocketTransport(socketFd: Int): Boolean
    external fun nativeStartUsbTransport(usbFd: Int): Boolean
    external fun nativeStop()
    external fun nativeSendTouch(packet: ByteArray, length: Int): Boolean
    external fun nativeSendKey(keyCode: Int, action: Int): Boolean
    external fun nativeSetAudioDuck(ducked: Boolean)
    external fun nativeGetMetrics(outMetrics: LongArray)
}
