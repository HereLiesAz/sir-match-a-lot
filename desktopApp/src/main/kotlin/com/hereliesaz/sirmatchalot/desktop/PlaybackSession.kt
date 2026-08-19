package com.hereliesaz.sirmatchalot.desktop

import com.hereliesaz.sirmatchalot.audio.AudioEngine
import com.hereliesaz.sirmatchalot.audio.Clip
import com.hereliesaz.sirmatchalot.audio.Deck
import com.hereliesaz.sirmatchalot.audio.Sampler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The desktop half of local mixing: two decks, a crossfader, and an eight-pad
 * sampler, all driven from local files and played through [DesktopAudioOutput].
 *
 * Everything here is UI wiring on top of [AudioEngine]'s already-portable
 * render graph — `deckA`/`deckB`, `mixer.crossfade`, and `sampler` all existed
 * before this session touched anything; Phase 3 only needed one deck to prove
 * the graph makes sound at all.
 */
class PlaybackSession {

    private val output = DesktopAudioOutput()
    val engine = AudioEngine(output)

    val deckA = DeckControl(engine, engine.deckA)
    val deckB = DeckControl(engine, engine.deckB)

    private val _crossfade = MutableStateFlow(engine.mixer.crossfade)
    val crossfade: StateFlow<Float> = _crossfade

    val samplerPads: List<SamplerPadControl> =
        engine.sampler.pads.indices.map { SamplerPadControl(engine, engine.sampler, it) }

    /**
     * Set once, from [DesktopAudioOutput.onError] — never cleared by a deck's
     * [DeckControl.load], because loading a different file has no bearing on
     * whether there is anywhere to play it.
     */
    private val _outputErrorMessage = MutableStateFlow<String?>(null)
    val outputErrorMessage: StateFlow<String?> = _outputErrorMessage

    init {
        output.onError = { error ->
            _outputErrorMessage.value = "No audio output available — ${error.message}"
            deckA.markStopped()
            deckB.markStopped()
        }
        engine.start()
    }

    fun setCrossfade(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        engine.mixer.crossfade = clamped
        _crossfade.value = clamped
    }

    fun release() {
        engine.release()
    }
}

/** One deck's load/play/stop state, backed by a live [Deck] in the shared engine. */
class DeckControl(private val engine: AudioEngine, private val deck: Deck) {

    private val _loadedFileName = MutableStateFlow<String?>(null)
    val loadedFileName: StateFlow<String?> = _loadedFileName

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _loadErrorMessage = MutableStateFlow<String?>(null)
    val loadErrorMessage: StateFlow<String?> = _loadErrorMessage

    /** Decodes [path] and puts it on this deck, stopped, ready to play. */
    fun load(path: String) {
        val file = File(path)
        val buffer = runCatching { DesktopAudioDecoder.decode(file) }.getOrNull()
        if (buffer == null) {
            _loadErrorMessage.value = "Could not decode $path — only WAV/AIFF/AU are supported so far"
            return
        }
        deck.playing = false
        deck.clips = listOf(Clip(id = file.path, buffer = buffer, loop = true))
        deck.playhead = 0.0
        _loadedFileName.value = file.name
        _isPlaying.value = false
        _loadErrorMessage.value = null
    }

    fun play() {
        if (deck.clips.isEmpty()) return
        deck.playing = true
        _isPlaying.value = true
        // Brings the output back immediately if it had stood down idle,
        // rather than waiting for the next poll — see AudioOutput.wake.
        engine.wake()
    }

    fun stop() {
        deck.playing = false
        _isPlaying.value = false
    }

    /** Called when the output itself has failed — the deck can no longer be sounding. */
    internal fun markStopped() {
        deck.playing = false
        _isPlaying.value = false
    }
}

/** One sampler pad's load/trigger/stop state, backed by a live pad in [Sampler]. */
class SamplerPadControl(private val engine: AudioEngine, private val sampler: Sampler, val index: Int) {

    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label

    private val _loadErrorMessage = MutableStateFlow<String?>(null)
    val loadErrorMessage: StateFlow<String?> = _loadErrorMessage

    /** Decodes [path] and drops it onto this pad, ready to trigger. */
    fun load(path: String) {
        val file = File(path)
        val buffer = runCatching { DesktopAudioDecoder.decode(file) }.getOrNull()
        if (buffer == null) {
            _loadErrorMessage.value = "Could not decode $path — only WAV/AIFF/AU are supported so far"
            return
        }
        sampler.pads[index].load(buffer, file.name)
        _label.value = file.name
        _loadErrorMessage.value = null
    }

    fun trigger() {
        if (sampler.pads[index].isEmpty) return
        sampler.trigger(index)
        engine.wake()
    }

    fun stop() {
        sampler.stop(index)
    }
}
