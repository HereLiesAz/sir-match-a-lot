package com.hereliesaz.sirmatchalot.ui.platter

import com.hereliesaz.sirmatchalot.dsp.PeakEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PlatterGeometryTest {

    @Test
    fun `angle is time - one revolution is one deck cycle`() {
        // The property the previous implementation lacked entirely: it advanced
        // the playhead at a hardcoded 2*PI/10 rad/s regardless of track length.
        val cycle = 180.0
        assertEquals(0f, PlatterGeometry.fractionForTime(0.0, cycle), 1e-6f)
        assertEquals(0.25f, PlatterGeometry.fractionForTime(45.0, cycle), 1e-6f)
        assertEquals(0.5f, PlatterGeometry.fractionForTime(90.0, cycle), 1e-6f)
    }

    @Test
    fun `time and fraction round trip`() {
        val cycle = 137.0
        for (seconds in listOf(0.0, 12.5, 68.0, 136.9)) {
            val fraction = PlatterGeometry.fractionForTime(seconds, cycle)
            assertEquals(seconds, PlatterGeometry.timeForFraction(fraction, cycle), 1e-4)
        }
    }

    @Test
    fun `time wraps past the end of a revolution`() {
        val cycle = 100.0
        assertEquals(0.1f, PlatterGeometry.fractionForTime(110.0, cycle), 1e-6f)
        assertEquals(0.9f, PlatterGeometry.fractionForTime(-10.0, cycle), 1e-6f)
    }

    @Test
    fun `an empty cycle does not divide by zero`() {
        assertEquals(0f, PlatterGeometry.fractionForTime(10.0, 0.0), 0f)
        assertEquals(0.0, PlatterGeometry.timeForFraction(0.5f, 0.0), 0.0)
    }

    @Test
    fun `zero is twelve o'clock and time runs clockwise`() {
        val cx = 100f
        val cy = 100f
        val r = 50f

        val (topX, topY) = PlatterGeometry.pointAt(0f, r, cx, cy)
        assertEquals("12:00 is directly above centre", cx, topX, 1e-3f)
        assertTrue("12:00 must be above centre", topY < cy)

        // A quarter turn later should be to the right — clockwise.
        val (rightX, rightY) = PlatterGeometry.pointAt(0.25f, r, cx, cy)
        assertTrue("3:00 must be right of centre", rightX > cx)
        assertEquals(cy, rightY, 1e-3f)

        val (bottomX, bottomY) = PlatterGeometry.pointAt(0.5f, r, cx, cy)
        assertEquals(cx, bottomX, 1e-3f)
        assertTrue("6:00 must be below centre", bottomY > cy)
    }

    @Test
    fun `screen angle and fraction round trip`() {
        var fraction = 0f
        while (fraction < 1f) {
            val angle = PlatterGeometry.screenAngleForFraction(fraction)
            assertEquals(fraction, PlatterGeometry.fractionForScreenAngle(angle), 1e-5f)
            fraction += 0.05f
        }
    }

    @Test
    fun `a touch maps back to the fraction it points at`() {
        val cx = 0f
        val cy = 0f
        for (expected in listOf(0f, 0.125f, 0.25f, 0.5f, 0.75f, 0.9f)) {
            val angle = PlatterGeometry.screenAngleForFraction(expected)
            val x = cx + cos(angle) * 80f
            val y = cy + sin(angle) * 80f
            assertEquals(expected, PlatterGeometry.fractionOf(x, y, cx, cy), 1e-4f)
        }
    }

    @Test
    fun `deck A is outside the ring and deck B inside`() {
        assertEquals(PlatterGeometry.Deck.A, PlatterGeometry.deckAt(120f, 100f))
        assertEquals(PlatterGeometry.Deck.B, PlatterGeometry.deckAt(80f, 100f))
        // Exactly on the ring belongs to A.
        assertEquals(PlatterGeometry.Deck.A, PlatterGeometry.deckAt(100f, 100f))
    }

    @Test
    fun `radius is measured from the centre`() {
        assertEquals(5f, PlatterGeometry.radiusOf(3f, 4f, 0f, 0f), 1e-5f)
        assertEquals(0f, PlatterGeometry.radiusOf(7f, 7f, 7f, 7f), 1e-5f)
    }

    // --- Height shaping, per docs/PLATTER_VISUAL.md ---

    @Test
    fun `silence draws nothing`() {
        assertEquals(0f, PlatterGeometry.exaggeratedHeight(0f, 200f), 0f)
        assertEquals(0f, PlatterGeometry.exaggeratedHeight(0.5f, 0f), 0f)
    }

    @Test
    fun `height is monotonic in the peak`() {
        var previous = -1f
        var peak = 0f
        while (peak <= 2f) {
            val height = PlatterGeometry.exaggeratedHeight(peak, 200f)
            assertTrue("not monotonic at $peak", height >= previous)
            previous = height
            peak += 0.02f
        }
    }

    @Test
    fun `quiet detail is lifted into view rather than left flat`() {
        // The complaint was that the waveform "barely jiggles". At a realistic
        // average amplitude, a linear mapping would draw ~5% of full height; the
        // shaped mapping must be substantially more visible than that.
        val maxHeight = 200f
        val quiet = PlatterGeometry.exaggeratedHeight(0.1f, maxHeight)
        val linear = 0.05f * maxHeight
        assertTrue("shaped $quiet should exceed linear $linear", quiet > linear * 2f)
    }

    @Test
    fun `loud peaks overshoot the nominal maximum so they careen outward`() {
        val maxHeight = 200f
        val loudest = PlatterGeometry.exaggeratedHeight(2f, maxHeight)
        assertTrue("full-scale peak $loudest should exceed $maxHeight", loudest > maxHeight)
    }

    @Test
    fun `peaks stand well clear of the body`() {
        // References show peaks around four to five times the median length.
        val maxHeight = 200f
        val body = PlatterGeometry.exaggeratedHeight(0.08f, maxHeight)
        val spike = PlatterGeometry.exaggeratedHeight(1.6f, maxHeight)
        assertTrue("spike $spike should tower over body $body", spike > body * 3f)
    }

    @Test
    fun `live level scales the whole ring`() {
        val full = PlatterGeometry.exaggeratedHeight(1f, 200f, levelScale = 1f)
        val half = PlatterGeometry.exaggeratedHeight(1f, 200f, levelScale = 0.5f)
        assertEquals(full * 0.5f, half, 1e-4f)
        assertEquals(0f, PlatterGeometry.exaggeratedHeight(1f, 200f, levelScale = 0f), 0f)
        // A negative level must not invert the waveform.
        assertEquals(0f, PlatterGeometry.exaggeratedHeight(1f, 200f, levelScale = -1f), 0f)
    }

    @Test
    fun `ray count follows circumference and is bounded`() {
        val small = PlatterGeometry.rayCount(50f)
        val large = PlatterGeometry.rayCount(400f)
        assertTrue("density should hold as radius grows: $small vs $large", large > small)
        assertEquals(0, PlatterGeometry.rayCount(0f))
        assertTrue("must be bounded", PlatterGeometry.rayCount(100_000f) <= 2048)
    }

    // --- Waveform lookup ---

    private fun envelopeOf(vararg values: Float) = PeakEnvelope(
        minima = FloatArray(values.size) { -values[it] / 2f },
        maxima = FloatArray(values.size) { values[it] / 2f },
    )

    @Test
    fun `waveform is silent outside its clip's span`() {
        val envelope = envelopeOf(1f, 1f, 1f, 1f)
        // Clip occupies the first quarter of the revolution.
        assertTrue(PlatterGeometry.waveformHeight(envelope, 0.1f, 0f, 0.25f, 200f) > 0f)
        assertEquals(0f, PlatterGeometry.waveformHeight(envelope, 0.6f, 0f, 0.25f, 200f), 0f)
    }

    @Test
    fun `a clip spanning the whole revolution sounds everywhere`() {
        // One sample alone on a deck circles the entire platter.
        val envelope = envelopeOf(1f, 1f, 1f, 1f)
        var fraction = 0f
        while (fraction < 1f) {
            assertTrue(
                "silent at $fraction",
                PlatterGeometry.waveformHeight(envelope, fraction, 0f, 1f, 200f) > 0f,
            )
            fraction += 0.05f
        }
    }

    @Test
    fun `waveform lookup handles a clip that wraps past twelve o'clock`() {
        val envelope = envelopeOf(1f, 1f)
        // Starts at 0.9 and spans 0.2, so it crosses the seam.
        assertTrue(PlatterGeometry.waveformHeight(envelope, 0.95f, 0.9f, 0.2f, 200f) > 0f)
        assertTrue(PlatterGeometry.waveformHeight(envelope, 0.05f, 0.9f, 0.2f, 200f) > 0f)
        assertEquals(0f, PlatterGeometry.waveformHeight(envelope, 0.5f, 0.9f, 0.2f, 200f), 0f)
    }

    @Test
    fun `an empty envelope draws nothing`() {
        val empty = PeakEnvelope(FloatArray(0), FloatArray(0))
        assertEquals(0f, PlatterGeometry.waveformHeight(empty, 0.5f, 0f, 1f, 200f), 0f)
    }

    // --- Playhead ---

    @Test
    fun `playhead length tracks the combined waveform height`() {
        // "twice as long as the CURRENT combined waveform height", centred on the
        // ring, so half-length either side is outward + inward.
        assertEquals(30f, PlatterGeometry.playheadHalfLength(20f, 10f), 1e-6f)
        assertEquals(0f, PlatterGeometry.playheadHalfLength(0f, 0f), 1e-6f)
    }

    @Test
    fun `playhead bounces as the waveform changes under it`() {
        val overHill = PlatterGeometry.playheadHalfLength(80f, 40f)
        val overValley = PlatterGeometry.playheadHalfLength(5f, 2f)
        assertTrue("playhead must not be a constant size", overHill > overValley * 4f)
    }

    @Test
    fun `playhead collapses to a dot when there is no waveform`() {
        assertTrue(PlatterGeometry.playheadIsDot(0f, 0f))
        assertTrue(!PlatterGeometry.playheadIsDot(20f, 0f))
        assertTrue(!PlatterGeometry.playheadIsDot(0f, 20f))
    }

    // --- Transport button ---

    @Test
    fun `the transport button takes the middle of the platter`() {
        val base = 300f
        val preferred = 60f
        assertTrue(PlatterGeometry.isOnCentreButton(0f, base, preferred))
        assertTrue(PlatterGeometry.isOnCentreButton(59f, base, preferred))
        assertTrue(!PlatterGeometry.isOnCentreButton(61f, base, preferred))
    }

    @Test
    fun `the transport button never reaches Deck B's ring`() {
        // The platter zooms down to 0.4, and a touch target fixed in pixels
        // would grow, relative to the rings, until it swallowed taps meant for
        // the inner deck. The clip has to hold at every radius.
        val preferred = 60f
        for (base in listOf(20f, 50f, 90f, 140f, 400f)) {
            val target = PlatterGeometry.centreButtonRadius(base, preferred)
            val deckB = PlatterGeometry.ringRadius(PlatterGeometry.Deck.B, base)
            assertTrue("target $target reaches Deck B at $deckB", target < deckB)
            assertTrue(!PlatterGeometry.isOnCentreButton(deckB, base, preferred))
        }
    }

    @Test
    fun `the far corner of the screen is not on the platter`() {
        // `deckAt` answers A for any radius at or beyond the base radius, with
        // no outer bound, and tap-to-select consulted it directly. A tap in the
        // black corner of the screen selected a clip spanning the revolution,
        // and a long press there removed it. `isOnRing` is the definition of an
        // outside, and it now guards the taps as well as the drags.
        val base = PlatterGeometry.baseRadius(1080f, 1920f)
        val corner = PlatterGeometry.radiusOf(0f, 0f, 540f, 960f)
        assertTrue("corner is $corner against a base of $base", corner > base)
        assertTrue(!PlatterGeometry.isOnRing(corner, base))
        // And a point on Deck A's own ring still is.
        assertTrue(PlatterGeometry.isOnRing(base * PlatterGeometry.DECK_A_RING, base))
    }

    @Test
    fun `a platter with no size has no transport button`() {
        assertTrue(!PlatterGeometry.isOnCentreButton(0f, 0f, 60f))
    }
}
