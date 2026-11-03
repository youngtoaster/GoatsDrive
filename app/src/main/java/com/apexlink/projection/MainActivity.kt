package com.apexlink.projection

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.apexlink.projection.service.ProjectionForegroundService

class MainActivity : Activity() {

    private lateinit var surfaceView: ProjectionSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Low-latency automotive display flags
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        applyImmersiveSticky()

        // Pure single SurfaceView host: ZERO layout XML overhead
        surfaceView = ProjectionSurfaceView(this)
        setContentView(surfaceView)

        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveSticky()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveSticky()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        // Forward intent to persistent foreground service
        val serviceIntent = Intent(this, ProjectionForegroundService::class.java).apply {
            action = ProjectionForegroundService.ACTION_INITIALIZE_HARDWARE
            putExtras(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveSticky() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    /**
     * Intercepts steering wheel controls (SWC) and automotive keys with zero object allocation.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ENDCALL,
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                NativeBridge.nativeSendKey(keyCode, KeyEvent.ACTION_DOWN)
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                // Prevent accidental app closure while driving
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ENDCALL -> {
                NativeBridge.nativeSendKey(keyCode, KeyEvent.ACTION_UP)
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }
}
