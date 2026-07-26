package com.hereliesaz.sirmatchalot.ui.platter

import com.hereliesaz.sirmatchalot.dsp.PeakEnvelope
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The mapping between position on the platter and position in the music.
 *
 * **Angle is time.** One full revolution is one deck cycle, and this is the only
 * place that correspondence is defined — the playhead, the waveform lookup,
 * scrubbing, cue markers and the energy ring all go through these functions.
 *
 * The previous implementation had no such mapping. The playhead advanced at a
 * hardcoded `2*PI / 10f` radians per second regardless of track length, while a
 * separately computed `platterDurationSeconds` was never used, and scrubbing
 * converted angle to time with its own unrelated `* 10f`. The playhead's
 * position therefore corresponded to nothing.
 *
 * Zero is at 12 o'clock and time runs clockwise, matching the stopwatch-hand
 * behaviour asked for.
 */
object PlatterGeometry {

    /** Screen-space angle, in radians, of 12 o'clock. */
    const val TWELVE_OCLOCK = -Math.PI.toFloat() / 2f

    private const val TWO_PI = (2.0 * Math.PI).toFloat()

    /** Fraction of one revolution, 0..1, for [seconds] into a [cycleSeconds] cycle. */
    fun fractionForTime(seconds: Double, cycleSeconds: Double): Float {
        if (cycleSeconds <= 0.0) return 0f
        var fraction = (seconds / cycleSeconds) % 1.0
        if (fraction < 0) fraction += 1.0
        return fraction.toFloat()
    }

    /** Time in seconds for a [fraction] of one revolution. */
    fun timeForFraction(fraction: Float, cycleSeconds: Double): Double =
        wrapFraction(fraction) * cycleSeconds

    /**
     * Screen-space angle in radians for a [fraction] of one revolution.
     *
     * Suitable for `cos`/`sin` directly: 0 is at 12 o'clock and the angle
     * increases clockwise, because screen y grows downward.
     */
    fun screenAngleForFraction(fraction: Float): Float =
        TWELVE_OCLOCK + wrapFraction(fraction) * TWO_PI

    /**
     * Inverse of [screenAngleForFraction]: which fraction of a revolution a touch
     * at [screenAngle] corresponds to.
     */
    fun fractionForScreenAngle(screenAngle: Float): Float {
        var normalised = (screenAngle - TWELVE_OCLOCK) % TWO_PI
        if (normalised < 0f) normalised += TWO_PI
        return normalised / TWO_PI
    }

    /** Screen angle of a touch at ([x], [y]) relative to centre ([cx], [cy]). */
    fun screenAngleOf(x: Float, y: Float, cx: Float, cy: Float): Float = atan2(y - cy, x - cx)

    /** Fraction of a revolution a touch at ([x], [y]) points at. */
    fun fractionOf(x: Float, y: Float, cx: Float, cy: Float): Float =
        fractionForScreenAngle(screenAngleOf(x, y, cx, cy))

    /** Distance from centre of a touch at ([x], [y]). */
    fun radiusOf(x: Float, y: Float, cx: Float, cy: Float): Float = hypot(x - cx, y - cy)

    /** Point on the circle of radius [radius] at [fraction] of a revolution. */
    fun pointAt(fraction: Float, radius: Float, cx: Float, cy: Float): Pair<Float, Float> {
        val angle = screenAngleForFraction(fraction)
        return (cx + cos(angle) * radius) to (cy + sin(angle) * radius)
    }

    /**
     * Which deck a touch belongs to, by which side of the base circle it is on.
     *
     * Deck A occupies the outside, Deck B the inside — two rings, never
     * concentric per track.
     */
    fun deckAt(radius: Float, baseRadius: Float): Deck =
        if (radius >= baseRadius) Deck.A else Deck.B

    enum class Deck { A, B }

    /**
     * Half-width of the band around the base radius that counts as "on the
     * circle", as a multiple of the base radius.
     *
     * A drag has to be able to leave the platter to delete a clip, so there must
     * be a definite outside. Without a band, every point on screen belongs to
     * one deck or the other and nothing can ever be dragged off.
     */
    const val RING_BAND = 0.85f

    /** True when [radius] is close enough to the rings to count as on the platter. */
    fun isOnRing(radius: Float, baseRadius: Float): Boolean {
        if (baseRadius <= 0f) return false
        val ratio = radius / baseRadius
        return ratio in (1f - RING_BAND)..(1f + RING_BAND)
    }

    /**
     * Which clip covers [fraction], or null.
     *
     * Spans wrap: a clip starting at 0.9 with a span of 0.2 covers 0.95 and
     * 0.05 alike, because the timeline is a circle and a clip that runs past the
     * top does not stop existing there.
     *
     * Later clips win where they overlap, matching the draw order.
     */
    fun clipAt(
        clips: List<com.hereliesaz.sirmatchalot.ui.platter.PlatterClip>,
        fraction: Float,
    ): com.hereliesaz.sirmatchalot.ui.platter.PlatterClip? {
        val target = wrapFraction(fraction)
        return clips.lastOrNull { clip ->
            if (clip.spanFraction <= 0f) {
                false
            } else if (clip.spanFraction >= 1f) {
                true
            } else {
                val start = wrapFraction(clip.startFraction)
                val offset = wrapFraction(target - start)
                offset < clip.spanFraction
            }
        }
    }

