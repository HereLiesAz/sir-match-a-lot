package com.hereliesaz.sirmatchalot.audio

import com.hereliesaz.sirmatchalot.dsp.Biquad
import com.hereliesaz.sirmatchalot.dsp.BiquadCoefficients
import com.hereliesaz.sirmatchalot.dsp.Resampler
import kotlin.math.abs

/**
 * One audio clip placed on a deck's circular timeline.
 *
 * @param id stable identifier, matching the library track or loop.
 * @param buffer decoded audio.
 * @param startFrame where on the deck timeline this clip begins.
 * @param gain linear gain multiplier.
 * @param loop whether the clip repeats for the whole timeline rather than
 *   occupying only its own span. A single clip alone on a deck loops, which is
 *   what makes one sample circle the whole platter.
 */
class Clip(
    val id: String,
    val buffer: PcmBuffer,
    val startFrame: Int = 0,
    val gain: Float = 1f,
    val loop: Boolean = false,
) {
    val frameCount: Int get() = buffer.frameCount

    /** One past the last frame of the deck timeline this clip covers. */
    val endFrame: Int get() = startFrame + frameCount
}

/**
 * A deck: a circular timeline of clips read by a single signed-rate playhead,
 * then filtered and gained.
 *
 * The playhead is a `Double` frame index advanced by a **signed** rate, so a
 * negative rate plays backwards and the signal stays continuous through zero —
 * there is no seek, and therefore no click, anywhere in a scratch. The timeline
 * wraps at [cycleFrames], which is the same wrap the platter draws as one full
 * revolution: angle and time are the same quantity, measured once.
 *
 * Not thread-safe. Owned by the render thread; controls are set from outside via
 * plain volatile writes, which is safe for the single `Float`/`Double` fields
 * used here and avoids locking the audio path.
 */
