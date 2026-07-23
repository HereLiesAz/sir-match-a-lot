package com.hereliesaz.sirmatchalot.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.hereliesaz.sirmatchalot.data.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

@OptIn(UnstableApi::class)
class DeckController(
    private val context: Context,
    val deckName: String
) {
    var loadedTrack: Track? = null
        private set

    private var exoPlayer: ExoPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentTime = MutableStateFlow(0f)
    val currentTime: StateFlow<Float> = _currentTime

    private val _duration = MutableStateFlow(180f)
    val duration: StateFlow<Float> = _duration

    var pitch: Float = 0f
        private set
    
    var isMuted: Boolean = false
        private set

    var masterVolume: Float = 0.5f
        private set

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    if (loadedTrack?.localPath != null) {
                        _isPlaying.value = playing
                        if (playing) startProgressTracking() else stopProgressTracking()
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && loadedTrack?.localPath != null) {
                        _duration.value = duration / 1000f
                    }
                }
            })
        }
    }

    fun loadTrack(track: Track) {
        pause()
        loadedTrack = track
        _currentTime.value = 0f

        try {
            val uri = if (track.localPath != null) {
                Uri.parse(track.localPath)
            } else if (track.youtubeId != null) {
                Uri.parse("http://10.0.2.2:8080/download?v=${track.youtubeId}")
            } else {
                return
            }
            
            val mediaItem = MediaItem.fromUri(uri)
            val mediaSourceFactory = DefaultMediaSourceFactory(context)
            val baseSource = mediaSourceFactory.createMediaSource(mediaItem)
            
            // Apply trimming if bounds are provided
            val sourceToPlay = if (track.trimEndMs > 0 && track.trimEndMs > track.trimStartMs) {
                ClippingMediaSource(
                    baseSource,
                    track.trimStartMs * 1000L, // Microseconds
                    track.trimEndMs * 1000L
                )
            } else {
                baseSource
            }
            
            exoPlayer?.setMediaSource(sourceToPlay)
            exoPlayer?.prepare()
            setVolume(masterVolume)
            setPlaybackRate(1f + pitch / 100f)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun play() {
        if (loadedTrack == null) return
        exoPlayer?.play()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun seekTo(seconds: Float) {
        exoPlayer?.seekTo((seconds * 1000f).toLong())
        _currentTime.value = seconds
    }

    fun setPlaybackRate(rate: Float) {
        pitch = (rate - 1f) * 100f
        exoPlayer?.playbackParameters = PlaybackParameters(rate)
    }

    fun setPitchOnly(pitchRatio: Float) {
        pitch = (pitchRatio - 1f) * 100f
        if (loadedTrack?.localPath != null) {
            // Speed remains locked at 1.0f (tempo/speed unaffected)
            exoPlayer?.playbackParameters = PlaybackParameters(1.0f, pitchRatio)
        }
    }

    fun setVolume(vol: Float) {
        masterVolume = vol
        val actualVol = if (isMuted) 0f else vol
        exoPlayer?.volume = actualVol
    }

    fun setMute(muted: Boolean) {
        isMuted = muted
        setVolume(masterVolume)
    }


    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(200)
                _currentTime.value = (exoPlayer?.currentPosition ?: 0L) / 1000f
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        progressJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }
}
