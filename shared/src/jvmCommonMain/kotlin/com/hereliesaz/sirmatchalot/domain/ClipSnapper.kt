package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.dsp.BeatGrid
import kotlin.math.roundToInt

/**
 * What is known about a clip's music, **in the clip's own time as it sits on the
 * deck** — that is, after any tempo conform, not the original file's.
 *
 * A clip stretched from 140 to 120 BPM has its first beat, its bars and its
 * drops all moved by the same factor. Describing it in deck time is what lets
 * [ClipSnapper] compare two clips without remembering what was done to either.
 */
data class ClipMusic(
    val bpm: Double?,
    val firstBeatSeconds: Double = 0.0,
    val beatsPerBar: Int = BeatGrid.DEFAULT_BEATS_PER_BAR,
    val downbeatOffset: Int = 0,
    val markers: List<PlanMarker> = emptyList(),
) {
    /**
     * Seconds from [positionSeconds] (in the clip's own time) to its next bar
     * line, or 0 when it is already on one. Null when no tempo was measured.
     *
     * What a starting deck needs to know about the deck it is joining: bring the
     * incoming bar line in exactly this long from now and the two sets of bars
     * land together, wherever in its bar the outgoing track happens to be.
     */
    fun secondsToNextBar(positionSeconds: Double): Double? {
        val perMinute = bpm?.takeIf { it > 0.0 } ?: return null
        val beat = 60.0 / perMinute
        val bar = beat * beatsPerBar
        val firstBar = firstBeatSeconds + downbeatOffset * beat
        val into = ((positionSeconds - firstBar) % bar + bar) % bar
        return if (into < BAR_EPSILON || bar - into < BAR_EPSILON) 0.0 else bar - into
    }

    /**
     * The beat nearest [positionSeconds] (in the clip's own time), or the position
     * unchanged when no tempo was measured. What a cue is quantised to.
     */
    fun nearestBeat(positionSeconds: Double): Double {
        val perMinute = bpm?.takeIf { it > 0.0 } ?: return positionSeconds
        val beat = 60.0 / perMinute
        val k = Math.round((positionSeconds - firstBeatSeconds) / beat)
        return firstBeatSeconds + k * beat
    }

    /**
     * [positionSeconds] if it is already on a bar line (within
     * [toleranceSeconds]), otherwise the next bar line after it. Unchanged when
     * no tempo was measured.
     *
     * An entry point that is not on a bar cannot be lined up with anything: the
     * start of a file is only a bar line if the music happens to begin on one.
     */
    fun barAtOrAfter(positionSeconds: Double, toleranceSeconds: Double = 0.005): Double {
        val wait = secondsToNextBar(positionSeconds) ?: return positionSeconds
        val perMinute = bpm ?: return positionSeconds
        val bar = 60.0 / perMinute * beatsPerBar
        return if (wait < toleranceSeconds || bar - wait < toleranceSeconds) positionSeconds
        else positionSeconds + wait
    }

    companion object {
        private const val BAR_EPSILON = 1e-9

        /**
         * The music of a track after it was stretched to be [scale] times longer.
         *
         * @param bpm the track's measured tempo, null when none was found.
         * @param scale played duration over original duration: 140 BPM conformed
         *   to 120 is 140/120. 1.0 for a clip that was not stretched.
         */
        fun conformed(
            bpm: Double?,
            firstBeatSeconds: Double,
            downbeatOffset: Int,
            markers: List<PlanMarker>,
            scale: Double,
        ): ClipMusic {
            val s = if (scale > 0.0 && scale.isFinite()) scale else 1.0
            return ClipMusic(
                bpm = bpm?.takeIf { it > 0.0 }?.let { it / s },
                firstBeatSeconds = firstBeatSeconds * s,
                downbeatOffset = BeatGrid.sanitiseDownbeat(downbeatOffset),
                markers = markers.map { it.copy(fileSeconds = it.fileSeconds * s) },
            )
        }
    }
}

/** A clip as the deck has it: where it sits, how long it is, what its music is. */
data class PlacedClip(
    val id: String,
    val startFrame: Int,
    val lengthFrames: Int,
    val music: ClipMusic,
)

/**
 * Where a snapped placement landed.
 *
 * @param snap the line that caught it, null when nothing did.
 * @param overlapsOthers whether the clip, where it ended up, shares any time with
 *   another. A clip over empty timeline has nothing to snap to, which is not the
 *   same as having snapped to nothing.
 */
data class SnappedPlacement(
    val startFrame: Int,
    val snap: SnapResult?,
    val overlapsOthers: Boolean,
)

/**
 * Snapping a clip being dropped or dragged onto the clips already on the decks.
 *
 * The bridge between the engine's frames and [MixTimeline]'s seconds: a clip is
 * moved against every other clip on **both** decks, so a drop on Deck B lands its
 * bars on Deck A's, rather than on a single grid taken from one reference track.
 */
object ClipSnapper {

    /**
     * Snaps [mover], placed at its current [PlacedClip.startFrame], onto [others].
     *
     * @param radiusBeats strong-tier reach in beats; zero or less leaves the
     *   clip exactly where it is.
     */
    fun snap(
        mover: PlacedClip,
        others: List<PlacedClip>,
        sampleRate: Int,
        radiusBeats: Double = GridSnap.DEFAULT_RADIUS_BEATS,
    ): SnappedPlacement {
        if (sampleRate <= 0) return SnappedPlacement(mover.startFrame, null, false)

        val timeline = MixTimeline(clips = (others + mover).map { it.toPlanClip(sampleRate) })
        val moved = timeline.snapMove(
            clipId = mover.id,
            proposedStartSeconds = mover.startFrame.toDouble() / sampleRate,
            radiusBeats = radiusBeats,
        )

        val frame = (moved.startSeconds * sampleRate).roundToInt().coerceAtLeast(0)
        val end = frame + mover.lengthFrames
        val overlaps = others.any { frame < it.startFrame + it.lengthFrames && end > it.startFrame }
        return SnappedPlacement(frame, moved.snap, overlaps)
    }

    private fun PlacedClip.toPlanClip(sampleRate: Int) = PlanClip(
        id = id,
        trackId = id,
        durationSeconds = lengthFrames.toDouble() / sampleRate,
        bpm = music.bpm,
        firstBeatSeconds = music.firstBeatSeconds,
        beatsPerBar = music.beatsPerBar,
        downbeatOffset = music.downbeatOffset,
        startSeconds = startFrame.toDouble() / sampleRate,
        markers = music.markers,
    )
}
