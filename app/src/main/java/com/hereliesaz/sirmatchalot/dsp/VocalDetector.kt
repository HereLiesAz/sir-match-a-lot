package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where the singing starts.
 *
 * `A9` asked for points of interest including **vocal entry**, and the honest
 * status until now was that vocal detection "would need a separate model". That
 * is true of *transcribing* a vocal, or of separating one. It is not true of
 * noticing that one has begun, which is a measurement — and this is that
 * measurement rather than a guess dressed up as one.
 *
 * ## What actually distinguishes a voice
 *
 * Not its frequency range: a lead synth, a guitar and a voice all live in
 * roughly 200–3500 Hz, so a band-energy detector fires on all three. The thing a
 * voice does that almost nothing else in recorded music does is **refuse to hold
 * still**. A singer's fundamental slides into notes, wavers across them, and
 * slides out; a synth, a piano and a bass sit on the twelve-tone grid and stay
 * there for the length of the note.
 *
 * So the detector measures three things per frame and requires all of them:
 *
 * 1. **Harmonic content in the vocal range.** A dominant fundamental between
 *    [MIN_F0_HZ] and [MAX_F0_HZ] with real harmonics above it, found by harmonic
 *    product spectrum. Rules out percussion and noise, which have no F0.
 * 2. **Pitch instability.** How far the fundamental moves between frames, in
 *    cents. Steady pitch scores zero however loud it is — this is what
 *    separates a voice from a lead synth playing the same notes.
 * 3. **Formant-band presence.** Energy in 300–3400 Hz relative to the whole
 *    spectrum. Rules out a bassline whose pitch happens to wobble.
 *
 * ## What it is not
 *
 * It will report a slide guitar, a theremin, a heavily pitch-bent lead, and an
 * expressive violin. Those are all *correct* answers to the question it can
 * actually ask — "is something singing here" — and wrong answers to "is that a
 * human". Recorded as a limit rather than papered over, because a performer
 * seeing a vocal marker on an instrumental break should know why.
 *
 * Pure arithmetic over decoded audio, so it is unit-tested against synthesised
 * material where the answer is known by construction.
 */
