package com.apexlink.projection.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.apexlink.projection.NativeBridge

/**
 * High-performance Automotive Audio Focus Manager.
 * Orchestrates seamless audio ducking between projection media and factory head unit navigation/FM radio.
 */
class AudioFocusController(context: Context) : AudioManager.OnAudioFocusChangeListener {

    companion object {
        private const val TAG = "GD_AudioFocus"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    fun requestMediaFocus(): Boolean {
        if (hasFocus) return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()

            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        Log.i(TAG, "Audio focus requested: granted=$hasFocus")
        return hasFocus
    }

    fun abandonFocus() {
        if (!hasFocus) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }

        hasFocus = false
        Log.i(TAG, "Audio focus abandoned")
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "AUDIOFOCUS_GAIN: Restoring full volume")
                NativeBridge.nativeSetAudioDuck(false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: Ducking projection audio for automotive prompt")
                NativeBridge.nativeSetAudioDuck(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "AUDIOFOCUS_LOSS_TRANSIENT: Muting projection audio")
                NativeBridge.nativeSetAudioDuck(true)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "AUDIOFOCUS_LOSS: External app took persistent audio focus")
                hasFocus = false
                NativeBridge.nativeSetAudioDuck(true)
            }
        }
    }
}
