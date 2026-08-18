package com.hereliesaz.sirmatchalot.audio

/**
 * The boundary between the mixer and the platform's audio sink.
 *
 * This exists so the render graph has no dependency on how samples leave the
 * process. Two consequences:
 *
 * - Tests drive the whole engine through [OfflineAudioOutput] and assert on the
 *   samples it collects, so mixing, crossfade law, EQ, and reverse playback are
 *   all covered by ordinary JVM unit tests with no device involved.
 * - Each platform supplies its own implementation — `AudioTrackOutput` on
 *   Android (`AudioTrack`, in `:app`), `DesktopAudioOutput` on desktop
 *   (`javax.sound.sampled`, in `:desktopApp`) — and nothing in `dsp/`, `Deck`,
 *   `Mixer`, or a screen changes between them. That is the whole point of
 *   this interface living here rather than next to either implementation.
 */
interface AudioOutput {

    /** Sample rate the sink expects. */
    val sampleRate: Int

    /** Frames requested per render callback. */
    val framesPerBuffer: Int

    /**
     * Starts pulling from [render], which must fill the supplied interleaved
     * stereo buffer with the given number of frames.
     */
    fun start(render: (buffer: FloatArray, frames: Int) -> Unit)

    fun stop()

    fun release()

    /**
     * Hints that something is about to make a sound, so an output that has stood
     * down can come back immediately rather than at its next idle check.
     *
     * Advisory: an output that never stands down does nothing, and one that does
     * must still be correct without ever being told.
     */
    fun wake() = Unit

    /**
     * Wired once by [AudioEngine], before [start], so an output that stands
     * down while idle knows when it may and how to reset the meters when it
     * does.
     *
     * Default is a no-op: an output with no stand-down behaviour (like
     * [OfflineAudioOutput]) simply never calls either lambda. This is what
     * lets [AudioEngine] stay platform-agnostic — the previous version
     * type-checked for `AudioTrackOutput` specifically, which only worked
     * because there was exactly one real output and it lived next to the
     * interface. A second platform makes that check wrong for the other one.
     */
    fun configureIdleTracking(isIdle: () -> Boolean, onStandDown: () -> Unit) = Unit
}

/**
 * Collects rendered audio into memory instead of playing it.
 *
 * Used by tests to inspect exactly what the engine produces.
 */
class OfflineAudioOutput(
    override val sampleRate: Int = 44_100,
    override val framesPerBuffer: Int = 256,
) : AudioOutput {

    private val collected = ArrayList<Float>()

    override fun start(render: (FloatArray, Int) -> Unit) = Unit

    override fun stop() = Unit

    override fun release() = Unit

    /** Renders [blocks] buffers through [render] and returns the interleaved result. */
    fun renderBlocks(blocks: Int, render: (FloatArray, Int) -> Unit): FloatArray {
        val buffer = FloatArray(framesPerBuffer * Deck.CHANNELS)
        collected.clear()
        repeat(blocks) {
            render(buffer, framesPerBuffer)
            for (i in 0 until framesPerBuffer * Deck.CHANNELS) collected.add(buffer[i])
        }
        return collected.toFloatArray()
    }
}

/**
 * Owns the mixer and the output, and wires one to the other.
 *
 * The single entry point a ViewModel or a desktop screen talks to.
 */
