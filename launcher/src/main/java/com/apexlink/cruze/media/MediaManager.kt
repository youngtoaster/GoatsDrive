package com.apexlink.cruze.media

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Universal media player controller dispatching global media key events and
 * tracking playback state across Bluetooth Audio, Spotify, FM Radio, and USB player.
 */
object MediaManager {

    data class TrackInfo(
        var title: String = "Cruze Audio",
        var artist: String = "Bluetooth Connected",
        var album: String = "Chevrolet MyLink",
        var isPlaying: Boolean = true,
        var durationSec: Int = 214,
        var progressSec: Int = 42
    )

    val currentTrack = TrackInfo()
    private val listeners = mutableListOf<(TrackInfo) -> Unit>()

    fun registerListener(l: (TrackInfo) -> Unit) {
        listeners.add(l)
        l(currentTrack)
    }

    fun unregisterListener(l: (TrackInfo) -> Unit) {
        listeners.remove(l)
    }

    fun togglePlayPause(context: Context) {
        currentTrack.isPlaying = !currentTrack.isPlaying
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        notifyUpdate()
    }

    fun skipNext(context: Context) {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        currentTrack.progressSec = 0
        notifyUpdate()
    }

    fun skipPrevious(context: Context) {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        currentTrack.progressSec = 0
        notifyUpdate()
    }

    private fun dispatchMediaKey(context: Context, keyCode: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val downTime = SystemClock.uptimeMillis()
            val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun notifyUpdate() {
        listeners.forEach { it(currentTrack) }
    }
}
