package com.hereliesaz.sirmatchalot.audio

/**
 * A single mono sound, played over the mix on demand.
 *
 * Exists for the easter egg — the growl that answers a long backward scratch —
 * but it is not specific to it: anything that needs one sound laid over the
 * output, triggered from anywhere, and mixed without allocating, belongs here.
 *
 * The rules that shape it are the audio thread's rules. [trigger] is a single
 * volatile write, so it is safe from any thread; [render] allocates nothing and
 * takes no locks; and the audio itself is supplied from outside via [load],
 * because synthesising or decoding it is not something the render callback can
 * be made to wait for.
 *
 * Retriggering restarts from the beginning rather than layering, which is what a
 * one-shot means: two overlapping copies of a voice saying the same sentence is
 * not twice as good.
 */
class OneShotVoice {

    @Volatile
    private var samples: FloatArray? = null

    /** Playback position in frames; negative is idle. */
    @Volatile
    private var position: Int = IDLE

    /** How loud, relative to the mix it sits over. */
    @Volatile
    var gain: Float = 0.9f

    val isLoaded: Boolean get() = samples != null

    val isPlaying: Boolean get() = position >= 0

    /**
     * Supplies the audio.
     *
     * Stops any playback first: swapping the buffer under a live playhead would
     * read past the end of the new one if it were shorter.
     */
    fun load(mono: FloatArray?) {
        position = IDLE
        samples = mono?.takeIf { it.isNotEmpty() }
    }

    /**
     * Starts it from the beginning.
     *
     * Safe from any thread, and a no-op when nothing is loaded — so a trigger
     * that fires before the audio has finished being prepared is silently
     * ignored rather than crashing or half-playing.
     */
    fun trigger() {
        if (samples != null) position = 0
    }

    fun stop() {
        position = IDLE
    }

    /**
     * Mixes into [out], which is interleaved stereo, adding rather than
     * replacing so this layers over whatever is already there.
     *
     * Centred: the same mono sample goes to both channels.
     */
    fun render(out: FloatArray, frames: Int) {
        val audio = samples ?: return
        var at = position
        if (at < 0) return

        val level = gain
        for (i in 0 until frames) {
            if (at >= audio.size) {
                at = IDLE
                break
            }
            val sample = audio[at] * level
            val base = i * Deck.CHANNELS
            out[base] += sample
            out[base + 1] += sample
            at++
        }
        position = at
    }

    companion object {
        const val IDLE = -1
    }
}
