package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a drop from a build from a breakdown.
 *
 * These three events are what the whole choreographer is built on — where to
 * leave a track, where to join it — and on a single broadband loudness curve
 * they are not separable. A riser and the drop it leads into are one continuous
 * rise; a breakdown that keeps its pads moves the broadband curve hardly at all.
 *
 * So the tests here are written as synthetic tracks with a *known* structure,
 * and they assert the label, not merely that something was found. The old
 * detector finds something in all of these; it finds the wrong thing in most.
 */
class StructureSegmenterTest {

    private val window = 0.5

    /**
     * Builds a band curve directly, so a test states the music rather than
     * synthesising audio and hoping the analysis hears it.
     */
    private fun bands(
        low: List<Float>,
        mid: List<Float> = List(low.size) { 0.5f },
        high: List<Float> = List(low.size) { 0.5f },
    ) = BandEnergyCurve(low.toFloatArray(), mid.toFloatArray(), high.toFloatArray(), window)

    private fun energyOf(curve: BandEnergyCurve) =
        EnergyCurve(FloatArray(curve.size) { curve.total(it) }, window)

    private fun segment(curve: BandEnergyCurve, grid: BeatGrid? = null) =
        SpectralSegmenter().segment(
            StructureInput(energy = energyOf(curve), bands = curve, grid = grid),
        )

    private fun flat(n: Int, value: Float) = List(n) { value }

    // --- The three that matter ---

    @Test
    fun `a drop is the low end coming back`() {
        // Sixteen windows of no bass, then it returns and stays. That is a drop,
        // and it is a drop whether or not the track got louder overall.
        val curve = bands(low = flat(10, 0.1f) + flat(14, 0.9f))
        val found = segment(curve)

        val drop = found.firstOrNull { it.kind == PointOfInterest.Kind.DROP }
        assertTrue("no drop in $found", drop != null)
        assertTrue("drop at ${drop!!.timeSeconds}", drop.timeSeconds in 4.0..7.0)
    }

    @Test
    fun `a build is the top climbing while the bottom stays out`() {
        // The case a broadband detector is blind to: a riser over an absent
        // kick sums to roughly nothing changing.
        val curve = bands(
            low = flat(20, 0.1f),
            high = List(20) { 0.2f + it * 0.04f },
        )
        val found = segment(curve)
        assertTrue("no build in $found", found.any { it.kind == PointOfInterest.Kind.BUILD })
        assertTrue("a build is not a drop: $found", found.none { it.kind == PointOfInterest.Kind.DROP })
    }

    @Test
    fun `a breakdown keeps something playing`() {
        // Low end leaves, the top carries on. Broadband energy barely moves,
        // which is exactly why these were being missed.
        val curve = bands(
            low = flat(10, 0.9f) + flat(14, 0.1f),
            high = flat(24, 0.7f),
        )
        val found = segment(curve)
        assertTrue("no breakdown in $found", found.any { it.kind == PointOfInterest.Kind.BREAKDOWN })
    }

    @Test
    fun `an outro is not a breakdown`() {
        // Everything leaving is a track ending, not a breakdown to mix into.
        // Requiring the top to survive is what separates them.
        val curve = bands(
            low = flat(10, 0.9f) + flat(14, 0.05f),
            mid = flat(10, 0.9f) + flat(14, 0.05f),
            high = flat(10, 0.9f) + flat(14, 0.05f),
        )
        val found = segment(curve)
        assertTrue("called a fade-out a breakdown: $found", found.none { it.kind == PointOfInterest.Kind.BREAKDOWN })
    }

    @Test
    fun `a build into a drop yields both, in order`() {
        // The single most important case, and the one the old detector collapsed
        // into one mislabelled point: the riser and the moment it pays off.
        val curve = bands(
            low = flat(12, 0.08f) + flat(14, 0.92f),
            high = List(12) { 0.25f + it * 0.05f } + flat(14, 0.8f),
        )
        val found = segment(curve)

        val build = found.firstOrNull { it.kind == PointOfInterest.Kind.BUILD }
        val drop = found.firstOrNull { it.kind == PointOfInterest.Kind.DROP }
        assertTrue("no build in $found", build != null)
        assertTrue("no drop in $found", drop != null)
        assertTrue("the build must come first: $found", build!!.timeSeconds < drop!!.timeSeconds)
    }

    // --- Not inventing things ---

    @Test
    fun `a track that does nothing has no landmarks`() {
        assertEquals(emptyList<PointOfInterest>(), segment(bands(low = flat(40, 0.5f))))
    }

