package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.dsp.BeatGrid
import com.hereliesaz.sirmatchalot.dsp.EnergyCurve
import com.hereliesaz.sirmatchalot.dsp.LoopCandidate
import com.hereliesaz.sirmatchalot.dsp.PointOfInterest
import kotlin.math.abs

/**
 * Everything already known about the shape of one track, in one object.
 *
 * All of this is measured during analysis and cached — drops, breakdowns,
 * builds, vocal entries, the energy curve, loopable sections, the beat grid —
 * and until now none of it reached the thing performing the mix. The director
 * knew a track's *length* and nothing about its *music*, which is exactly why
 * every transition it made was identical.
 *
 * Deliberately a plain value: it holds analysis results, computes nothing, and
 * touches no Android class, so every decision made from it is unit-testable.
 *
 * @param points structural landmarks, from [com.hereliesaz.sirmatchalot.dsp.StructureFinder].
 * @param loops sections that repeat cleanly, ranked by self-similarity.
 * @param energy the measured energy curve, or null when it was never computed.
 */
data class TrackStructure(
    val trackId: String,
    val durationSeconds: Double,
    val bpm: Double?,
    val firstBeatSeconds: Double = 0.0,
    val downbeatOffset: Int = 0,
    val points: List<PointOfInterest> = emptyList(),
    val loops: List<LoopCandidate> = emptyList(),
    val energy: EnergyCurve? = null,
) {
    /** The measured grid, or null when the tempo was never found. */
    val grid: BeatGrid? = bpm
        ?.takeIf { it > 0.0 }
        ?.let {
            BeatGrid(
                bpm = it,
                firstBeatSeconds = firstBeatSeconds,
                downbeatOffset = BeatGrid.sanitiseDownbeat(downbeatOffset),
            )
        }

    /** Seconds in one bar, or 0 when there is no grid to measure it against. */
    val barSeconds: Double get() = grid?.barDuration ?: 0.0

    /** [seconds] snapped to the nearest bar line, or unchanged with no grid. */
    fun nearestBar(seconds: Double): Double =
        grid?.nearestBarTime(seconds)?.coerceIn(0.0, durationSeconds) ?: seconds

    /** [seconds] moved back by [bars] whole bars, never past the start. */
    fun barsBefore(seconds: Double, bars: Int): Double =
        (seconds - bars * barSeconds).coerceAtLeast(0.0)

    /** Measured energy at [seconds] on 0..1; 0.5 when nothing was measured. */
    fun energyAt(seconds: Double): Float = energy?.at(seconds) ?: 0.5f

    /**
     * Points to leave the track on, best first.
     *
     * The user asked for tracks to be cut at musical exits rather than played to
     * the end, and this is the list that makes that possible. Ranking is by what
     * a listener hears as a natural place for a song to hand over:
     *
     * - a **breakdown** is the best exit there is — the track has already taken
     *   itself away, so the next one walks into a space rather than a wall;
     * - the bar after a **drop** is next: the payoff has landed, and leaving
     *   straight afterwards is the one moment the drop cannot be undersold;
     * - the end of a clean **loop** is a seam the material itself provides;
     * - the track's own end is always available and always last, because
     *   choosing it means no musical exit was found, not that it is preferred.
     *
     * @param after ignore exits before this, so a track is never cut short.
     */
    fun exits(after: Double): List<Exit> {
        val end = durationSeconds
        if (end <= 0.0) return emptyList()

        val found = ArrayList<Exit>()
        for (point in points) {
            val at = point.timeSeconds
            if (at <= after || at >= end) continue
            when (point.kind) {
                PointOfInterest.Kind.BREAKDOWN ->
                    found.add(Exit(at, Exit.Reason.BREAKDOWN, 1.0 * point.strength))

                PointOfInterest.Kind.DROP -> {
                    // Not on the drop — a bar after it, so the drop is heard.
                    val payoff = (at + barSeconds * DROP_PAYOFF_BARS).coerceAtMost(end)
                    if (payoff > after) {
                        found.add(Exit(payoff, Exit.Reason.AFTER_DROP, 0.85 * point.strength))
                    }
                }

                else -> Unit
            }
        }
        for (loop in loops) {
            val at = loop.endSeconds
            if (at <= after || at >= end) continue
            found.add(Exit(at, Exit.Reason.LOOP_END, 0.6 * loop.score))
        }

        // The end is not a candidate among others: it is the floor, so there is
        // always somewhere to go even for a track nothing was measured on.
        found.add(Exit(end, Exit.Reason.TRACK_END, 0.2))

        // Sorted before deduping, not after: distinctBy keeps the first
        // element it sees per key, in whatever order the list already has —
        // which was insertion order (points before loops, and within each,
        // file order), not quality order. A weak breakdown landing in the
        // same 100ms bucket as a strong loop end used to survive just
        // because it happened to be appended first; the strong exit was
        // silently discarded despite ranking is "the whole decision" this
        // function's own header describes.
        return found
            .sortedByDescending { it.quality }
            .distinctBy { (it.atSeconds * 10).toLong() }
    }

    /**
     * Points to bring the track in on, best first, always ending with 0.0.
     *
     * A track that always enters at frame zero always enters the same way. Real
     * entries are *before* something: the bar before a build, a whole number of
     * bars before a drop so the drop lands where the fade ends, well before a
     * vocal so the voice arrives after the blend rather than during it.
     */
    fun entries(): List<Double> {
        if (barSeconds <= 0.0) return listOf(0.0)
        val found = ArrayList<Double>()
        for (point in points) {
            val candidate = when (point.kind) {
                PointOfInterest.Kind.BUILD -> barsBefore(point.timeSeconds, 1)
                PointOfInterest.Kind.DROP -> barsBefore(point.timeSeconds, DROP_RUN_UP_BARS)
                PointOfInterest.Kind.VOCAL -> barsBefore(point.timeSeconds, VOCAL_RUN_UP_BARS)
                else -> continue
            }
            // An entry has to leave enough track to be worth entering.
            if (candidate > 0.0 && candidate < durationSeconds * MAXIMUM_ENTRY_FRACTION) {
                found.add(nearestBar(candidate))
            }
        }
        return (found.distinct() + 0.0)
    }

    /** The strongest loop starting at or before [seconds], or null. */
    fun bestLoopBefore(seconds: Double): LoopCandidate? =
        loops.filter { it.endSeconds <= seconds }.maxByOrNull { it.score }

    /** The strongest loop covering [seconds], or null. */
    fun bestLoopAt(seconds: Double): LoopCandidate? =
        loops.filter { seconds in it.startSeconds..it.endSeconds }.maxByOrNull { it.score }

    /** The strongest point of [kind] after [seconds], or null. */
    fun firstPoint(kind: PointOfInterest.Kind, after: Double = 0.0): PointOfInterest? =
        points.filter { it.kind == kind && it.timeSeconds > after }.minByOrNull { it.timeSeconds }

    /**
     * The same structure in **playback** time rather than in the file's own time.
     *
     * Everything here is measured on the original file, and a track loaded onto a
     * deck has been conformed to the session's tempo — the audio is physically
     * time-stretched before it is ever heard, so a drop measured at 90 seconds
     * arrives somewhere else. Scaling once, here, is what lets every consumer
     * treat these numbers as wall-clock seconds; the alternative is carrying a
     * ratio alongside the structure and hoping every arithmetic site remembers it.
     *
     * @param ratio played duration over original duration. 1.0 leaves it alone.
     */
    fun scaledBy(ratio: Double): TrackStructure {
        if (!ratio.isFinite() || ratio <= 0.0 || abs(ratio - 1.0) < 1e-6) return this
        return copy(
            durationSeconds = durationSeconds * ratio,
            bpm = bpm?.let { it / ratio },
            firstBeatSeconds = firstBeatSeconds * ratio,
            points = points.map { it.copy(timeSeconds = it.timeSeconds * ratio) },
            loops = loops.map {
                it.copy(
                    startSeconds = it.startSeconds * ratio,
                    durationSeconds = it.durationSeconds * ratio,
                )
            },
            // The curve's values are unchanged; only how long each covers is.
            energy = energy?.let { EnergyCurve(it.values, it.windowSeconds * ratio) },
        )
    }

    /** True when there is enough measured here to choose anything from. */
    val isUsable: Boolean get() = grid != null && durationSeconds > 0.0

    /**
     * A place to leave a track, and how good a place it is.
     *
     * @param quality 0..1. Comparable across reasons on purpose — the ranking
     *   between a weak breakdown and a strong loop end is the whole decision.
     */
    data class Exit(
        val atSeconds: Double,
        val reason: Reason,
        val quality: Double,
    ) {
        enum class Reason { BREAKDOWN, AFTER_DROP, LOOP_END, TRACK_END }
    }

    companion object {
        /** How long a drop is allowed to land before the exit is taken. */
        const val DROP_PAYOFF_BARS = 8

        /** Bars of run-up so a drop lands as the incoming fade completes. */
        const val DROP_RUN_UP_BARS = 16

        /** Bars before a vocal, so the voice arrives after the blend. */
        const val VOCAL_RUN_UP_BARS = 8

        /** An entry past this far into a track leaves too little of it. */
        const val MAXIMUM_ENTRY_FRACTION = 0.5

        /** Builds one from a track and whatever analysis is to hand. */
        fun of(
            track: Track,
            points: List<PointOfInterest> = emptyList(),
            loops: List<LoopCandidate> = emptyList(),
            energy: EnergyCurve? = null,
        ) = TrackStructure(
            trackId = track.id,
            durationSeconds = track.durationMs / 1000.0,
            bpm = track.bpm,
            firstBeatSeconds = track.firstBeatSeconds ?: 0.0,
            downbeatOffset = track.downbeatOffset,
            points = points,
            loops = loops,
            energy = energy,
        )
    }
}

/**
 * Where the director gets a track's shape from.
 *
 * An interface rather than a lookup table so the domain stays free of the
 * caches, the decoder and the ViewModel — and so a test can hand the director a
 * structure it made up in three lines.
 *
 * Returning null is a first-class answer: an unanalysed track has no structure,
 * and everything downstream falls back to the plain blend rather than inventing
 * landmarks that were never measured.
 */
fun interface TrackStructureProvider {
    fun structureFor(track: Track): TrackStructure?
}
