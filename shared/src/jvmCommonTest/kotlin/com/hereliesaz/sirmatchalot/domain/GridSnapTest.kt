package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.dsp.PointOfInterest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tiers of a snap.
 *
 * What a mix is made of is bars lining up, so a bar on a bar has to grab harder
 * than a beat on a beat, and beat four on beat two sits between them: musical,
 * but not the bar. All of it is arithmetic over points, so it is pinned here.
 */
class GridSnapTest {

    /** Half a second: one beat at 120 BPM, the strong-tier reach in these tests. */
    private val radius = 0.5

    private fun beat(t: Double, pos: Int, perBar: Int = 4) = SnapPoint.beat(t, pos, perBar)

    @Test
    fun `a downbeat near a downbeat is pulled on to it, strongly`() {
        val result = GridSnap.snap(listOf(beat(10.1, 0)), listOf(beat(10.0, 0)), radius)!!

        assertEquals(-0.1, result.shiftSeconds, 1e-9)
        assertEquals(SnapTier.STRONG, result.tier)
    }

    @Test
    fun `beat four on beat two is a medium snap`() {
        // Positions 3 and 1 in four four: two beats, half a bar, apart.
        val result = GridSnap.snap(listOf(beat(5.03, 3)), listOf(beat(5.0, 1)), radius)!!

        assertEquals(SnapTier.MEDIUM, result.tier)
        assertEquals(-0.03, result.shiftSeconds, 1e-9)
    }

    @Test
    fun `medium reaches half as far as strong`() {
        // 0.3 s is inside the strong reach (0.5) but outside the medium one (0.25).
        assertNotNull(GridSnap.snap(listOf(beat(10.3, 0)), listOf(beat(10.0, 0)), radius))
        assertNull(GridSnap.snap(listOf(beat(5.3, 3)), listOf(beat(5.0, 1)), radius))
    }

    @Test
    fun `an off-bar beat is only a weak snap and only from close by`() {
        // Beat two onto beat one of the target: on a beat, but not a bar relation.
        val near = GridSnap.snap(listOf(beat(10.1, 1)), listOf(beat(10.0, 0)), radius)!!
        assertEquals(SnapTier.WEAK, near.tier)

        // Weak reaches a quarter of the radius: 0.125 s here.
        assertNull(GridSnap.snap(listOf(beat(10.2, 1)), listOf(beat(10.0, 0)), radius))
    }

    @Test
    fun `a bar line a little further off outranks a plain beat just next to it`() {
        // Downbeat 0.2 s from a downbeat, and also 0.075 s from a weak beat.
        val mover = listOf(beat(10.2, 0))
        val targets = listOf(beat(10.0, 0), beat(10.275, 1))

        val result = GridSnap.snap(mover, targets, radius)!!

        assertEquals(SnapTier.STRONG, result.tier)
        assertEquals(-0.2, result.shiftSeconds, 1e-9)
    }

    @Test
    fun `a much closer weak beat still beats a distant bar line`() {
        // 0.45 s from the bar line, 0.02 s from a weak beat.
        val mover = listOf(beat(10.45, 0))
        val targets = listOf(beat(10.0, 0), beat(10.47, 1))

        assertEquals(SnapTier.WEAK, GridSnap.snap(mover, targets, radius)!!.tier)
    }

    @Test
    fun `downbeats meet strongly even when the bars differ in length`() {
        val result = GridSnap.snap(listOf(beat(10.05, 0, perBar = 3)), listOf(beat(10.0, 0, perBar = 4)), radius)!!

        assertEquals(SnapTier.STRONG, result.tier)
    }

    @Test
    fun `other beats of bars of different lengths are only weak`() {
        // Beat two of a 3/4 bar against beat two of a 4/4 bar: no bar relation.
        val result = GridSnap.snap(listOf(beat(10.05, 1, perBar = 3)), listOf(beat(10.0, 1, perBar = 4)), radius)!!

        assertEquals(SnapTier.WEAK, result.tier)
    }

    @Test
    fun `a three four bar has no half-bar tier`() {
        // 3 beats per bar: offset 1 or 2 are both just beat relations.
        val result = GridSnap.snap(listOf(beat(10.05, 2, perBar = 3)), listOf(beat(10.0, 1, perBar = 3)), radius)!!

        assertEquals(SnapTier.WEAK, result.tier)
    }

    @Test
    fun `a drop snaps strongly to a drop`() {
        val result = GridSnap.snap(
            listOf(SnapPoint.marker(30.2, PointOfInterest.Kind.DROP)),
            listOf(SnapPoint.marker(30.0, PointOfInterest.Kind.DROP)),
            radius,
        )!!

        assertEquals(SnapTier.STRONG, result.tier)
        assertEquals(-0.2, result.shiftSeconds, 1e-9)
    }

    @Test
    fun `different kinds of marker do not attract`() {
        assertNull(
            GridSnap.snap(
                listOf(SnapPoint.marker(30.1, PointOfInterest.Kind.DROP)),
                listOf(SnapPoint.marker(30.0, PointOfInterest.Kind.BREAKDOWN)),
                radius,
            ),
        )
    }

    @Test
    fun `a marker is strong on a bar line and weak on any other beat`() {
        val drop = SnapPoint.marker(30.05, PointOfInterest.Kind.DROP)

        assertEquals(SnapTier.STRONG, GridSnap.snap(listOf(drop), listOf(beat(30.0, 0)), radius)!!.tier)
        assertEquals(SnapTier.WEAK, GridSnap.snap(listOf(drop), listOf(beat(30.0, 2)), radius)!!.tier)
    }

    @Test
    fun `zero radius disables snapping`() {
        assertNull(GridSnap.snap(listOf(beat(10.01, 0)), listOf(beat(10.0, 0)), 0.0))
        assertNull(GridSnap.snap(listOf(beat(10.01, 0)), listOf(beat(10.0, 0)), -1.0))
    }

    @Test
    fun `nothing to snap to is not a snap`() {
        assertNull(GridSnap.snap(emptyList(), listOf(beat(10.0, 0)), radius))
        assertNull(GridSnap.snap(listOf(beat(10.0, 0)), emptyList(), radius))
    }

    @Test
    fun `the nearest of several bar lines wins`() {
        val targets = listOf(beat(8.0, 0), beat(10.0, 0), beat(12.0, 0))

        assertEquals(10.0, GridSnap.snap(listOf(beat(9.9, 0)), targets, radius)!!.target.timeSeconds, 1e-9)
    }
}
