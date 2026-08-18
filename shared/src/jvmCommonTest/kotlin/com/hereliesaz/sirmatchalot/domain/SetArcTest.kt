package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of a set over its whole length.
 *
 * Choosing every transition purely from the two tracks either side of it gives a
 * mix that is locally sensible and globally shapeless — which is the other half
 * of sounding robotic, and the half that no amount of clever individual
 * transitions fixes. The arc is what makes a long blend belong at track two and
 * a hard cut belong at track seven.
 */
class SetArcTest {

    private fun step(energy: Int) = MixStep(
        track = Track(title = "e$energy", artist = "t", bpm = 128.0, camelotKey = "8A", energyLevel = energy),
        transition = null,
        alignment = null,
    )

    private fun arcOf(vararg energies: Int) = SetArc.of(energies.map { step(it) })

    @Test
    fun `a full set opens, builds, peaks and comes down`() {
        val arc = arcOf(1, 2, 3, 4, 5, 6, 8, 9, 7, 5, 3, 2)
        val phases = (0 until arc.size).map { arc.phaseAt(it) }

        assertEquals(SetArc.Phase.OPENING, phases.first())
        assertTrue("no peak in $phases", SetArc.Phase.PEAK in phases)
        assertEquals(SetArc.Phase.RELEASE, phases.last())
    }

    @Test
    fun `the peak lands where the plan actually peaks`() {
        // Not two thirds of the way through by decree: the planner builds by
        // energy, so overriding where it put the loudest stretch would be the
        // arc arguing with the plan it is describing.
        val arc = arcOf(9, 9, 8, 3, 2, 1, 1, 1)
        assertTrue("peak at ${arc.peakIndex}", arc.peakIndex <= 2)
    }

    @Test
    fun `a library with no energy spread still gets an opening and a build`() {
        // Equal energies make `maxByOrNull` answer index 0, which is not a peak
        // — it is the absence of one. That zeroed the opening and started the
        // set at PEAK, so a genre-consistent library, which is an ordinary
        // thing to have, opened at full boldness and cut every track at 55%.
        val arc = arcOf(5, 5, 5, 5, 5, 5, 5, 5, 5, 5)
        val phases = (0 until arc.size).map { arc.phaseAt(it) }

        assertTrue("no opening in $phases", phases.contains(SetArc.Phase.OPENING))
        assertTrue("no build in $phases", phases.contains(SetArc.Phase.BUILD))
        assertEquals("the set must not open at the peak", SetArc.Phase.OPENING, phases.first())
        assertTrue("the peak must come after the opening", arc.peakIndex > 0)
    }

    @Test
    fun `phases only ever move forwards`() {
        val arc = arcOf(1, 2, 4, 5, 7, 9, 8, 6, 4, 2)
        val order = listOf(
            SetArc.Phase.OPENING,
            SetArc.Phase.BUILD,
            SetArc.Phase.PEAK,
            SetArc.Phase.RELEASE,
        )
        var seen = 0
        for (i in 0 until arc.size) {
            val at = order.indexOf(arc.phaseAt(i))
            assertTrue("phase went backwards at $i", at >= seen)
            seen = at
        }
    }

    @Test
    fun `a peak is a stretch rather than a single instant`() {
        val arc = arcOf(1, 2, 3, 4, 6, 8, 9, 9, 8, 6, 4, 2)
        val peaks = (0 until arc.size).count { arc.phaseAt(it) == SetArc.Phase.PEAK }
        assertTrue("only $peaks track at the peak", peaks >= 2)
    }

    @Test
    fun `a short set is not given an arc it cannot support`() {
        // Three tracks labelled opening, peak and release is a caption, not a
        // shape — the set is over before any of them means anything.
        val arc = arcOf(3, 5, 7)
        assertEquals(3, arc.size)
        assertTrue((0 until 3).all { arc.phaseAt(it) == SetArc.Phase.BUILD })
    }

    @Test
    fun `an empty plan has no arc and answers anyway`() {
        val arc = SetArc.of(emptyList())
        assertEquals(0, arc.size)
        assertEquals(SetArc.Phase.BUILD, arc.phaseAt(0))
        assertEquals(-1, arc.peakIndex)
    }

    @Test
    fun `unmeasured energy does not break the shape`() {
        val steps = List(10) {
            MixStep(
                track = Track(title = "$it", artist = "t", bpm = 128.0, camelotKey = "8A"),
                transition = null,
                alignment = null,
            )
        }
        val arc = SetArc.of(steps)
        assertEquals(10, arc.size)
        assertTrue((0 until 10).all { arc.phaseAt(it) != null })
    }

    @Test
    fun `one loud track in a quiet stretch is not the peak`() {
        // Smoothed on purpose: energyLevel is a single average per track, and a
        // lone spike among quiet material is a track, not a climax.
        val arc = arcOf(1, 1, 9, 1, 1, 7, 8, 8, 7, 2)
        assertTrue("peak at ${arc.peakIndex}", arc.peakIndex >= 5)
    }
}
