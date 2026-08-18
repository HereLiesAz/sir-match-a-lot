package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A measured musical key.
 *
 * @param rootPitchClass 0 = C, 1 = C#, ... 11 = B.
 * @param isMinor true for a minor key.
 * @param confidence 0..1; margin between the winning key and the runner-up.
 */
data class KeyEstimate(
    val rootPitchClass: Int,
    val isMinor: Boolean,
    val confidence: Float,
) {
    /** e.g. "A minor", "Eb major". */
    val name: String
        get() = "${PITCH_CLASS_NAMES[rootPitchClass]} ${if (isMinor) "minor" else "major"}"

    companion object {
        val PITCH_CLASS_NAMES = arrayOf(
            "C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B",
        )
    }
}

/**
 * Estimates key from a chromagram correlated against the Krumhansl-Schmuckler
 * key profiles.
 *
 * A chromagram folds the spectrum into twelve pitch classes, so it measures
 * which notes are actually present and for how long. Correlating that against
 * the twenty-four rotated profiles picks the key whose expected note
 * distribution best matches what was played.
 */
class KeyDetector(
    private val stft: Stft = Stft(frameSize = 4096, hop = 2048),
    private val minFrequency: Double = 65.0,   // ~C2
    private val maxFrequency: Double = 2100.0, // ~C7
) {
    fun detect(samples: FloatArray, sampleRate: Int): KeyEstimate? {
        val chroma = chromagram(samples, sampleRate) ?: return null
        return classify(chroma)
    }

    /**
     * Average pitch-class energy over the whole buffer, normalised to sum to 1.
     * Returns null if the signal has no usable tonal content.
     */
    fun chromagram(samples: FloatArray, sampleRate: Int): DoubleArray? {
        if (stft.frameCount(samples.size) == 0) return null

        val chroma = DoubleArray(12)
        val bins = stft.bins
        // Precompute each bin's pitch class once; it never changes. MIDI 69 is
        // A4 = 440 Hz, and MIDI 60 is C, so pitch class is `midi mod 12` with
        // 0 = C. Bins outside the tonal range are marked -1 and skipped.
        val binPitchClass = IntArray(bins) { -1 }
        for (k in 1 until bins) {
            val frequency = stft.binFrequency(k, sampleRate)
            if (frequency < minFrequency || frequency > maxFrequency) continue
            val midi = 69.0 + 12.0 * ln(frequency / 440.0) / ln(2.0)
            binPitchClass[k] = ((midi.roundToInt() % 12) + 12) % 12
        }

        stft.forEachFrame(samples) { _, magnitudes ->
            for (k in 1 until bins) {
                val pitchClass = binPitchClass[k]
                if (pitchClass < 0) continue
                // Squared magnitude weights sustained harmonic content over
                // broadband percussive noise.
                val magnitude = magnitudes[k].toDouble()
                chroma[pitchClass] += magnitude * magnitude
            }
        }

        val total = chroma.sum()
        if (total <= 0.0) return null
        for (i in 0 until 12) chroma[i] /= total
        return chroma
    }

    /** Picks the best of the 24 keys for a [chroma] profile. */
    fun classify(chroma: DoubleArray): KeyEstimate {
        require(chroma.size == 12) { "chroma must have 12 bins" }

        var bestScore = -Double.MAX_VALUE
        var runnerUp = -Double.MAX_VALUE
        var bestRoot = 0
        var bestMinor = false

        for (root in 0 until 12) {
            for (minor in booleanArrayOf(false, true)) {
                val profile = if (minor) MINOR_PROFILE else MAJOR_PROFILE
                val score = correlate(chroma, profile, root)
                if (score > bestScore) {
                    runnerUp = bestScore
                    bestScore = score
                    bestRoot = root
                    bestMinor = minor
                } else if (score > runnerUp) {
                    runnerUp = score
                }
            }
        }

        // Pearson correlations live in [-1, 1]; map the winner's margin into a
        // 0..1 confidence.
        val margin = ((bestScore - runnerUp) / 2.0).coerceIn(0.0, 1.0)
        return KeyEstimate(bestRoot, bestMinor, margin.toFloat())
    }

    /** Pearson correlation of [chroma] against [profile] rotated to [root]. */
    private fun correlate(chroma: DoubleArray, profile: DoubleArray, root: Int): Double {
        var meanChroma = 0.0
        var meanProfile = 0.0
        for (i in 0 until 12) {
            meanChroma += chroma[i]
            meanProfile += profile[i]
        }
        meanChroma /= 12.0
        meanProfile /= 12.0

        var covariance = 0.0
        var varianceChroma = 0.0
        var varianceProfile = 0.0
        for (i in 0 until 12) {
            val a = chroma[i] - meanChroma
            val b = profile[(i - root + 12) % 12] - meanProfile
            covariance += a * b
            varianceChroma += a * a
            varianceProfile += b * b
        }
        val denominator = sqrt(varianceChroma * varianceProfile)
        return if (denominator <= 0.0) 0.0 else covariance / denominator
    }

    companion object {
        /**
         * Krumhansl-Kessler probe-tone profiles, indexed from the tonic.
         * Reference: Krumhansl, *Cognitive Foundations of Musical Pitch* (1990).
         */
        val MAJOR_PROFILE = doubleArrayOf(
            6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88,
        )
        val MINOR_PROFILE = doubleArrayOf(
            6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17,
        )
    }
}