class VocalDetector(
    private val stft: Stft = Stft(frameSize = 2048, hop = 512),
) {

    /**
     * A stretch of the track where something is singing.
     *
     * @param startSeconds when it begins — the value A9 asked for.
     * @param endSeconds when it stops.
     * @param confidence mean score over the stretch, 0..1.
     */
    data class VocalRegion(
        val startSeconds: Double,
        val endSeconds: Double,
        val confidence: Float,
    ) {
        val durationSeconds: Double get() = endSeconds - startSeconds
    }

    /**
     * Per-frame vocal-likeness, 0..1.
     *
     * Exposed separately from [findRegions] because it is the measurement, and a
     * caller that wants to threshold it differently — or draw it — should not
     * have to re-derive it.
     */
    fun score(mono: FloatArray, sampleRate: Int): FloatArray {
        if (mono.isEmpty() || sampleRate <= 0) return FloatArray(0)
        val frames = stft.frameCount(mono.size)
        if (frames <= 0) return FloatArray(0)

        val f0 = DoubleArray(frames)
        val harmonicity = FloatArray(frames)
        val formantShare = FloatArray(frames)

        stft.forEachFrame(mono) { index, magnitudes ->
            if (index < frames) {
                val estimate = estimateF0(magnitudes, sampleRate)
                f0[index] = estimate.frequency
                harmonicity[index] = estimate.strength
                formantShare[index] = formantBandShare(magnitudes, sampleRate)
            }
        }

        val out = FloatArray(frames)
        for (i in frames.indices()) {
            // Pitch movement over a short window rather than one hop: vibrato is
            // roughly 5–7 Hz, so a single 12 ms step can catch it at a turning
            // point and read as perfectly steady.
            val movement = pitchMovementCents(f0, i)
            if (movement <= 0f) continue

            // Saturating rather than linear: past about a semitone of wander
            // per window, more wander is not more evidence.
            val instability = min(1f, movement / STEADY_CENTS)

            out[i] = (harmonicity[i] * instability * formantShare[i]).coerceIn(0f, 1f)
        }
        return smooth(out, radius = SMOOTHING_FRAMES)
    }

    /**
     * The stretches where [score] stays above [threshold].
     *
     * @param minimumSeconds regions shorter than this are dropped. A word is not
     *   a vocal entry; two consecutive frames of a synth bend should not put a
     *   marker on the platter.
     * @param gapSeconds silence shorter than this does not end a region, so a
     *   breath between lines is not two vocals.
     */
    fun findRegions(
        mono: FloatArray,
        sampleRate: Int,
        threshold: Float = 0.18f,
        minimumSeconds: Double = 1.5,
        gapSeconds: Double = 1.2,
    ): List<VocalRegion> {
        val scores = score(mono, sampleRate)
        if (scores.isEmpty()) return emptyList()

        val secondsPerFrame = 1.0 / stft.frameRate(sampleRate)
        val gapFrames = (gapSeconds / secondsPerFrame).toInt()

        val regions = ArrayList<VocalRegion>()
        var start = -1
        var quiet = 0
        var sum = 0f
        var count = 0

        fun close(endFrame: Int) {
            if (start < 0) return
            val region = VocalRegion(
                startSeconds = start * secondsPerFrame,
                endSeconds = endFrame * secondsPerFrame,
                confidence = if (count == 0) 0f else sum / count,
            )
            if (region.durationSeconds >= minimumSeconds) regions.add(region)
            start = -1
            sum = 0f
            count = 0
        }

        for (i in scores.indices) {
            if (scores[i] >= threshold) {
                if (start < 0) start = i
                quiet = 0
                sum += scores[i]
                count++
            } else if (start >= 0) {
                quiet++
                if (quiet > gapFrames) close(i - quiet)
            }
        }
        close(scores.size)
        return regions
    }

    /**
     * The first moment something starts singing, or null.
     *
     * This is the point A9 actually asks to be tagged.
     */
    fun findVocalEntry(mono: FloatArray, sampleRate: Int): PointOfInterest? {
        val region = findRegions(mono, sampleRate).firstOrNull() ?: return null
        return PointOfInterest(
            timeSeconds = region.startSeconds,
            kind = PointOfInterest.Kind.VOCAL,
            strength = region.confidence,
        )
    }

    // --- Measurement ---

    private data class F0Estimate(val frequency: Double, val strength: Float)

    /**
     * Dominant fundamental by harmonic product spectrum.
     *
     * The spectrum is multiplied by decimated copies of itself, so a bin only
     * survives if there is energy at its multiples too. A partial with no
     * harmonics above it — a bell, a cymbal, a sine — collapses; a voice, which
     * has a dozen, does not.
     */
    private fun estimateF0(magnitudes: FloatArray, sampleRate: Int): F0Estimate {
        val binHz = sampleRate.toDouble() / stft.frameSize
        val lowBin = max(1, (MIN_F0_HZ / binHz).toInt())
        val highBin = min(magnitudes.size - 1, (MAX_F0_HZ / binHz).toInt())
        if (highBin <= lowBin) return F0Estimate(0.0, 0f)

        var best = 0
        var bestProduct = 0f
        var total = 0f
        // Kept so the peak can be interpolated without a second pass.
        val strengths = FloatArray(highBin - lowBin + 1)

        for (bin in lowBin..highBin) {
            var product = magnitudes[bin]
            for (harmonic in 2..HARMONICS) {
                val index = bin * harmonic
                if (index >= magnitudes.size) break
                product *= magnitudes[index]
            }
            // Root, so the product does not underflow to zero on quiet frames
            // and so it stays comparable between frames of different loudness.
            val strength = if (product <= 0f) 0f else product.toDouble().pow(1.0 / HARMONICS).toFloat()
            strengths[bin - lowBin] = strength
            total += strength
            if (strength > bestProduct) {
                bestProduct = strength
                best = bin
            }
        }
        if (best == 0 || total <= 0f) return F0Estimate(0.0, 0f)

        // How dominant the winner is over the average candidate. A frame with
        // one clear fundamental scores high; noise, where every bin is equally
        // plausible, scores near zero.
        val candidates = (highBin - lowBin + 1).toFloat()
        val dominance = ((bestProduct / (total / candidates)) - 1f) / DOMINANCE_SCALE
        return F0Estimate(interpolatedBin(strengths, best - lowBin, lowBin) * binHz, dominance.coerceIn(0f, 1f))
    }

    /**
     * The peak's true position, between bins.
     *
     * Without this the fundamental is quantised to the FFT's bin spacing — at
     * this frame size and rate, about 80 cents at 220 Hz. Vibrato spans roughly
     * a semitone, so a whole-bin estimate reports a singer as perfectly steady
     * right up until the wander happens to cross a bin edge, and the
     * instability term measures the grid rather than the voice.
     *
     * A parabola through the peak and its two neighbours, which is the standard
     * fit and is exact for a Gaussian-shaped peak in log magnitude — close
     * enough for one whose width is set by a Hann window.
     */
    private fun interpolatedBin(strengths: FloatArray, peak: Int, offset: Int): Double {
        if (peak <= 0 || peak >= strengths.size - 1) return (peak + offset).toDouble()
        val left = strengths[peak - 1]
        val here = strengths[peak]
        val right = strengths[peak + 1]
        val denominator = left - 2f * here + right
        if (abs(denominator) < 1e-12f) return (peak + offset).toDouble()
        val shift = 0.5f * (left - right) / denominator
        // A fit that lands outside the neighbouring bins is not a fit.
        if (abs(shift) > 1f) return (peak + offset).toDouble()
        return peak + offset + shift.toDouble()
    }

    /** Share of the frame's energy inside the formant band. */
    private fun formantBandShare(magnitudes: FloatArray, sampleRate: Int): Float {
        val binHz = sampleRate.toDouble() / stft.frameSize
        var inBand = 0f
        var all = 0f
        for (bin in magnitudes.indices) {
            val frequency = bin * binHz
            val magnitude = magnitudes[bin]
            all += magnitude
            if (frequency in FORMANT_LOW_HZ..FORMANT_HIGH_HZ) inBand += magnitude
        }
        if (all <= 0f) return 0f
        // Scaled so a typical vocal share — around a third — reaches 1, rather
        // than requiring a voice to be the only thing in the mix.
        return min(1f, (inBand / all) / FORMANT_SHARE_FOR_FULL_SCORE)
    }

    /**
     * How far the fundamental wanders around frame [index], in cents.
     *
     * Frames with no fundamental are skipped rather than treated as a jump to
     * zero, which would make every silence look like a glissando.
     */
    private fun pitchMovementCents(f0: DoubleArray, index: Int): Float {
        val from = max(0, index - MOVEMENT_FRAMES)
        val to = min(f0.size - 1, index + MOVEMENT_FRAMES)
        var lowest = Double.MAX_VALUE
        var highest = 0.0
        var seen = 0
        for (i in from..to) {
            val value = f0[i]
            if (value <= 0.0) continue
            lowest = min(lowest, value)
            highest = max(highest, value)
            seen++
        }
        if (seen < 3 || lowest == Double.MAX_VALUE || lowest <= 0.0) return 0f

        val cents = 1200.0 * ln(highest / lowest) / LN_2
        // An octave jump is the estimator picking a different harmonic, not a
        // singer. Discarded rather than scored as maximum instability.
        if (cents > OCTAVE_CENTS * 0.9) return 0f
        return cents.toFloat()
    }

    private fun smooth(values: FloatArray, radius: Int): FloatArray {
        if (radius <= 0 || values.size <= 1) return values
        val out = FloatArray(values.size)
        for (i in values.indices) {
            var sum = 0f
            var count = 0
            for (j in max(0, i - radius)..min(values.size - 1, i + radius)) {
                sum += values[j]
                count++
            }
            out[i] = sum / count
        }
        return out
    }

    private fun Int.indices() = 0 until this

    private fun Double.pow(exponent: Double) = Math.pow(this, exponent)

    companion object {
        /** Lowest fundamental treated as possibly a voice — below a bass singer. */
        const val MIN_F0_HZ = 70.0

        /** Highest — above a soprano's top, and below where harmonics dominate. */
        const val MAX_F0_HZ = 1_100.0

        /** The band vowel formants live in, and telephony's for the same reason. */
        const val FORMANT_LOW_HZ = 300.0
        const val FORMANT_HIGH_HZ = 3_400.0

        /** Harmonics multiplied in the product spectrum. */
        const val HARMONICS = 5

        /**
         * Cents of wander, over one measurement window, that counts as fully
         * unsteady. A semitone: vibrato spans roughly this, and fixed-pitch
         * instruments span none of it.
         */
        const val STEADY_CENTS = 100f

        /** Half-width of the pitch-movement window, in frames. */
        const val MOVEMENT_FRAMES = 4

        /** Half-width of the score smoothing window, in frames. */
        const val SMOOTHING_FRAMES = 6

        /** Formant-band share at which the band term reaches 1. */
        const val FORMANT_SHARE_FOR_FULL_SCORE = 0.35f

        /** How far above average a fundamental must stand to score 1. */
        const val DOMINANCE_SCALE = 12f

        const val OCTAVE_CENTS = 1_200.0
        private val LN_2 = ln(2.0)
    }
}