    /**
     * Shapes a raw peak into a drawn ray length.
     *
     * The reference images this is built against share one property: a few
     * excursions reach four or five times the median length, while the body of
     * the waveform still has visible detail. A linear mapping cannot do both —
     * real music sits at a low average amplitude, so linear scaling yields the
     * near-flat band the previous implementation drew, and simply multiplying by
     * a larger constant clips every peak to the same maximum.
     *
     * The primary reference is the dense monochrome starburst: a short, fairly
     * even body of rays with perhaps a dozen shooting out many times further.
     * The drama there comes from *contrast*, not from raising the whole ring — so
     * the gamma is only mildly below 1, lifting quiet detail just enough to be
     * visible, while a large overshoot above the nominal maximum lets genuine
     * transients careen well past the body. [levelScale] then multiplies the
     * whole thing by the live metered output, so the ring breathes with the audio
     * instead of being a static envelope animated by a free-running oscillator.
     *
     * @param peak peak-to-peak envelope value, 0..2.
     * @param maxHeight pixels a nominal full-scale peak reaches.
     * @param levelScale live output level, typically 0..1.
     * @param gamma shaping exponent; below 1 lifts quiet detail.
     * @param peakOvershoot how far the loudest material may exceed [maxHeight].
     */
    fun exaggeratedHeight(
        peak: Float,
        maxHeight: Float,
        levelScale: Float = 1f,
        gamma: Float = 0.75f,
        peakOvershoot: Float = 2.4f,
    ): Float {
        if (peak <= 0f || maxHeight <= 0f) return 0f
        // heightAtFraction is peak-to-peak over [-1, 1], so halve to an excursion.
        val normalised = (peak * 0.5f).coerceIn(0f, 1f)
        val shaped = Math.pow(normalised.toDouble(), gamma.toDouble()).toFloat()
        return shaped * maxHeight * peakOvershoot * levelScale.coerceAtLeast(0f)
    }

    /**
     * Ray length in pixels at [fraction] of a revolution.
     *
     * @param envelope the track's peak envelope.
     * @param clipStartFraction where this clip begins on the revolution.
     * @param clipSpanFraction how much of the revolution it occupies.
     * @param maxHeight pixel height a nominal full-scale peak reaches.
     * @param levelScale live output level.
     * @return 0 when [fraction] falls outside the clip.
     */
    fun waveformHeight(
        envelope: PeakEnvelope,
        fraction: Float,
        clipStartFraction: Float,
        clipSpanFraction: Float,
        maxHeight: Float,
        levelScale: Float = 1f,
    ): Float {
        if (clipSpanFraction <= 0f || envelope.size == 0) return 0f
        var offset = (fraction - clipStartFraction) % 1f
        if (offset < 0f) offset += 1f
        if (offset > clipSpanFraction) return 0f
        val within = (offset / clipSpanFraction).coerceIn(0f, 1f)
        return exaggeratedHeight(envelope.heightAtFraction(within), maxHeight, levelScale)
    }

    /**
     * How many discrete radial rays to draw around a ring of [radius].
     *
     * The waveform is drawn as separate radial segments — one per bucket, from
     * the ring outward — not as a connected path through peak and valley points.
     * That connected form is what made the previous rendering read as a zigzag
     * ribbon rather than a waveform.
     *
     * Count follows the circumference so ray density stays constant as the
     * platter is zoomed, bounded so a large screen does not draw unboundedly many.
     * The default 2 px spacing targets the dense hairline look of the primary
     * reference — several hundred rays on a phone-sized platter — rather than the
     * coarser forty-bar variant.
     *
     * @param spacingPx desired centre-to-centre spacing between rays.
     */
    fun rayCount(radius: Float, spacingPx: Float = 2f, maxRays: Int = 2048): Int {
        if (radius <= 0f || spacingPx <= 0f) return 0
        val circumference = TWO_PI * radius
        return (circumference / spacingPx).toInt().coerceIn(1, maxRays)
    }

    /**
     * Half-length of the playhead slash at a point where the outward and inward
     * waveforms currently reach [outwardHeight] and [inwardHeight].
     *
     * The requirement is a slash "twice the width of both decks' waveforms
     * combined", centred on the ring — so it bounces over the hills and valleys
     * as it passes them. Total length is `2 * (outward + inward)`, hence half of
     * that either side of the ring.
     *
     * When there is no waveform at all this returns 0, and the caller draws a
     * glowing dot instead.
     */
    fun playheadHalfLength(outwardHeight: Float, inwardHeight: Float): Float =
        outwardHeight + inwardHeight

    /** True when the playhead should render as a dot rather than a slash. */
    fun playheadIsDot(outwardHeight: Float, inwardHeight: Float): Boolean =
        playheadHalfLength(outwardHeight, inwardHeight) <= 0.5f

    private fun wrapFraction(fraction: Float): Float {
        var f = fraction % 1f
        if (f < 0f) f += 1f
        return f
    }
}
