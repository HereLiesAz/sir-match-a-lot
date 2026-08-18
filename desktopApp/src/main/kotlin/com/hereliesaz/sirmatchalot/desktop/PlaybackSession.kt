package com.hereliesaz.sirmatchalot.desktop

import com.hereliesaz.sirmatchalot.audio.AudioEngine
import com.hereliesaz.sirmatchalot.audio.Clip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The desktop half of local mixing: one deck, loaded from a local WAV file
 * and played through [DesktopAudioOutput].
 *
 * This is deliberately smaller than the Android app's two-deck, sampler-and-
 * crossfader instrument — it exists to prove the render graph genuinely
 * plays sound on a desktop JVM, which nothing before Phase 3 did. Growing it
 * into the full instrument (deck B, the crossfader, the platter) is UI work
 * on an already-portable graph, not new audio plumbing.
 */
class PlaybackSession {

    private val output = DesktopAudioOutput()
    val engine = AudioEngine(output)

    private val _loadedFileName = MutableStateFlow<String?>(null)
    val loadedFileName: StateFlow<String?> = _loadedFileName

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    /**
     * Set once, from [DesktopAudioOutput.onError] — never cleared by [load],
     * because loading a different file has no bearing on whether there is
     * anywhere to play it. Distinct from [loadErrorMessage] for the same
     * reason: the two are unrelated failures, and one resolving must not
     * silently hide the other.
     */
    private val _outputErrorMessage = MutableStateFlow<String?>(null)
    val outputErrorMessage: StateFlow<String?> = _outputErrorMessage

    private val _loadErrorMessage = MutableStateFlow<String?>(null)
    val loadErrorMessage: StateFlow<String?> = _loadErrorMessage

    init {
        output.onError = { error ->
            _outputErrorMessage.value = "No audio output available — ${error.message}"
            _isPlaying.value = false
        }
        engine.start()
    }

    /** Decodes [path] and puts it on the deck, stopped, ready to play. */
    fun load(path: String) {
        val file = File(path)
        val buffer = runCatching { DesktopAudioDecoder.decode(file) }.getOrNull()
        if (buffer == null) {
            _loadErrorMessage.value = "Could not decode $path — only WAV/AIFF/AU are supported so far"
            return
        }
        engine.deckA.playing = false
        engine.deckA.clips = listOf(Clip(id = file.path, buffer = buffer, loop = true))
        engine.deckA.playhead = 0.0
        _loadedFileName.value = file.name
        _isPlaying.value = false
        _loadErrorMessage.value = null
    }

    fun play() {
        if (engine.deckA.clips.isEmpty()) return
        engine.deckA.playing = true
        _isPlaying.value = true
        // Brings the output back immediately if it had stood down idle,
        // rather than waiting for the next poll — see AudioOutput.wake.
        engine.wake()
    }

    fun stop() {
        engine.deckA.playing = false
        _isPlaying.value = false
    }

    fun release() {
        engine.release()
    }
}
