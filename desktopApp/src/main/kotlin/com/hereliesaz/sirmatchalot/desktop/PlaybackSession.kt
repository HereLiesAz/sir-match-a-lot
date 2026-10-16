package com.hereliesaz.sirmatchalot.desktop

import com.hereliesaz.sirmatchalot.audio.AudioEngine
import com.hereliesaz.sirmatchalot.audio.Clip
import com.hereliesaz.sirmatchalot.audio.Deck
import com.hereliesaz.sirmatchalot.audio.PcmBuffer
import com.hereliesaz.sirmatchalot.audio.Sampler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The desktop half of local mixing: two decks, a crossfader, and an eight-pad
 * sampler, all driven from local files and played through [DesktopAudioOutput].
 *
 * Everything here is UI-facing state on top of [AudioEngine]'s portable
 * render graph (`deckA`/`deckB`, `mixer.crossfade`, `sampler`). Loading a
 * file decodes it on [Dispatchers.IO] — a whole track is read into memory,
 * which must not freeze the window — and the result is applied when the
 * decode finishes.
 */
class PlaybackSession {

    /** Decode jobs; cancelled by [release]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val output = DesktopAudioOutput()
    val engine = AudioEngine(output)

    val deckA = DeckControl(engine, engine.deckA, scope)
    val deckB = DeckControl(engine, engine.deckB, scope)

    private val _crossfade = MutableStateFlow(engine.mixer.crossfade)
    val crossfade: StateFlow<Float> = _crossfade

    val samplerPads: List<SamplerPadControl> =
        engine.sampler.pads.indices.map { SamplerPadControl(engine, engine.sampler, it, scope) }

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
        scope.cancel()
        engine.release()
    }
}

/** One deck's load/play/stop state, backed by a live [Deck] in the shared engine. */
class DeckControl(
    private val engine: AudioEngine,
    private val deck: Deck,
    private val scope: CoroutineScope,
) {

    private val _loadedFileName = MutableStateFlow<String?>(null)
    val loadedFileName: StateFlow<String?> = _loadedFileName

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _loadErrorMessage = MutableStateFlow<String?>(null)
    val loadErrorMessage: StateFlow<String?> = _loadErrorMessage

    /** Bumped per [load]; only the newest load's result is applied. */
    private val loadGeneration = AtomicInteger()

    /**
     * Decodes [path] in the background and then puts it on this deck,
     * stopped, ready to play. Returns the decode job, so a caller (a test)
     * can wait for it; the UI just observes the state flows.
     */
    fun load(path: String): Job {
        val generation = loadGeneration.incrementAndGet()
        return scope.launch {
            val result = decodeForLoad(path)
            if (generation != loadGeneration.get()) return@launch
            result.onSuccess { apply(File(path), it) }
                .onFailure { _loadErrorMessage.value = it.message }
        }
    }

    private fun apply(file: File, buffer: PcmBuffer) {
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
class SamplerPadControl(
    private val engine: AudioEngine,
    private val sampler: Sampler,
    val index: Int,
    private val scope: CoroutineScope,
) {

    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label

    private val _loadErrorMessage = MutableStateFlow<String?>(null)
    val loadErrorMessage: StateFlow<String?> = _loadErrorMessage

    private val loadGeneration = AtomicInteger()

    /** Decodes [path] in the background, then drops it onto this pad, ready to trigger. */
    fun load(path: String): Job {
        val generation = loadGeneration.incrementAndGet()
        return scope.launch {
            val result = decodeForLoad(path)
            if (generation != loadGeneration.get()) return@launch
            result.onSuccess { buffer ->
                val file = File(path)
                sampler.pads[index].load(buffer, file.name)
                _label.value = file.name
                _loadErrorMessage.value = null
            }.onFailure { _loadErrorMessage.value = it.message }
        }
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

/**
 * Decodes [path] for a deck or pad, turning every failure into a message a
 * user can act on. Out-of-memory is reported on its own: the decoder holds
 * the whole file as 16-bit PCM, so a long track can exceed the heap, and
 * "could not decode" would wrongly suggest the format is the problem.
 */
internal fun decodeForLoad(path: String): Result<PcmBuffer> {
    val buffer = try {
        DesktopAudioDecoder.decode(File(path))
    } catch (e: OutOfMemoryError) {
        return Result.failure(
            IllegalStateException("$path is too large to load into memory — try a shorter file"),
        )
    } catch (e: Exception) {
        null
    }
    return buffer?.let { Result.success(it) }
        ?: Result.failure(
            IllegalArgumentException("Could not decode $path — only WAV/AIFF/AU are supported so far"),
        )
}