class Deck(
    val name: String,
    val outputSampleRate: Int = 44_100,
) {
    /** Clips currently loaded. Replaced wholesale, never mutated in place. */
    @Volatile
    var clips: List<Clip> = emptyList()
        set(value) {
            field = value
            // Keep the playhead inside the new cycle rather than stranding it
            // past the end of a shortened timeline.
            val frames = cycleFrames
            if (frames > 0 && playhead >= frames) playhead %= frames
        }

    /**
     * Length of one revolution in frames — the longest clip extent, so the
     * timeline is exactly as long as the material on it.
     */
    val cycleFrames: Int
        get() = clips.maxOfOrNull { it.endFrame } ?: 0

    /** Current position on the timeline, in frames. */
    @Volatile
    var playhead: Double = 0.0

    /**
     * Playback rate. 1.0 is normal speed, negative plays backwards, 0.0 holds.
     * Pitch follows rate, as on a turntable.
     */
    @Volatile
    var rate: Double = 1.0

    @Volatile
    var gain: Float = 1f

    /** Low-shelf boost in dB, driven by the two-finger pinch bass-boost gesture. */
    @Volatile
    var bassBoostDb: Double = 0.0

    /** High-shelf gain in dB. */
    @Volatile
    var trebleDb: Double = 0.0

    @Volatile
    var playing: Boolean = false

    private val bassFilters = Array(CHANNELS) { Biquad() }
    private val trebleFilters = Array(CHANNELS) { Biquad() }
    private var appliedBassDb = Double.NaN
    private var appliedTrebleDb = Double.NaN

    /** Peak absolute sample of the most recent block, for the visuals. */
    @Volatile
    var outputLevel: Float = 0f
        private set

    /**
     * Renders [frames] frames into [out], which is interleaved stereo and must
     * hold at least `frames * 2` values. Existing content is overwritten.
     *
     * Allocates nothing.
     */
    fun render(out: FloatArray, frames: Int) {
        require(out.size >= frames * CHANNELS) { "output buffer too small" }

        java.util.Arrays.fill(out, 0, frames * CHANNELS, 0f)

        val cycle = cycleFrames
        if (!playing || cycle <= 0 || clips.isEmpty()) {
            outputLevel = 0f
            return
        }

        val snapshot = clips
        var position = playhead

        for (frame in 0 until frames) {
            var left = 0f
            var right = 0f

            for (clip in snapshot) {
                val local = localPosition(clip, position, cycle) ?: continue
                left += Resampler.read(clip.buffer.channel(0), local) * clip.gain
                right += Resampler.read(clip.buffer.channel(1), local) * clip.gain
            }

            val base = frame * CHANNELS
            out[base] = left
            out[base + 1] = right

            position += rate * rateScale(snapshot)
            // Wrap in whichever direction the playhead is travelling.
            if (position >= cycle) position -= cycle
            else if (position < 0.0) position += cycle
        }

        playhead = position

        applyEq(out, frames)
        applyGain(out, frames)
        outputLevel = peakOf(out, frames)
    }

    /**
     * Position within [clip]'s own buffer for a deck-timeline [position], or null
     * if the clip is not sounding there.
     */
    private fun localPosition(clip: Clip, position: Double, cycle: Int): Double? {
        if (clip.frameCount <= 0) return null
        if (clip.loop) {
            // A looping clip sounds everywhere on the timeline.
            var offset = (position - clip.startFrame) % clip.frameCount
            if (offset < 0) offset += clip.frameCount
            return offset
        }
        if (position < clip.startFrame || position >= clip.endFrame) return null
        return position - clip.startFrame
    }

    /**
     * Converts the deck's rate into frames of source per frame of output. Clips
     * decoded at a different sample rate than the output are corrected here, so
     * a 48 kHz sample and a 44.1 kHz sample play at the same musical speed.
     */
    private fun rateScale(snapshot: List<Clip>): Double {
        val sourceRate = snapshot.firstOrNull()?.buffer?.sampleRate ?: outputSampleRate
        return sourceRate.toDouble() / outputSampleRate
    }

    private fun applyEq(out: FloatArray, frames: Int) {
        val bass = bassBoostDb
        val treble = trebleDb

        if (bass != appliedBassDb) {
            val coefficients =
                if (abs(bass) < 0.01) BiquadCoefficients.IDENTITY
                else BiquadCoefficients.lowShelf(200.0, outputSampleRate, bass)
            bassFilters.forEach { it.coefficients = coefficients }
            appliedBassDb = bass
        }
        if (treble != appliedTrebleDb) {
            val coefficients =
                if (abs(treble) < 0.01) BiquadCoefficients.IDENTITY
                else BiquadCoefficients.highShelf(4000.0, outputSampleRate, treble)
            trebleFilters.forEach { it.coefficients = coefficients }
            appliedTrebleDb = treble
        }

        val bassActive = abs(appliedBassDb) >= 0.01
        val trebleActive = abs(appliedTrebleDb) >= 0.01
        if (!bassActive && !trebleActive) return

        for (channel in 0 until CHANNELS) {
            var i = channel
            while (i < frames * CHANNELS) {
                var sample = out[i]
                if (bassActive) sample = bassFilters[channel].process(sample)
                if (trebleActive) sample = trebleFilters[channel].process(sample)
                out[i] = sample
                i += CHANNELS
            }
        }
    }

    private fun applyGain(out: FloatArray, frames: Int) {
        val target = gain
        for (i in 0 until frames * CHANNELS) out[i] *= target
    }

    private fun peakOf(out: FloatArray, frames: Int): Float {
        var peak = 0f
        for (i in 0 until frames * CHANNELS) {
            val magnitude = abs(out[i])
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }

    /** Position on the timeline as a fraction of one revolution, for the platter. */
    fun cyclePosition(): Float {
        val cycle = cycleFrames
        if (cycle <= 0) return 0f
        return (playhead / cycle).toFloat().coerceIn(0f, 1f)
    }

    /** Seeks to a fraction of one revolution. */
    fun seekToFraction(fraction: Float) {
        val cycle = cycleFrames
        playhead = if (cycle <= 0) 0.0 else fraction.coerceIn(0f, 1f).toDouble() * cycle
    }

    fun reset() {
        playhead = 0.0
        rate = 1.0
        playing = false
        bassFilters.forEach { it.reset() }
        trebleFilters.forEach { it.reset() }
    }

    companion object {
        const val CHANNELS = 2
    }
}
