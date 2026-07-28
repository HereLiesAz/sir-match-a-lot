package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.dsp.BeatGrid
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What has to change for one track to run in time with another.
 *
 * @param tempoRatio multiply the source's playback rate by this to match the
 *   target's tempo. 1.0 means already in tempo.
 * @param phaseOffsetSeconds shift the source by this to put its beats on the
 *   target's. Always within half a beat of zero, so the correction is the short
 *   way round.
 * @param semitoneShift pitch shift that brings the source's key to the target's,
 *   never more than six semitones in either direction. Zero when either key is
 *   unmeasured, since guessing would be worse than leaving it alone.
 * @param isHalfOrDoubleTime true when the match is against half or double the
 *   target tempo, which is a real and common pairing — 174 BPM
 *   drum-and-bass over 87 BPM hip-hop.
 */
data class BeatAlignment(
    val tempoRatio: Double,
    val phaseOffsetSeconds: Double,
    val semitoneShift: Int,
    val isHalfOrDoubleTime: Boolean,
) {
    /** True when nothing needs to change. */
    val isAligned: Boolean
        get() = abs(tempoRatio - 1.0) < 1e-6 &&
            abs(phaseOffsetSeconds) < 1e-6 &&
            semitoneShift == 0
}

/**
 * Computes the tempo, phase, and pitch corrections that align two tracks.
 *
 * This is what "Auto Beat Sync" and "Harmonize" have to be built on, and it
 * could not exist before: the previous implementation had no beat phase at all,
 * and its tempo came from a hash of the filename. It attempted phase correction
 * in the UI layer by polling every 250 ms and calling `seekTo` whenever drift
 * exceeded 40 ms — which cannot converge, because the correction is coarser than
 * the error it is chasing.
 *
 * Everything here is pure arithmetic over measured values, so it is fully
 * testable; applying the result is the audio engine's job.
 */
object BeatSync {

    /**
     * Aligns [source] to [target] at [atSeconds] on the timeline.
     *
     * @return null when either track has no measured tempo. Callers should leave
     *   such a track alone rather than warping it by a guessed ratio.
     */
    fun align(source: Track, target: Track, atSeconds: Double = 0.0): BeatAlignment? {
        val sourceBpm = source.bpm ?: return null
        val targetBpm = target.bpm ?: return null
        if (sourceBpm <= 0.0 || targetBpm <= 0.0) return null

        val (ratio, halfOrDouble) = tempoRatioTo(sourceBpm, targetBpm)

        // Phase is compared after the tempo correction, since stretching the
        // source moves its beats.
        val phase = phaseOffset(source, target, sourceBpm * ratio, atSeconds)

        return BeatAlignment(
            tempoRatio = ratio,
            phaseOffsetSeconds = phase,
            semitoneShift = semitoneShift(source, target),
            isHalfOrDoubleTime = halfOrDouble,
        )
    }

    /**
     * The ratio that brings [sourceBpm] to [targetBpm], or to half or double it
     * if that is a closer match.
     *
     * Choosing the nearest metrical relationship rather than always the literal
     * one is what keeps a sync from stretching a 174 BPM track down to 87 — a 2x
     * warp that would destroy it — when playing it at its own tempo over a
     * half-time track is what a DJ actually wants.
     */
    fun tempoRatioTo(sourceBpm: Double, targetBpm: Double): Pair<Double, Boolean> {
        // Quarter and quadruple time are included as well as half and double.
        // With only 1x/2x/0.5x the covered levels are an octave apart *from the
        // target*, so a source far outside that span still needs a large warp:
        // 70 BPM against 200 BPM had to stretch by 1.43x. Extending the ladder
        // keeps the worst case near the square root of two, which is the most any
        // source can be from the nearest metrical level.
        val candidates = listOf(
            targetBpm to false,
            targetBpm * 2.0 to true,
            targetBpm / 2.0 to true,
            targetBpm * 4.0 to true,
            targetBpm / 4.0 to true,
        )
        // Compare in the log domain so being 10% fast and 10% slow weigh equally.
        val best = candidates.minByOrNull { (bpm, _) ->
            abs(Math.log(bpm / sourceBpm))
        } ?: return 1.0 to false
        return (best.first / sourceBpm) to best.second
    }

    /**
     * Seconds to shift [source] so its beats land on [target]'s.
     *
     * @param effectiveSourceBpm the source's tempo *after* the tempo correction.
     */
    fun phaseOffset(
        source: Track,
        target: Track,
        effectiveSourceBpm: Double,
        atSeconds: Double,
    ): Double {
        val sourcePhase = source.firstBeatSeconds ?: return 0.0
        val targetPhase = target.firstBeatSeconds ?: return 0.0
        val targetBpm = target.bpm ?: return 0.0
        if (effectiveSourceBpm <= 0.0 || targetBpm <= 0.0) return 0.0

        val sourceGrid = BeatGrid(effectiveSourceBpm, sourcePhase)
        val targetGrid = BeatGrid(targetBpm, targetPhase)
        return sourceGrid.phaseErrorTo(targetGrid, atSeconds)
    }

    /**
     * Semitones to shift [source] toward [target]'s key, by the short way round
     * the wheel. Zero when either key is unmeasured.
     */
    fun semitoneShift(source: Track, target: Track): Int {
        val sourceKey = source.camelotKey ?: return 0
        val targetKey = target.camelotKey ?: return 0
        return HarmonicEngine.getShortestSemitoneShift(targetKey, sourceKey)
    }

    /**
     * The beat time in [track] nearest to [seconds], for snapping a clip or a cue
     * to the grid. Null when the track has no measured grid.
     */
    fun nearestBeat(track: Track, seconds: Double): Double? {
        val bpm = track.bpm ?: return null
        val phase = track.firstBeatSeconds ?: return null
        return BeatGrid(bpm, phase).nearestBeatTime(seconds)
    }

    /**
     * The bar line in [track] nearest to [seconds]. Loops and transitions belong
     * on bars, not on arbitrary beats.
     */
    fun nearestBar(track: Track, seconds: Double): Double? =
        track.measuredBeatGrid()?.nearestBarTime(seconds)

    /**
     * Length of [bars] bars in [track], for making a loop that stays musical.
     */
    fun barDurationSeconds(track: Track, bars: Int = 1): Double? {
        val bpm = track.bpm ?: return null
        if (bpm <= 0.0 || bars <= 0) return null
        return BeatGrid(bpm, track.firstBeatSeconds ?: 0.0).barDuration * bars
    }

    /**
     * Whether [source] can be brought to [target]'s tempo without an audible
     * stretch — the conventional limit is about 6%.
     */
    fun canSyncWithoutArtefacts(source: Track, target: Track, tolerance: Double = 0.06): Boolean {
        val alignment = align(source, target) ?: return false
        return abs(alignment.tempoRatio - 1.0) <= tolerance
    }

    /** Percentage tempo change the alignment implies, for display. */
    fun tempoChangePercent(alignment: BeatAlignment): Double =
        (alignment.tempoRatio - 1.0) * 100.0

    /** Rounds a ratio to the nearest whole percent, for a readable pitch-fader figure. */
    fun roundedPercent(alignment: BeatAlignment): Int =
        tempoChangePercent(alignment).roundToInt()
}