    @Test
    fun `silence has no landmarks`() {
        assertEquals(
            emptyList<PointOfInterest>(),
            segment(bands(low = flat(40, 0f), mid = flat(40, 0f), high = flat(40, 0f))),
        )
    }

    @Test
    fun `a track too short to have structure yields nothing`() {
        assertEquals(emptyList<PointOfInterest>(), segment(bands(low = flat(3, 0.2f))))
    }

    @Test
    fun `no more landmarks than asked for`() {
        val alternating = (0 until 120).map { if ((it / 3) % 2 == 0) 0.1f else 0.9f }
        val curve = bands(low = alternating)
        val found = SpectralSegmenter().segment(
            StructureInput(energy = energyOf(curve), bands = curve, limit = 5),
        )
        assertTrue("returned ${found.size}", found.size <= 5)
    }

    @Test
    fun `landmarks come back in time order`() {
        val curve = bands(low = flat(10, 0.1f) + flat(10, 0.9f) + flat(10, 0.1f) + flat(10, 0.9f))
        val found = segment(curve)
        assertEquals(found.sortedBy { it.timeSeconds }, found)
    }

    @Test
    fun `landmarks snap to bar lines`() {
        // A cue marker half a beat off is worse than useless.
        val curve = bands(low = flat(10, 0.1f) + flat(14, 0.9f))
        val grid = BeatGrid(bpm = 120.0, firstBeatSeconds = 0.0)
        val found = segment(curve, grid)

        assertTrue(found.isNotEmpty())
        for (point in found) {
            assertEquals("off the grid: ${point.timeSeconds}", 0.0, point.timeSeconds % grid.barDuration, 1e-6)
        }
    }

    // --- Falling back rather than failing ---

    @Test
    fun `without bands it uses the old detector rather than giving up`() {
        // A library analysed by an earlier version has no band information, and
        // re-deriving it means decoding every file again. It keeps its landmarks
        // at the fidelity it was analysed at.
        val values = FloatArray(24) { if (it < 10) 0.15f else 0.85f }
        val found = SpectralSegmenter().segment(
            StructureInput(energy = EnergyCurve(values, window), bands = null),
        )
        assertTrue("fell back to nothing", found.isNotEmpty())
    }

    @Test
    fun `the old detector still behaves as it always did`() {
        val values = FloatArray(24) { if (it < 10) 0.15f else 0.85f }
        val input = StructureInput(energy = EnergyCurve(values, window), bands = null)
        assertEquals(BroadbandSegmenter().segment(input), SpectralSegmenter().segment(input))
    }

    @Test
    fun `StructureFinder asks the current segmenter`() {
        // The rest of the app goes through StructureFinder, so it must be the
        // thing that improved, not a parallel path nobody calls.
        val curve = bands(low = flat(10, 0.1f) + flat(14, 0.9f))
        val found = StructureFinder().findPointsOfInterest(
            energy = energyOf(curve),
            bands = curve,
        )
        assertTrue("no drop in $found", found.any { it.kind == PointOfInterest.Kind.DROP })
    }

    // --- Measuring the bands ---

    @Test
    fun `a bass tone lands in the low band and a hiss in the high`() {
        val rate = 44_100
        val bass = FloatArray(rate) { kotlin.math.sin(2.0 * Math.PI * 60.0 * it / rate).toFloat() * 0.8f }
        val air = FloatArray(rate) { kotlin.math.sin(2.0 * Math.PI * 8_000.0 * it / rate).toFloat() * 0.8f }

        val low = BandEnergyCurve.compute(bass, rate)
        val high = BandEnergyCurve.compute(air, rate)

        assertTrue("bass did not read as low end", low.low.average() > low.high.average())
        assertTrue("air did not read as top end", high.high.average() > high.low.average())
    }

    @Test
    fun `measuring nothing gives an empty curve rather than throwing`() {
        assertTrue(BandEnergyCurve.compute(FloatArray(0), 44_100).isEmpty)
        assertTrue(BandEnergyCurve.compute(FloatArray(100), 0).isEmpty)
    }

    @Test
    fun `the bands share a timeline with the energy curve`() {
        // Two analyses of one track that disagree about where second ninety is
        // are worse than one.
        val rate = 44_100
        val samples = FloatArray(rate * 4) { kotlin.math.sin(2.0 * Math.PI * 220.0 * it / rate).toFloat() * 0.5f }

        val energy = EnergyCurve.compute(samples, rate)
        val bands = BandEnergyCurve.compute(samples, rate)

        assertEquals(energy.windowSeconds, bands.windowSeconds, 1e-9)
        assertEquals(energy.size, bands.size)
    }
}