class AudioEngine(
    val output: AudioOutput,
) {
    val deckA = Deck("A", output.sampleRate)
    val deckB = Deck("B", output.sampleRate)
    val mixer = Mixer(deckA, deckB, output.sampleRate, maxFrames = output.framesPerBuffer)

    /**
     * Pads that record from this engine's own output and play back over it.
     *
     * Rendered after the mixer so a pad layers on top of the decks, and captured
     * from the mixer's output so a recording is what the performer actually
     * heard.
     */
    val sampler = Sampler(sampleRate = output.sampleRate)

    /**
     * A second, smaller pad bank the Automatchic Mix plays by itself.
     *
     * Separate from [sampler] because those eight pads belong to the performer.
     * A director that borrowed one would silence whatever the user had loaded
     * onto it, mid-set, to play a loop they did not ask for — and give it back
     * empty. Four pads is more than a transition ever needs at once.
     *
     * It never records, so its capture space is zero-length rather than the
     * couple of megabytes an unused thirty-second ceiling would reserve.
     */
    val mixSampler = Sampler(
        padCount = 4,
        sampleRate = output.sampleRate,
        maxRecordSeconds = 0.0,
    )

    /** Fired once when a scratch is dragged past the reverse threshold. */
    @Volatile
    var onReverseThreshold: (() -> Unit)? = null

    /**
     * The voice that answers a long backward scratch.
     *
     * Synthesised rather than shipped as a file: requirement F9 says no built-in
     * audio clips, and a bundled growl.ogg is a built-in audio clip whatever is
     * on it. See [com.hereliesaz.sirmatchalot.dsp.GrowlVoice].
     */
    val growl = OneShotVoice()

    /**
     * Band levels for the background light show.
     *
     * Three bands rather than one number, because one number makes everything
     * on screen pulse together — which is precisely what a strobe looks like.
     */
    val spectrum = SpectrumMeter(sampleRate = output.sampleRate)

    private val scratch = ScratchModel(sampleRate = output.sampleRate.toDouble())
    private var started = false

    /**
     * Whether the audio thread may stand down while nothing is sounding.
     *
     * Follows the user's setting. Turning it off restores the old behaviour —
     * the graph runs continuously — which costs battery and buys the guarantee
     * that the first sample after a pause is immediate.
     */
    @Volatile
    var idleShutdown: Boolean = true

    /**
     * True when nothing in the graph can be producing sound.
     *
     * Deliberately structural rather than a level measurement: a track playing a
     * silent passage is *sounding* and must keep the output open, or the first
     * beat after the silence would arrive a stand-down late. What counts is
     * whether anything is running, not whether it happens to be loud.
     */
    fun isIdle(): Boolean =
        !deckA.isSounding && !deckB.isSounding && !sampler.isActive &&
            !mixSampler.isActive && !growl.isPlaying

    /** Brings the output back immediately, ahead of its next idle check. */
    fun wake() = output.wake()

    fun start() {
        if (started) return
        started = true

        output.configureIdleTracking(
            isIdle = { idleShutdown && isIdle() },
            // Nothing is measuring once the output parks, so the meters would
            // otherwise stay frozen at whatever was playing when the music
            // stopped — a light show stuck mid-chorus over silence.
            onStandDown = {
                spectrum.reset()
                mixer.markSilent()
            },
        )

        // Synthesised off the audio thread, once. It is a couple of seconds of
        // formant synthesis — milliseconds of work, but not work the render
        // callback can be asked to wait for even once.
        if (!growl.isLoaded) {
            Thread({
                growl.load(
                    com.hereliesaz.sirmatchalot.dsp.GrowlVoice(sampleRate = output.sampleRate)
                        .render(),
                )
            }, "SirMatchALot-Growl").apply { isDaemon = true }.start()
        }

        output.start { buffer, frames ->
            mixer.render(buffer, frames)
            // Capture the deck mix before the pads are added, so re-triggering a
            // recorded pad while recording cannot feed back on itself.
            sampler.captureFromMaster(buffer, frames)
            sampler.render(buffer, frames)
            // After the performer's pads, so a director's loop layers over the
            // whole mix exactly as a hand-triggered one does.
            mixSampler.render(buffer, frames)
            growl.render(buffer, frames)
            // Measured last, so the lights follow everything a listener hears —
            // decks, pads and all — rather than only the deck mix.
            spectrum.measure(buffer, frames)
            if (scratch.accountForRenderedFrames(frames)) {
                // Triggered here rather than by the listener, so the sound
                // happens even if nothing is listening for the message.
                growl.trigger()
                onReverseThreshold?.invoke()
            }
        }
    }

    fun stop() {
        output.stop()
        started = false
    }

    fun release() {
        output.release()
        started = false
    }

    /**
     * Applies a computed beat alignment to a deck.
     *
     * Tempo is a rate multiplier and phase is a playhead nudge, both applied
     * once rather than chased. The previous implementation polled every 250 ms
     * from the UI and called `seekTo` whenever drift exceeded 40 ms, which could
     * not converge because the correction was coarser than the error.
     *
     * @param phaseOffsetSeconds positive shifts the deck later.
     */
    fun applyAlignment(deckName: String, tempoRatio: Double, phaseOffsetSeconds: Double) {
        val deck = if (deckName == "A") deckA else deckB
        deck.rate = tempoRatio
        val cycle = deck.cycleFrames
        if (cycle <= 0) return
        val shift = phaseOffsetSeconds * output.sampleRate
        var position = deck.playhead + shift
        // Keep the playhead on the circle rather than letting a correction walk
        // it off either end.
        position %= cycle
        if (position < 0) position += cycle
        deck.playhead = position
    }

    /** Begins a scratch on both decks. */
    fun beginScratch() = scratch.begin(deckA.rate)

    /** Updates a scratch in progress from the gesture's cumulative displacement. */
    fun updateScratch(totalDeltaY: Float) {
        val rate = scratch.update(totalDeltaY)
        deckA.rate = rate
        deckB.rate = rate
    }

    fun endScratch() {
        val rate = scratch.end()
        deckA.rate = rate
        deckB.rate = rate
    }

    fun scratchReverseProgress(): Float = scratch.reverseProgress()
}
