package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.dsp.BeatGrid
import com.hereliesaz.sirmatchalot.dsp.PointOfInterest
import kotlin.math.roundToInt

/**
 * Turns an ordered [MixPlan] into a [MixTimeline]: every clip given a position,
 * every handover given a length, every incoming bar line landed on an outgoing
 * one.
 *
 * Until now the running order was all there was, and each handover was improvised
 * at playback by [MixDirector] from a clock. Here the same facts — the measured
 * grids, the exit and entry candidates in [TrackStructure], the tempo ratios the
 * planner already chose — are used once, up front, to decide where things go.
 * That is what makes the result drawable, editable, savable and previewable.
 *
 * The rule is the one a DJ follows by ear: the outgoing track leaves on a bar
 * line, the incoming track enters on a bar line, and the two sit a whole number
 * of bars apart. Because both are played at the same tempo (the planner's ratios
 * chain it), every bar after that stays together without further correction.
 */
object TimelineLayout {

    /** Bars a blend runs for when nothing in the incoming track says otherwise. */
    const val DEFAULT_TRANSITION_BARS = 8

    /** Shortest and longest blends worth planning. */
    const val MIN_TRANSITION_BARS = 4
    const val MAX_TRANSITION_BARS = 16

    /** Bars of the outgoing track that must be heard before its overlap begins. */
    const val MIN_BARS_BEFORE_OVERLAP = 4

    /** Blend length when a track has no grid, so bars mean nothing. */
    const val FALLBACK_OVERLAP_SECONDS = 8.0

    /**
     * @param structures where each track's measured shape comes from. A track with
     *   none still gets a clip, built from the tempo and length on the [Track]
     *   alone, so an unanalysed structure degrades the plan instead of dropping
     *   the track.
     */
    fun layout(plan: MixPlan, structures: TrackStructureProvider): MixTimeline {
        val clips = ArrayList<PlanClip>()
        val shapes = ArrayList<TrackStructure>()
        val transitions = ArrayList<PlanTransition>()

        for ((index, step) in plan.steps.withIndex()) {
            val shape = structures.structureFor(step.track) ?: TrackStructure.of(step.track)
            val ratio = step.alignment?.tempoRatio
                ?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
            val semitones = step.alignment?.semitoneShift ?: 0
            val id = "clip-$index"

            if (index == 0) {
                clips.add(clipOf(id, shape, ratio, semitones, startSeconds = 0.0, inSeconds = 0.0))
                shapes.add(shape)
                continue
            }

            val previous = clips.last()
            val previousShape = shapes.last()

            val entry = entryFor(shape)
            val bars = blendBars(shape, entry)
            val incomingBar = shape.grid?.let { it.barDuration / ratio }
            val blendSeconds = if (bars > 0 && incomingBar != null) {
                bars * incomingBar
            } else {
                FALLBACK_OVERLAP_SECONDS
            }

            val exit = exitFor(previous, previousShape, bars)
            val exitMaster = previous.toMaster(exit)

            val incomingMaster = exitMaster - blendSeconds
            val incoming = clipOf(
                id = id,
                shape = shape,
                ratio = ratio,
                semitones = semitones,
                startSeconds = incomingMaster - entry / ratio,
                inSeconds = entry,
            )

            clips[clips.lastIndex] = previous.copy(outSeconds = exit)
            clips.add(incoming)
            shapes.add(shape)
            transitions.add(
                PlanTransition(
                    fromClipId = previous.id,
                    toClipId = id,
                    startSeconds = incomingMaster,
                    lengthBars = if (incomingBar != null) bars else 0,
                    lengthSeconds = blendSeconds,
                    matchScore = step.transition?.overallScore,
                ),
            )
        }

        return MixTimeline(clips, transitions)
    }

    private fun clipOf(
        id: String,
        shape: TrackStructure,
        ratio: Double,
        semitones: Int,
        startSeconds: Double,
        inSeconds: Double,
    ) = PlanClip(
        id = id,
        trackId = shape.trackId,
        durationSeconds = shape.durationSeconds,
        bpm = shape.bpm,
        firstBeatSeconds = shape.firstBeatSeconds,
        downbeatOffset = shape.downbeatOffset,
        startSeconds = startSeconds,
        inSeconds = inSeconds,
        outSeconds = shape.durationSeconds,
        ratio = ratio,
        semitones = semitones,
        markers = shape.points.map { PlanMarker(it.timeSeconds, it.kind, it.strength) },
    )

    /**
     * File time to enter [shape] at: its best entry, put on a bar line.
     *
     * Entry zero is not automatically a bar — a track whose first beat is 300 ms
     * in has its first bar line there — so even the "start of the track" entry is
     * moved to the first bar line at or after it.
     */
    private fun entryFor(shape: TrackStructure): Double {
        val best = shape.entries().firstOrNull() ?: 0.0
        val grid = shape.grid ?: return best
        if (best > 0.0) return best
        var bar = grid.nearestBarTime(0.0)
        while (bar < 0.0) bar += grid.barDuration
        return bar
    }

    /**
     * Bars the blend runs for.
     *
     * When the incoming track has a drop shortly after its entry, the blend is
     * sized so the drop lands exactly as the outgoing track leaves — the
     * arrangement entries() is built to produce — otherwise the default.
     */
    private fun blendBars(shape: TrackStructure, entry: Double): Int {
        val grid = shape.grid ?: return 0
        val drop = shape.firstPoint(PointOfInterest.Kind.DROP, after = entry) ?: return DEFAULT_TRANSITION_BARS
        val bars = ((drop.timeSeconds - entry) / grid.barDuration).roundToInt()
        return if (bars in MIN_TRANSITION_BARS..MAX_TRANSITION_BARS) bars else DEFAULT_TRANSITION_BARS
    }

    /**
     * File time at which [previous] leaves, on a bar line.
     *
     * Walks the candidates best first and takes the first that still leaves the
     * outgoing track a few bars of its own before the overlap begins, so a blend
     * never starts before the track it is blending out of has been heard.
     */
    private fun exitFor(previous: PlanClip, shape: TrackStructure, blendBars: Int): Double {
        val grid = previous.grid
        val barFile = grid?.barDuration
        val after = if (barFile != null) {
            previous.inSeconds + (blendBars + MIN_BARS_BEFORE_OVERLAP) * barFile
        } else {
            previous.inSeconds
        }
        for (candidate in shape.exits(after)) {
            val snapped = if (grid != null) barAtOrBeforeEnd(grid, candidate.atSeconds, shape.durationSeconds) else candidate.atSeconds
            if (snapped > after && snapped <= shape.durationSeconds) return snapped
        }
        // Even the floor goes on a bar line: the end of the file rarely is one,
        // and leaving on the raw end would put the outgoing track's bars half a
        // bar out of step with the incoming track's.
        return if (grid != null) barAtOrBeforeEnd(grid, shape.durationSeconds, shape.durationSeconds)
        else shape.durationSeconds
    }

    /** The bar line nearest [seconds] that does not run past [end]. */
    private fun barAtOrBeforeEnd(grid: BeatGrid, seconds: Double, end: Double): Double {
        var bar = grid.nearestBarTime(seconds)
        while (bar > end) bar -= grid.barDuration
        return bar
    }
}
