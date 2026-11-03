package com.apexlink.projection

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * High-performance, zero-allocation SurfaceView for hardware-accelerated video rendering
 * and sub-5ms touch serialization.
 */
class ProjectionSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "GD_SurfaceView"
    }

    // Pre-allocated 16-byte touch buffer to guarantee ZERO-GC in high-frequency touch loops
    private val touchPacket = ByteArray(16)
    private var isSurfaceReady = false

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "Surface created")
        isSurfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "Surface geometry configured: ${width}x${height} (format: $format)")
        NativeBridge.nativeInit(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "Surface destroyed")
        isSurfaceReady = false
        NativeBridge.nativeStop()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val pointerCount = event.pointerCount

        val viewWidth = width
        val viewHeight = height
        if (viewWidth <= 0 || viewHeight <= 0) return true

        // Normalize coordinates to 16-bit unsigned integer (0..65535) for phone resolution independence
        val rawX = event.getX(pointerIndex)
        val rawY = event.getY(pointerIndex)
        val normX = ((rawX / viewWidth) * 65535f).toInt().coerceIn(0, 65535)
        val normY = ((rawY / viewHeight) * 65535f).toInt().coerceIn(0, 65535)
        val normPressure = (event.getPressure(pointerIndex) * 65535f).toInt().coerceIn(0, 65535)

        // Fixed 16-byte Touch Packet Serialization:
        // [0] Magic: 'T' (0x54)
        // [1] Action: ACTION_DOWN (0), ACTION_UP (1), ACTION_MOVE (2), etc.
        // [2] Pointer ID
        // [3] Pointer Count
        // [4..5] Normalized X (Big Endian)
        // [6..7] Normalized Y (Big Endian)
        // [8..9] Pressure (Big Endian)
        // [10..15] Reserved padding
        touchPacket[0] = 0x54
        touchPacket[1] = (action and 0xFF).toByte()
        touchPacket[2] = (pointerId and 0xFF).toByte()
        touchPacket[3] = (pointerCount and 0xFF).toByte()

        touchPacket[4] = ((normX ushr 8) and 0xFF).toByte()
        touchPacket[5] = (normX and 0xFF).toByte()

        touchPacket[6] = ((normY ushr 8) and 0xFF).toByte()
        touchPacket[7] = (normY and 0xFF).toByte()

        touchPacket[8] = ((normPressure ushr 8) and 0xFF).toByte()
        touchPacket[9] = (normPressure and 0xFF).toByte()

        // Dispatch directly into native transport outbound ring buffer
        NativeBridge.nativeSendTouch(touchPacket, 16)

        return true
    }
}
