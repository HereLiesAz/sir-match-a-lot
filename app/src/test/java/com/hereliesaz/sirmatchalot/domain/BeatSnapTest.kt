package com.hereliesaz.sirmatchalot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rounding clip placements and lengths onto the beat grid.
 *
 * A finger on a five-minute revolution is tens of milliseconds out at best,
 * which is audibly off the beat and impossible to correct by eye. Snapping is
 * what makes dragging and pinching a clip usable rather than approximate, so the
 * arithmetic is pinned here rather than left to a device.
 */
class BeatSnapTest {

    private val rate = 48_000

    @Test
    fun `frames per beat follows tempo`() {
        // 120 BPM is two beats a second, so half a second of frames per beat.
        assertEquals(24_000.0, BeatSnap.framesPerBeat(120.0, rate), 1e-9)
        assertEquals(48_000.0, BeatSnap.framesPerBeat(60.0, rate), 1e-9)
    }

    @Test
    fun `an unmeasured or nonsensical tempo yields no grid`() {
        // Guessing a grid is how the old analysis got its data.
        assertEquals(0.0, BeatSnap.framesPerBeat(null, rate), 0.0)
        assertEquals(0.0, BeatSnap.framesPerBeat(0.0, rate), 0.0)
        assertEquals(0.0, BeatSnap.framesPerBeat(-120.0, rate), 0.0)
        assertEquals(0.0, BeatSnap.framesPerBeat(120.0, 0), 0.0)
    }

    // --- Snapping a position ---

    @Test
    fun `a position near a beat is pulled onto it`() {
        val perBeat = BeatSnap.framesPerBeat(120.0, rate)
        assertEquals(24_000, BeatSnap.snapFrame(24_500, perBeat))
        assertEquals(24_000, BeatSnap.snapFrame(23_500, perBeat))
        assertEquals(48_000, BeatSnap.snapFrame(47_000, perBeat))
    }

    @Test
    fun `the grid starts where the music does, not at frame zero`() {
        // A track whose first downbeat is 300 ms in has every beat 300 ms in.
        val perBeat = BeatSnap.framesPerBeat(120.0, rate)
        val phase = 0.3 * rate
        assertEquals(14_400, BeatSnap.snapFrame(15_000, perBeat, phaseFrames = phase))
        assertEquals(38_400, BeatSnap.snapFrame(38_000, perBeat, phaseFrames = phase))
    }

    @Test
    fun `without a grid the position is left where it was put`() {
        assertEquals(12_345, BeatSnap.snapFrame(12_345, framesPerBeat = 0.0))
    }

    @Test
    fun `snapping never produces a negative frame`() {
        val perBeat = BeatSnap.framesPerBeat(120.0, rate)
        assertTrue(BeatSnap.snapFrame(100, perBeat, phaseFrames = 20_000.0) >= 0)
        assertTrue(BeatSnap.snapFrame(0, perBeat) >= 0)
    }

    @Test
    fun `a deliberate off-beat placement is respected beyond the window`() {
        // With no limit, every position is within half a beat of some line, so
        // snapping would be unconditional and a deliberate placement impossible.
        val perBeat = BeatSnap.framesPerBeat(120.0, rate)
        val offBeat = 12_000 // exactly half a beat
        assertEquals(offBeat, BeatSnap.snapFrame(offBeat, perBeat, maxDistanceBeats = 0.1))
    }

    // --- Snapping a length ---

    @Test
    fun `a length rounds to whole beats`() {
        val perBeat = BeatSnap.framesPerBeat(120.0, rate)
        assertEquals(96_000, BeatSnap.snapLength(95_000, perBeat))
        assertEquals(96_000, BeatSnap.snapLength(97_500, perBeat))
    }

    @Test
    fun `a clip cannot be squeezed below one beat`() {
        val perBeat = BeatSnap.framesPerBeat(120.0, rate)
        assertEquals(24_000, BeatSnap.snapLength(10, perBeat))
    }

    @Test
    fun `without a grid a length is left alone`() {
        assertEquals(5_000, BeatSnap.snapLength(5_000, framesPerBeat = 0.0))
    }

    // --- Snapping a stretch ratio ---

    @Test
    fun `a stretch ratio lands the clip on a whole number of beats`() {
        val perBeat = BeatSnap.framesPerBeat(120.0, rate)
        // Four beats long, pinched to about 1.2x: 115200 frames is 4.8 beats,
        // which rounds to 5, so the ratio becomes exactly 5/4.
        val snapped = BeatSnap.snapRatio(96_000, 1.2, perBeat)
        assertEquals(1.25, snapped, 1e-9)
        assertEquals(120_000.0, 96_000 * snapped, 1e-6)
    }

    @Test
    fun `a snapped ratio always yields a whole number of beats`() {
        val perBeat = BeatSnap.framesPerBeat(128.0, rate)
        val frames = 200_000
        for (requested in listOf(0.4, 0.77, 1.0, 1.31, 2.6)) {
            val snapped = BeatSnap.snapRatio(frames, requested, perBeat)
            val beats = frames * snapped / perBeat
            assertEquals("ratio $requested gave $beats beats", beats, Math.round(beats).toDouble(), 1e-6)
        }
    }

    @Test
    fun `a clip with no measured tempo stretches freely`() {
        // Better to stretch by exactly what was asked than to snap to a guess.
        assertEquals(1.37, BeatSnap.snapRatio(50_000, 1.37, framesPerBeat = 0.0), 1e-9)
    }

    @Test
    fun `an empty clip is not divided by`() {
        assertEquals(2.0, BeatSnap.snapRatio(0, 2.0, framesPerBeat = 1_000.0), 1e-9)
    }
}
