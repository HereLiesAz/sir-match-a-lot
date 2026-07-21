package com.hereliesaz.sirmatchalot.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.hereliesaz.sirmatchalot.data.Track
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
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
    var youtubePlayer: YouTubePlayer? = null

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

        if (track.localPath != null) {
            try {
                val mediaItem = MediaItem.fromUri(Uri.parse(track.localPath))
                exoPlayer?.setMediaItem(mediaItem)
                exoPlayer?.prepare()
                setVolume(masterVolume)
                setPlaybackRate(1f + pitch / 100f)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            track.youtubeId?.let { videoId ->
                youtubePlayer?.cueVideo(videoId, 0f)
                _duration.value = 180f
            }
        }
    }

    fun play() {
        if (loadedTrack == null) return
        if (loadedTrack?.localPath != null) {
            exoPlayer?.play()
        } else {
            youtubePlayer?.play()
            _isPlaying.value = true
            startProgressTracking()
        }
    }

    fun pause() {
        if (loadedTrack?.localPath != null) {
            exoPlayer?.pause()
        } else {
            youtubePlayer?.pause()
            _isPlaying.value = false
            stopProgressTracking()
        }
    }

    fun seekTo(seconds: Float) {
        if (loadedTrack?.localPath != null) {
            exoPlayer?.seekTo((seconds * 1000f).toLong())
            _currentTime.value = seconds
        } else {
            youtubePlayer?.seekTo(seconds)
            _currentTime.value = seconds
        }
    }

    fun setPlaybackRate(rate: Float) {
        pitch = (rate - 1f) * 100f
        if (loadedTrack?.localPath != null) {
            exoPlayer?.playbackParameters = PlaybackParameters(rate)
        } else {
            val ytRate = when {
                rate <= 0.35f -> com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlaybackRate.RATE_0_25
                rate <= 0.75f -> com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlaybackRate.RATE_0_5
                rate <= 1.25f -> com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlaybackRate.RATE_1
                rate <= 1.75f -> com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlaybackRate.RATE_1_5
                else -> com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlaybackRate.RATE_2
            }
            youtubePlayer?.setPlaybackRate(ytRate)
        }
    }

    fun setVolume(vol: Float) {
        masterVolume = vol
        val actualVol = if (isMuted) 0f else vol
        exoPlayer?.volume = actualVol
        if (!isMuted) {
            youtubePlayer?.setVolume((actualVol * 100f).toInt())
        } else {
            youtubePlayer?.setVolume(0)
        }
    }

    fun setMute(muted: Boolean) {
        isMuted = muted
        setVolume(masterVolume)
    }

    fun bindYouTubePlayer(player: YouTubePlayer) {
        youtubePlayer = player
        player.addListener(object : AbstractYouTubePlayerListener() {
            override fun onVideoDuration(youTubePlayer: YouTubePlayer, durationSecs: Float) {
                if (loadedTrack?.localPath == null) {
                    _duration.value = durationSecs
                }
            }
            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                if (loadedTrack?.localPath == null) {
                    _currentTime.value = second
                }
            }
        })
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(200)
                if (loadedTrack?.localPath != null) {
                    _currentTime.value = (exoPlayer?.currentPosition ?: 0L) / 1000f
                }
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
        youtubePlayer = null
    }
}
