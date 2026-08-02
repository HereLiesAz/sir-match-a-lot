package com.hereliesaz.sirmatchalot.domain

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Rounding clip placements and lengths onto a beat grid.
 *
 * Dragging a clip by hand lands it wherever the finger happened to be, which on
 * a five-minute revolution is tens of milliseconds out — enough to be audibly
 * off the beat and impossible to correct by eye. Snapping is what makes the
 * gesture usable rather than approximate.
 *
 * Pure arithmetic over frames, so all of it is tested without an audio device.
 */
object BeatSnap {

    /** Frames in one beat at [bpm]. Zero when the tempo is unusable. */
    fun framesPerBeat(bpm: Double?, sampleRate: Int): Double {
        if (bpm == null || bpm <= 0.0 || sampleRate <= 0) return 0.0
        return sampleRate * 60.0 / bpm
    }

    /**
     * Rounds [frame] to the nearest beat line.
     *
     * @param phaseFrames where the grid's first beat sits, so the grid lines up
     *   with the music rather than with frame zero. A track whose first downbeat
     *   is 300 ms in has every beat 300 ms in.
     * @param maxDistanceBeats how far a snap may move something. Beyond this the
     *   position is left alone: a drag deliberately placed between beats should
     *   not be dragged back, and with no limit every position on the timeline is
     *   within half a beat of a line, so snapping would be unconditional.
     * @return the snapped frame, never negative.
     */
    fun snapFrame(
        frame: Int,
        framesPerBeat: Double,
        phaseFrames: Double = 0.0,
        maxDistanceBeats: Double = DEFAULT_SNAP_BEATS,
    ): Int {
        if (framesPerBeat <= 0.0) return frame.coerceAtLeast(0)
        val relative = frame - phaseFrames
        val beats = relative / framesPerBeat
        val nearest = beats.roundToLong()
        val distance = abs(beats - nearest)
        if (distance > maxDistanceBeats) return frame.coerceAtLeast(0)
        val snapped = (nearest * framesPerBeat + phaseFrames).roundToLong()
        return snapped.coerceAtLeast(0L).toInt()
    }

    /**
     * Rounds a length to a whole number of beats.
     *
     * A clip stretched by pinch should come out an exact musical length — four
     * bars, not 3.97 — because the whole point of stretching it is to make it
     * line up with something else.
     *
     * @param minBeats floor, so a clip cannot be squeezed to nothing.
     */
    fun snapLength(frames: Int, framesPerBeat: Double, minBeats: Int = 1): Int {
        if (framesPerBeat <= 0.0) return frames.coerceAtLeast(1)
        val beats = (frames / framesPerBeat).roundToInt().coerceAtLeast(minBeats)
        return (beats * framesPerBeat).roundToLong().coerceAtLeast(1L).toInt()
    }

    /**
     * The stretch ratio that turns [currentFrames] into the nearest whole number
     * of beats, given a requested [ratio].
     *
     * Snapping the *ratio* rather than the resulting length keeps the caller's
     * single source of truth — the pristine buffer and one ratio — instead of
     * accumulating a new length each time the pinch is adjusted.
     *
     * @return [ratio] unchanged when there is no usable grid — stretch freely,
     *   rather than 1.0, which would mean ignoring the pinch entirely.
     *   tempo stretches freely rather than being snapped to a guess.
     */
    fun snapRatio(
        currentFrames: Int,
        ratio: Double,
        framesPerBeat: Double,
        minBeats: Int = 1,
    ): Double {
        if (framesPerBeat <= 0.0 || currentFrames <= 0) return ratio
        val target = currentFrames * ratio
        val snapped = snapLength(target.roundToInt().coerceAtLeast(1), framesPerBeat, minBeats)
        return snapped.toDouble() / currentFrames
    }

    /** Half a beat: every position is within this of some line, so it is the widest useful window. */
    const val DEFAULT_SNAP_BEATS = 0.5
}
