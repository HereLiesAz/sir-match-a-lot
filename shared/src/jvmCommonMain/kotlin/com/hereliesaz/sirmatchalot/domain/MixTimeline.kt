package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.dsp.BeatGrid
import com.hereliesaz.sirmatchalot.dsp.PointOfInterest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A structural landmark on a clip, in the **file's own time**.
 *
 * Stored unscaled and converted through [PlanClip.toMaster] on the way out, so a
 * drop measured at 90 seconds in a clip played 17% slower lands at 105 seconds
 * on the timeline instead of staying drawn at 90.
 */
data class PlanMarker(
    val fileSeconds: Double,
    val kind: PointOfInterest.Kind,
    val strength: Float = 1f,
)

/**
 * One track placed on the master timeline.
 *
 * The master timeline is **played-back** time: seconds as the listener hears them,
 * after any tempo conform. Everything measured on the file stays in file time and
 * reaches the timeline through [toMaster], so the two clocks are never mixed.
 *
 * @param startSeconds the master time at which the file's own time zero would
 *   play. Often negative or before the clip is heard: a clip entered at 40 s into
 *   its file has its zero well before it is audible. Positioning by where the
 *   file's *beats* fall, rather than by where its audible part begins, is what
 *   makes alignment arithmetic exact.
 * @param inSeconds file time at which playback begins (the entry point).
 * @param outSeconds file time at which playback ends (the exit point).
 * @param ratio playback rate: above 1 is faster. A 120 BPM file at ratio 1.05
 *   plays at 126.
 * @param semitones pitch shift applied, carried so the plan records it.
 * @param bpm measured tempo of the file, null when none was found.
 */
