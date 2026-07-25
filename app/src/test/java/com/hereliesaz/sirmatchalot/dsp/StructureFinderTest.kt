package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class StructureFinderTest {

    private val finder = StructureFinder()
    private val sampleRate = SignalFixtures.SAMPLE_RATE

    /** 120 BPM, so a beat is 0.5 s and a bar is 2 s. */
    private val grid = BeatGrid(bpm = 120.0, firstBeatSeconds = 0.0)

    /**
     * Audio made of [patternBars]-bar blocks, where each block is an exact repeat
     * of the one before it for [repeats] repeats, then different material.
     */
    private fun repeatingAudio(
        patternBars: Int,
        repeats: Int,
        tailSeconds: Double = 0.0,
        seed: Int = 5,
    ): FloatArray {
        val barSeconds = grid.barDuration
        val patternSamples = (patternBars * barSeconds * sampleRate).toInt()
        val random = Random(seed)
        val pattern = FloatArray(patternSamples) { (random.nextFloat() * 2f - 1f) * 0.5f }

        val tailSamples = (tailSeconds * sampleRate).toInt()
        val out = FloatArray(patternSamples * repeats + tailSamples)
        for (r in 0 until repeats) pattern.copyInto(out, r * patternSamples)
        val tailRandom = Random(seed + 1)
        for (i in patternSamples * repeats until out.size) {
            out[i] = (tailRandom.nextFloat() * 2f - 1f) * 0.5f
        }
        return out
    }

    // --- Loops ---

    @Test
    fun `finds a repeating segment`() {
        val audio = repeatingAudio(patternBars = 4, repeats = 4)
        val loops = finder.findLoops(audio, sampleRate, grid, barCounts = listOf(4))
        assertTrue("expected at least one loop", loops.isNotEmpty())
        assertTrue("score was ${loops.first().score}", loops.first().score > 0.9f)
    }

    @Test
    fun `loops are whole bars and start on a bar line`() {
        val audio = repeatingAudio(patternBars = 4, repeats = 4)
        for (loop in finder.findLoops(audio, sampleRate, grid, barCounts = listOf(4, 8))) {
            assertTrue("bars must be positive", loop.bars > 0)
            assertEquals(
                "duration must be a whole number of bars",
                loop.bars * grid.barDuration,
                loop.durationSeconds,
                1e-9,
            )
            val barsFromZero = loop.startSeconds / grid.barDuration
            assertEquals(
                "start ${loop.startSeconds} is not on a bar line",
                barsFromZero,
                Math.round(barsFromZero).toDouble(),
                1e-6,
            )
        }
    }

    @Test
    fun `silence is never offered as a loop`() {
        // Silence trivially resembles the silence after it, so a naive
        // correlation would score it perfectly.
        val loops = finder.findLoops(FloatArray(sampleRate * 30), sampleRate, grid)
        assertTrue("silence produced ${loops.size} loops", loops.isEmpty())
    }

    @Test
    fun `unrelated material does not loop`() {
        val random = Random(11)
        val noise = FloatArray(sampleRate * 30) { (random.nextFloat() * 2f - 1f) * 0.5f }
        val loops = finder.findLoops(noise, sampleRate, grid, barCounts = listOf(4), minimumScore = 0.95f)
        assertTrue("white noise should not self-similar at 0.95, got ${loops.size}", loops.isEmpty())
    }

    @Test
    fun `returned loops never overlap`() {
        val audio = repeatingAudio(patternBars = 4, repeats = 8)
        val loops = finder.findLoops(audio, sampleRate, grid, barCounts = listOf(4, 8))
        for (i in loops.indices) {
            for (j in i + 1 until loops.size) {
                val a = loops[i]
                val b = loops[j]
                assertTrue(
                    "loops overlap: ${a.startSeconds}-${a.endSeconds} and ${b.startSeconds}-${b.endSeconds}",
                    a.endSeconds <= b.startSeconds || b.endSeconds <= a.startSeconds,
                )
            }
        }
    }

    @Test
    fun `loops are returned in playback order`() {
        val audio = repeatingAudio(patternBars = 4, repeats = 8)
        val starts = finder.findLoops(audio, sampleRate, grid, barCounts = listOf(4)).map { it.startSeconds }
        assertEquals(starts.sorted(), starts)
    }

    @Test
    fun `the limit is respected`() {
        val audio = repeatingAudio(patternBars = 4, repeats = 12)
        assertTrue(finder.findLoops(audio, sampleRate, grid, barCounts = listOf(4), limit = 2).size <= 2)
    }

    @Test
    fun `a loop never runs past the end of the track`() {
        val audio = repeatingAudio(patternBars = 4, repeats = 3)
        val duration = audio.size.toDouble() / sampleRate
        for (loop in finder.findLoops(audio, sampleRate, grid, barCounts = listOf(4, 8, 16))) {
            assertTrue("loop ends at ${loop.endSeconds}, track is $duration", loop.endSeconds <= duration)
        }
    }

    @Test
    fun `degenerate input is handled`() {
        assertTrue(finder.findLoops(FloatArray(0), sampleRate, grid).isEmpty())
        assertTrue(finder.findLoops(FloatArray(100), 0, grid).isEmpty())
        // A track shorter than two loop lengths cannot be assessed.
        assertTrue(finder.findLoops(FloatArray(sampleRate), sampleRate, grid, barCounts = listOf(16)).isEmpty())
    }

    // --- Points of interest ---

    /** An energy curve rising and falling in the shape given. */
    private fun curveOf(vararg values: Float) = EnergyCurve(values, windowSeconds = 0.5)

    @Test
    fun `a drop is tagged where energy returns after a lull`() {
        val curve = curveOf(
            0.8f, 0.8f, 0.8f,
            0.1f, 0.1f, 0.1f, 0.1f,
            0.9f, 0.9f, 0.9f, 0.9f, 0.9f,
        )
        val points = finder.findPointsOfInterest(curve)
        assertTrue("expected a drop, got ${points.map { it.kind }}", points.any { it.kind == PointOfInterest.Kind.DROP })
    }

    @Test
    fun `a breakdown is tagged where energy falls away`() {
        val curve = curveOf(
            0.9f, 0.9f, 0.9f, 0.9f,
            0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f,
        )
        val points = finder.findPointsOfInterest(curve)
        assertTrue(
            "expected a breakdown, got ${points.map { it.kind }}",
            points.any { it.kind == PointOfInterest.Kind.BREAKDOWN },
        )
    }

    @Test
    fun `a steady climb is tagged as a build`() {
        val curve = curveOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f)
        val points = finder.findPointsOfInterest(curve)
        assertTrue(
            "expected a build, got ${points.map { it.kind }}",
            points.any { it.kind == PointOfInterest.Kind.BUILD },
        )
    }

    @Test
    fun `flat material has nothing worth marking`() {
        assertTrue(finder.findPointsOfInterest(curveOf(*FloatArray(20) { 0.5f })).isEmpty())
    }

    @Test
    fun `a curve too short to assess yields nothing`() {
        assertTrue(finder.findPointsOfInterest(curveOf(0.1f, 0.9f)).isEmpty())
        assertTrue(finder.findPointsOfInterest(EnergyCurve(FloatArray(0), 0.5)).isEmpty())
    }

    @Test
    fun `points snap to bar lines when a grid is available`() {
        val curve = curveOf(
            0.8f, 0.8f, 0.8f,
            0.1f, 0.1f, 0.1f, 0.1f,
            0.9f, 0.9f, 0.9f, 0.9f, 0.9f,
        )
        val points = finder.findPointsOfInterest(curve, grid)
        assertTrue(points.isNotEmpty())
        for (point in points) {
            val bars = point.timeSeconds / grid.barDuration
            assertTrue(
                "cue at ${point.timeSeconds} is not on a bar line",
                abs(bars - Math.round(bars)) < 1e-6,
            )
        }
    }

    @Test
    fun `points are returned in playback order and within the limit`() {
        val curve = curveOf(
            0.1f, 0.9f, 0.1f, 0.9f, 0.1f, 0.9f, 0.1f, 0.9f,
            0.1f, 0.9f, 0.1f, 0.9f, 0.1f, 0.9f, 0.1f, 0.9f,
        )
        val points = finder.findPointsOfInterest(curve, limit = 4)
        assertTrue(points.size <= 4)
        assertEquals(points.map { it.timeSeconds }.sorted(), points.map { it.timeSeconds })
    }

    @Test
    fun `strength is always in range`() {
        val curve = curveOf(0.0f, 1f, 0.0f, 1f, 0.0f, 1f, 0.0f, 1f, 0.0f, 1f)
        for (point in finder.findPointsOfInterest(curve)) {
            assertTrue("strength ${point.strength} out of range", point.strength in 0f..1f)
        }
    }

    @Test
    fun `snapped points never land before the start of the track`() {
        val curve = curveOf(0.9f, 0.1f, 0.1f, 0.1f, 0.9f, 0.9f, 0.9f, 0.9f)
        for (point in finder.findPointsOfInterest(curve, BeatGrid(120.0, firstBeatSeconds = 0.3))) {
            assertTrue("cue at ${point.timeSeconds}", point.timeSeconds >= 0.0)
        }
    }
}
