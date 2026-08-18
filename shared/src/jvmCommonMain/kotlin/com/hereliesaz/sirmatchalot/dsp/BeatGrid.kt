package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A measured beat grid: where every beat and bar line falls, in seconds.
 *
 * This replaces the app's previous notion of a "beat grid" — 32 evenly spaced
 * ticks drawn around a circle with no relationship to the audio. Every tick here
 * comes from [TempoDetector], so snapping a clip to a bar line actually lands on
 * a bar line.
 *
 * @param bpm measured tempo.
 * @param firstBeatSeconds phase of the grid.
 * @param beatsPerBar time signature numerator.
 * @param downbeatOffset which beat index carries the downbeat, in `0 until beatsPerBar`.
 */
data class BeatGrid(
    val bpm: Double,
    val firstBeatSeconds: Double,
    val beatsPerBar: Int = 4,
    val downbeatOffset: Int = 0,
) {
    init {
        require(bpm > 0) { "bpm must be positive" }
        require(beatsPerBar > 0) { "beatsPerBar must be positive" }
        require(downbeatOffset in 0 until beatsPerBar) { "downbeatOffset out of range" }
    }

    /** Seconds per beat. */
    val beatDuration: Double get() = 60.0 / bpm

    /** Seconds per bar. */
    val barDuration: Double get() = beatDuration * beatsPerBar

    /** Time of beat [index]; negative indices extrapolate backwards. */
    fun beatTime(index: Int): Double = firstBeatSeconds + index * beatDuration

    /** Index of the most recent beat at or before [seconds]. */
    fun beatIndexAt(seconds: Double): Int =
        floor((seconds - firstBeatSeconds) / beatDuration).toInt()

    /** Time of the beat closest to [seconds]. */
    fun nearestBeatTime(seconds: Double): Double =
        beatTime(((seconds - firstBeatSeconds) / beatDuration).roundToInt())

    /** Time of the bar line closest to [seconds]. */
    fun nearestBarTime(seconds: Double): Double {
        val downbeatZero = beatTime(downbeatOffset)
        val bar = ((seconds - downbeatZero) / barDuration).roundToInt()
        return downbeatZero + bar * barDuration
    }

    fun isDownbeat(index: Int): Boolean =
        ((index - downbeatOffset) % beatsPerBar + beatsPerBar) % beatsPerBar == 0

    /** Every beat time in `[0, durationSeconds)`. */
    fun beatTimes(durationSeconds: Double): DoubleArray {
        if (durationSeconds <= 0) return DoubleArray(0)
        var i = beatIndexAt(0.0)
        while (beatTime(i) < 0.0) i++
        val times = ArrayList<Double>()
        while (beatTime(i) < durationSeconds) {
            times.add(beatTime(i))
            i++
        }
        return times.toDoubleArray()
    }

    /**
     * Phase difference, in seconds, that must be added to this grid for its
     * beats to line up with [other]'s — the quantity a beat-sync has to null
     * out. Always within half a beat of zero.
     */
    fun phaseErrorTo(other: BeatGrid, atSeconds: Double): Double {
        val target = other.nearestBeatTime(atSeconds)
        val mine = nearestBeatTime(atSeconds)
        var error = target - mine
        val half = beatDuration / 2
        while (error > half) error -= beatDuration
        while (error < -half) error += beatDuration
        return error
    }

    companion object {
        /** Four four, unless something measured says otherwise. */
        const val DEFAULT_BEATS_PER_BAR = 4

        /**
         * A stored [downbeatOffset] brought into range.
         *
         * The constructor's `require` is right for a value a programmer wrote and
         * wrong for a value a database returned: an offset that arrived out of
         * range from an old row should cost that track its downbeat phase, not
         * throw on the thread that was loading it. Wrapping rather than clamping,
         * because an offset is a residue class — 5 in four four is 1, and that is
         * a better answer than 3.
         */
        fun sanitiseDownbeat(offset: Int, beatsPerBar: Int = DEFAULT_BEATS_PER_BAR): Int {
            if (beatsPerBar <= 0) return 0
            return ((offset % beatsPerBar) + beatsPerBar) % beatsPerBar
        }

        /**
         * Infers which beat of the bar is the downbeat by asking which residue
         * class collects the most onset strength — kick drums land on beat one.
         */
        fun inferDownbeat(
            envelope: OnsetEnvelope,
            bpm: Double,
            firstBeatSeconds: Double,
            beatsPerBar: Int = 4,
        ): Int {
            if (envelope.size == 0) return 0
            val beatDuration = 60.0 / bpm
            val sums = DoubleArray(beatsPerBar)

            var index = 0
            while (true) {
                val time = firstBeatSeconds + index * beatDuration
                if (time < 0) { index++; continue }
                val frame = envelope.frameOf(time)
                if (frame >= envelope.size) break
                sums[index % beatsPerBar] += envelope.values[frame].toDouble()
                index++
            }

            var best = 0
            for (i in 1 until beatsPerBar) if (sums[i] > sums[best]) best = i
            return best
        }
    }
}
