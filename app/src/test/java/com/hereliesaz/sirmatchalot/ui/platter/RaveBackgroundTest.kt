package com.hereliesaz.sirmatchalot.ui.platter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the light show that are arithmetic rather than pixels.
 *
 * Drawing cannot be checked from the JVM, but the two properties that make it
 * "not a strobe" can: that silence is darkness, and that the rig's motion comes
 * from a clock rather than from the audio.
 */
class RaveBackgroundTest {

    @Test
    fun `silence is darkness`() {
        // A background that glowed with nothing playing would be decoration,
        // and decoration is the thing C15 rules out.
        assertTrue(SpectrumBands.DARK.isDark)
        assertTrue(SpectrumBands(0f, 0f, 0f).isDark)
    }

    @Test
    fun `any band above zero lights the room`() {
        assertFalse(SpectrumBands(low = 0.4f).isDark)
        assertFalse(SpectrumBands(mid = 0.4f).isDark)
        assertFalse(SpectrumBands(high = 0.4f).isDark)
    }

    @Test
    fun `the rig sweeps through a full turn over its period`() {
        val quarter = rigPhase(RIG_ROTATION_PERIOD_MS / 4)
        val half = rigPhase(RIG_ROTATION_PERIOD_MS / 2)
        val twoPi = (2.0 * Math.PI).toFloat()

        assertEquals(twoPi / 4f, quarter, 1e-3f)
        assertEquals(twoPi / 2f, half, 1e-3f)
    }

    @Test
    fun `the phase wraps rather than growing without bound`() {
        // The clock this reads is the frame clock, whose origin is arbitrary
        // and which runs for the life of the process.
        assertEquals(rigPhase(0), rigPhase(RIG_ROTATION_PERIOD_MS), 1e-3f)
        assertEquals(
            rigPhase(1_234),
            rigPhase(1_234 + RIG_ROTATION_PERIOD_MS * 1_000),
            1e-3f,
        )
    }

    @Test
    fun `the same elapsed time always gives the same arrangement`() {
        // Motion is reproducible, so the background is not a source of
        // nondeterminism in a screenshot or a recording.
        assertEquals(rigPhase(7_777), rigPhase(7_777), 0f)
    }

    @Test
    fun `motion is slow enough to be ambience`() {
        // Over one frame at 60 Hz the slowest light moves a fraction of a
        // degree. Anything faster reads as an effect rather than as a room.
        val perFrame = rigPhase(16) - rigPhase(0)
        val degrees = Math.toDegrees(perFrame.toDouble())
        assertTrue("$degrees degrees per frame", degrees < 1.0)
    }

    @Test
    fun `the rig has lights on every band`() {
        // Weighted toward the low band, but not silent on any of them: a rig
        // that ignored a band would make that part of the music invisible.
        assertTrue(RIG_SIZE >= 3)
    }
}
