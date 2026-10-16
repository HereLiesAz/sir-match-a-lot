package com.hereliesaz.sirmatchalot.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Plays well with the rest of the phone: audio focus and headphone unplugging.
 *
 * The engine writes straight to `AudioTrack`, which does none of this on its
 * own. Without it the app talked over a phone call, kept playing under another
 * music app the user had just started, and — the one that embarrasses people —
 * carried on out of the phone's speaker the moment headphones were pulled out.
 *
 * [acquire] when playback starts and [release] when it stops. [onPause] is
 * called, on the main thread, whenever playback should stop: focus lost to
 * another app or a call, or `ACTION_AUDIO_BECOMING_NOISY`. Focus is not
 * re-taken automatically when it comes back; a DJ app resuming by itself after
 * a call is a surprise, not a feature.
 */
class PlaybackFocus(
    context: Context,
    private val onPause: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var held = false
    private var request: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> onPause()
            // Ducking is left to the system (API 26+ ducks automatically).
            else -> Unit
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onPause()
        }
    }

    /** Takes focus and starts listening for headphones being unplugged. Idempotent. */
    fun acquire() {
        if (held) return
        held = true
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val built = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                request = built
                manager?.requestAudioFocus(built)
            } else {
                @Suppress("DEPRECATION")
                manager?.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /** Gives focus back and stops listening. Idempotent. */
    fun release() {
        if (!held) return
        held = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                request?.let { manager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                manager?.abandonAudioFocus(focusListener)
            }
        }
        request = null
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
    }
}