data class PlanClip(
    val id: String,
    val trackId: String,
    val durationSeconds: Double,
    val bpm: Double?,
    val firstBeatSeconds: Double = 0.0,
    val beatsPerBar: Int = BeatGrid.DEFAULT_BEATS_PER_BAR,
    val downbeatOffset: Int = 0,
    val startSeconds: Double = 0.0,
    val inSeconds: Double = 0.0,
    val outSeconds: Double = durationSeconds,
    val ratio: Double = 1.0,
    val semitones: Int = 0,
    val markers: List<PlanMarker> = emptyList(),
) {
    init {
        require(ratio > 0.0 && ratio.isFinite()) { "ratio must be positive" }
    }

    /** The grid in file time, or null when no tempo was measured. */
    val grid: BeatGrid? = bpm
        ?.takeIf { it > 0.0 }
        ?.let {
            BeatGrid(
                bpm = it,
                firstBeatSeconds = firstBeatSeconds,
                beatsPerBar = beatsPerBar,
                downbeatOffset = BeatGrid.sanitiseDownbeat(downbeatOffset, beatsPerBar),
            )
        }

    /** Seconds per beat as heard, or null with no grid. */
    val playedBeatSeconds: Double? get() = grid?.let { it.beatDuration / ratio }

    /** Seconds per bar as heard, or null with no grid. */
    val playedBarSeconds: Double? get() = grid?.let { it.barDuration / ratio }

    /** Tempo as heard. */
    val playedBpm: Double? get() = bpm?.let { it * ratio }

    fun toMaster(fileSeconds: Double): Double = startSeconds + fileSeconds / ratio

    fun toFile(masterSeconds: Double): Double = (masterSeconds - startSeconds) * ratio

    /** Master time at which this clip becomes audible. */
    val masterIn: Double get() = toMaster(inSeconds)

    /** Master time at which this clip stops. */
    val masterOut: Double get() = toMaster(outSeconds)

    fun movedTo(newStartSeconds: Double): PlanClip = copy(startSeconds = newStartSeconds)

    /**
     * Beats and markers falling inside `[fromMaster, toMaster]`, in master time,
     * limited to the part of the clip that is actually heard.
     */
    fun snapPoints(fromMaster: Double, toMaster: Double): List<SnapPoint> {
        val lo = max(fromMaster, masterIn)
        val hi = min(toMaster, masterOut)
        if (hi < lo) return emptyList()

        val points = ArrayList<SnapPoint>()
        grid?.let { g ->
            val fileLo = toFile(lo)
            val fileHi = toFile(hi)
            var index = ceil((fileLo - g.firstBeatSeconds) / g.beatDuration - EPSILON).toInt()
            while (true) {
                val fileTime = g.beatTime(index)
                if (fileTime > fileHi + EPSILON) break
                val position = ((index - g.downbeatOffset) % g.beatsPerBar + g.beatsPerBar) % g.beatsPerBar
                points.add(SnapPoint.beat(toMaster(fileTime), position, g.beatsPerBar))
                index++
            }
        }
        for (marker in markers) {
            val t = toMaster(marker.fileSeconds)
            if (t in lo..hi) points.add(SnapPoint.marker(t, marker.kind))
        }
        return points
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}

/**
 * The handover between two adjacent clips.
 *
 * @param startSeconds master time at which the incoming clip becomes audible.
 * @param lengthBars how many bars the blend runs for.
 * @param lengthSeconds the same, in master seconds.
 * @param matchScore the harmonic/tempo compatibility that justified the pairing,
 *   carried so the plan can say why.
 */
data class PlanTransition(
    val fromClipId: String,
    val toClipId: String,
    val startSeconds: Double,
    val lengthBars: Int,
    val lengthSeconds: Double,
    val matchScore: Int? = null,
) {
    val endSeconds: Double get() = startSeconds + lengthSeconds
}

/** A snapped move: where the clip ended up and which line caught it, if any. */
data class MoveResult(val startSeconds: Double, val snap: SnapResult?)

/**
 * A mix as a thing with positions, rather than a list with an order.
 *
 * This is what was missing: the previous `MixPlan` said *which tracks* and
 * nothing about *where*, so the app had no place to ask "does bar one of this
 * land on bar one of that" and no object a screen could draw, edit or save.
 *
 * Immutable: an edit produces a new timeline.
 */
data class MixTimeline(
    val clips: List<PlanClip>,
    val transitions: List<PlanTransition> = emptyList(),
) {
    /** When the last clip stops. */
    val endSeconds: Double get() = clips.maxOfOrNull { it.masterOut } ?: 0.0

    fun clip(id: String): PlanClip? = clips.firstOrNull { it.id == id }

    fun withClip(updated: PlanClip): MixTimeline =
        copy(clips = clips.map { if (it.id == updated.id) updated else it })

    /** The clips audible at [masterSeconds], earliest first. */
    fun clipsAt(masterSeconds: Double): List<PlanClip> =
        clips.filter { masterSeconds >= it.masterIn && masterSeconds < it.masterOut }
            .sortedBy { it.masterIn }

    /**
     * Moves a clip to [proposedStartSeconds], snapping it onto the beats and
     * markers of whatever it overlaps.
     *
     * Only clips that overlap in time attract each other: a clip dragged across
     * empty timeline is left where it is put, and only starts to feel the grid
     * once it is on top of something it could be matched with.
     *
     * @param radiusBeats strong-tier reach in beats; medium and weak are shorter.
     *   Zero or less moves freely — what a held "no snap" modifier passes.
     */
    fun snapMove(
        clipId: String,
        proposedStartSeconds: Double,
        radiusBeats: Double = GridSnap.DEFAULT_RADIUS_BEATS,
    ): MoveResult {
        val clip = clip(clipId) ?: return MoveResult(proposedStartSeconds, null)
        val moved = clip.movedTo(proposedStartSeconds)
        val others = clips.filter { it.id != clipId }

        val beatSeconds = moved.playedBeatSeconds
            ?: others.firstNotNullOfOrNull { it.playedBeatSeconds }
            ?: return MoveResult(proposedStartSeconds, null)
        val radius = radiusBeats * beatSeconds
        if (radius <= 0.0) return MoveResult(proposedStartSeconds, null)

        val from = moved.masterIn - radius
        val to = moved.masterOut + radius
        val moverPoints = moved.snapPoints(from, to)
        val targets = others.flatMap { it.snapPoints(from, to) }

        val snap = GridSnap.snap(moverPoints, targets, radius)
        return MoveResult(proposedStartSeconds + (snap?.shiftSeconds ?: 0.0), snap)
    }
}
