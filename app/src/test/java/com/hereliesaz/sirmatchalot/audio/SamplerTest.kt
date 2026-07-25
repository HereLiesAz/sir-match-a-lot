package com.hereliesaz.sirmatchalot.audio

import com.hereliesaz.sirmatchalot.dsp.LoopCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SamplerTest {

    private val sampleRate = 44_100

    private fun sampler(maxSeconds: Double = 1.0) =
        Sampler(padCount = 8, sampleRate = sampleRate, maxRecordSeconds = maxSeconds)

    /** A block of interleaved stereo at a constant level. */
    private fun masterBlock(frames: Int, level: Float) =
        FloatArray(frames * Deck.CHANNELS) { level }

    private fun toneBuffer(frames: Int, level: Float = 0.5f) =
        PcmBuffer.fromFloat(
            arrayOf(FloatArray(frames) { level }, FloatArray(frames) { level }),
            sampleRate,
        )

    // --- Pads ---

    @Test
    fun `pads start empty and idle`() {
        val sampler = sampler()
        assertEquals(8, sampler.pads.size)
        assertEquals(8, sampler.emptyPadCount)
        for (pad in sampler.pads) {
            assertTrue(pad.isEmpty)
            assertTrue(!pad.isPlaying)
        }
    }

    @Test
    fun `an empty pad cannot be triggered`() {
        val sampler = sampler()
        sampler.trigger(0)
        assertTrue(!sampler.pads[0].isPlaying)
    }

    @Test
    fun `triggering a loaded pad starts it from the beginning`() {
        val sampler = sampler()
        sampler.pads[0].load(toneBuffer(100), "tone")
        sampler.trigger(0)
        assertTrue(sampler.pads[0].isPlaying)
        assertEquals(0.0, sampler.pads[0].playhead, 0.0)
    }

    @Test
    fun `out of range pads are ignored rather than crashing`() {
        val sampler = sampler()
        sampler.trigger(99)
        sampler.stop(-1)
        sampler.beginRecording(99)
        assertTrue(!sampler.isRecording)
    }

    // --- Recording ---

    @Test
    fun `recording captures the master bus`() {
        val sampler = sampler()
        sampler.beginRecording(0)
        assertTrue(sampler.isRecording)

        repeat(4) { sampler.captureFromMaster(masterBlock(256, 0.5f), 256) }
        val captured = requireNotNull(sampler.endRecording())

        assertEquals(1024, captured.frameCount)
        assertEquals(sampleRate, captured.sampleRate)
        assertTrue(!sampler.isRecording)
        // The take lands on the pad.
        assertTrue(!sampler.pads[0].isEmpty)
    }

    @Test
    fun `recorded audio matches what was captured`() {
        val sampler = sampler()
        sampler.beginRecording(1)
        sampler.captureFromMaster(masterBlock(64, 0.5f), 64)
        val captured = requireNotNull(sampler.endRecording())

        for (v in captured.toMonoFloat()) {
            assertEquals(0.5f, v, 1e-3f)
        }
    }

    @Test
    fun `nothing is captured before recording begins`() {
        val sampler = sampler()
        sampler.captureFromMaster(masterBlock(256, 0.5f), 256)
        assertNull(sampler.endRecording())
        assertTrue(sampler.pads[0].isEmpty)
    }

    @Test
    fun `an empty take does not load a pad`() {
        val sampler = sampler()
        sampler.beginRecording(0)
        assertNull(sampler.endRecording())
        assertTrue(sampler.pads[0].isEmpty)
    }

    @Test
    fun `capture stops at the ceiling instead of overwriting the take`() {
        // A fixed ceiling is the price of never allocating on the audio thread.
        val sampler = sampler(maxSeconds = 0.01) // 441 frames
        sampler.beginRecording(0)
        repeat(20) { sampler.captureFromMaster(masterBlock(256, 0.5f), 256) }
        val captured = requireNotNull(sampler.endRecording())
        assertEquals(441, captured.frameCount)
    }

    @Test
    fun `record progress is reported`() {
        val sampler = sampler(maxSeconds = 0.01)
        sampler.beginRecording(0)
        assertEquals(0f, sampler.recordProgress(), 1e-6f)
        sampler.captureFromMaster(masterBlock(220, 0.5f), 220)
        assertTrue(sampler.recordProgress() > 0.4f)
    }

    @Test
    fun `cancelling a take leaves the pad untouched`() {
        val sampler = sampler()
        sampler.pads[0].load(toneBuffer(100), "original")
        sampler.beginRecording(0)
        sampler.captureFromMaster(masterBlock(256, 0.9f), 256)
        sampler.cancelRecording()

        assertTrue(!sampler.isRecording)
        assertEquals("original", sampler.pads[0].label)
    }

    @Test
    fun `a pad's existing content survives until the take completes`() {
        val sampler = sampler()
        sampler.pads[0].load(toneBuffer(100), "original")
        sampler.beginRecording(0)
        // Mid-take, the old content is still there.
        assertEquals("original", sampler.pads[0].label)
        sampler.captureFromMaster(masterBlock(64, 0.5f), 64)
        sampler.endRecording(label = "new take")
        assertEquals("new take", sampler.pads[0].label)
    }

    // --- Replay ---

    @Test
    fun `a triggered pad renders its audio`() {
        val sampler = sampler()
        sampler.pads[0].load(toneBuffer(256, level = 0.4f), "tone")
        sampler.trigger(0)

        val out = FloatArray(64 * Deck.CHANNELS)
        sampler.render(out, 64)
        for (v in out) assertEquals(0.4f, v, 5e-3f)
    }

    @Test
    fun `rendering adds to existing content so the sampler layers over the decks`() {
        val sampler = sampler()
        sampler.pads[0].load(toneBuffer(256, level = 0.2f), "tone")
        sampler.trigger(0)

        val out = FloatArray(32 * Deck.CHANNELS) { 0.1f }
        sampler.render(out, 32)
        for (v in out) assertEquals(0.3f, v, 5e-3f)
    }

    @Test
    fun `an idle pad renders nothing`() {
        val sampler = sampler()
        sampler.pads[0].load(toneBuffer(256), "tone")
        val out = FloatArray(32 * Deck.CHANNELS)
        sampler.render(out, 32)
        for (v in out) assertEquals(0f, v, 0f)
    }

    @Test
    fun `a one-shot pad stops at the end`() {
        val sampler = sampler()
        sampler.pads[0].load(toneBuffer(32), "short", loop = false)
        sampler.trigger(0)

        val out = FloatArray(128 * Deck.CHANNELS)
        sampler.render(out, 128)
        assertTrue("should have stopped", !sampler.pads[0].isPlaying)
        // Nothing sounds past the end of the sample.
        for (i in 40 until 128) {
            assertEquals(0f, out[i * Deck.CHANNELS], 1e-6f)
        }
    }

    @Test
    fun `a looping pad keeps going`() {
        val sampler = sampler()
        sampler.pads[0].load(toneBuffer(32, level = 0.5f), "loop", loop = true)
        sampler.trigger(0)

        val out = FloatArray(256 * Deck.CHANNELS)
        sampler.render(out, 256)
        assertTrue("should still be playing", sampler.pads[0].isPlaying)
        for (v in out) assertEquals(0.5f, v, 5e-3f)
    }

    @Test
    fun `pad gain scales its output`() {
        val sampler = sampler()
        sampler.pads[0].load(toneBuffer(128, level = 0.8f), "tone")
        sampler.pads[0].gain = 0.5f
        sampler.trigger(0)

        val out = FloatArray(32 * Deck.CHANNELS)
        sampler.render(out, 32)
        for (v in out) assertEquals(0.4f, v, 5e-3f)
    }

    @Test
    fun `several pads sum together`() {
        val sampler = sampler()
        sampler.pads[0].load(toneBuffer(128, level = 0.2f), "a")
        sampler.pads[1].load(toneBuffer(128, level = 0.3f), "b")
        sampler.trigger(0)
        sampler.trigger(1)

        val out = FloatArray(32 * Deck.CHANNELS)
        sampler.render(out, 32)
        for (v in out) assertEquals(0.5f, v, 1e-2f)
    }

    @Test
    fun `stopping silences a pad`() {
        val sampler = sampler()
        sampler.pads[0].load(toneBuffer(256, level = 0.5f), "tone", loop = true)
        sampler.trigger(0)
        sampler.stop(0)

        val out = FloatArray(32 * Deck.CHANNELS)
        sampler.render(out, 32)
        for (v in out) assertEquals(0f, v, 0f)
    }

    @Test
    fun `stop all silences everything`() {
        val sampler = sampler()
        sampler.pads.forEach { it.load(toneBuffer(128), "x", loop = true) }
        sampler.pads.indices.forEach { sampler.trigger(it) }
        sampler.stopAll()
        assertTrue(sampler.pads.none { it.isPlaying })
    }

    // --- Auto-fill ---

    private fun candidate(start: Double, bars: Int, score: Float) =
        LoopCandidate(start, bars, durationSeconds = bars * 2.0, score = score)

    @Test
    fun `auto-fill takes loops from the track into empty pads`() {
        val sampler = sampler()
        val source = toneBuffer(sampleRate * 20)
        val filled = sampler.autoFill(
            listOf(candidate(0.0, 4, 0.9f), candidate(8.0, 4, 0.8f)),
            source,
            trackTitle = "Track",
        )
        assertEquals(2, filled)
        assertEquals(6, sampler.emptyPadCount)
        assertTrue(sampler.pads[0].label!!.contains("4bar"))
    }

    @Test
    fun `auto-fill never overwrites a performer's own take`() {
        // The explicit requirement is to fill *unused* pads.
        val sampler = sampler()
        sampler.pads[0].load(toneBuffer(100), "my take")
        val source = toneBuffer(sampleRate * 20)

        sampler.autoFill(listOf(candidate(0.0, 4, 0.9f)), source)
        assertEquals("my take", sampler.pads[0].label)
        assertTrue("should have used the next free pad", !sampler.pads[1].isEmpty)
    }

    @Test
    fun `auto-fill uses the strongest candidates first`() {
        val sampler = sampler()
        val source = toneBuffer(sampleRate * 40)
        sampler.autoFill(
            listOf(candidate(0.0, 4, 0.6f), candidate(16.0, 16, 0.99f)),
            source,
        )
        assertTrue(
            "the best loop should take the first pad, got ${sampler.pads[0].label}",
            sampler.pads[0].label!!.contains("16bar"),
        )
    }

    @Test
    fun `auto-fill stops when the pads are full`() {
        val sampler = sampler()
        val source = toneBuffer(sampleRate * 200)
        val many = (0 until 20).map { candidate(it * 9.0, 4, 0.9f) }
        assertEquals(8, sampler.autoFill(many, source))
        assertEquals(0, sampler.emptyPadCount)
    }

    @Test
    fun `auto-fill skips candidates that fall outside the track`() {
        val sampler = sampler()
        val source = toneBuffer(sampleRate * 2)
        assertEquals(0, sampler.autoFill(listOf(candidate(60.0, 4, 0.9f)), source))
        assertEquals(8, sampler.emptyPadCount)
    }

    @Test
    fun `auto-fill with no candidates does nothing`() {
        val sampler = sampler()
        assertEquals(0, sampler.autoFill(emptyList(), toneBuffer(1000)))
    }

    @Test
    fun `a filled pad holds the right slice of audio`() {
        val sampler = sampler()
        // A ramp, so position within the source is identifiable.
        val ramp = FloatArray(sampleRate * 10) { it.toFloat() / (sampleRate * 10) }
        val source = PcmBuffer.fromFloat(arrayOf(ramp, ramp), sampleRate)

        sampler.autoFill(listOf(candidate(4.0, 1, 0.9f)), source)
        val pad = requireNotNull(sampler.pads[0].buffer)

        assertEquals("2 seconds at $sampleRate", sampleRate * 2, pad.frameCount)
        // The slice should begin at 4/10 of the way through the ramp.
        assertEquals(0.4f, pad.toMonoFloat().first(), 1e-2f)
    }

    @Test
    fun `clear all empties every pad and cancels recording`() {
        val sampler = sampler()
        sampler.pads.forEach { it.load(toneBuffer(64), "x") }
        sampler.beginRecording(0)
        sampler.clearAll()

        assertEquals(8, sampler.emptyPadCount)
        assertTrue(!sampler.isRecording)
    }

    @Test
    fun `recorded audio can be replayed`() {
        // The full round trip: capture the mix, then hear it back.
        val sampler = sampler()
        sampler.beginRecording(2)
        sampler.captureFromMaster(masterBlock(512, 0.6f), 512)
        sampler.endRecording(loop = false)

        sampler.trigger(2)
        val out = FloatArray(128 * Deck.CHANNELS)
        sampler.render(out, 128)
        for (v in out) assertTrue("expected replayed audio, got $v", abs(v - 0.6f) < 1e-2f)
    }
}
