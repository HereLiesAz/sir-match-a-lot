package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class CrossfadeTest {

    @Test
    fun `endpoints select one deck exclusively`() {
        val (a0, b0) = Crossfade.equalPower(0f)
        assertEquals(1f, a0, 1e-6f)
        assertEquals(0f, b0, 1e-6f)

        val (a1, b1) = Crossfade.equalPower(1f)
        assertEquals(0f, a1, 1e-6f)
        assertEquals(1f, b1, 1e-6f)
    }

    @Test
    fun `centre splits equally`() {
        val (a, b) = Crossfade.equalPower(0.5f)
        assertEquals(sqrt(0.5f), a, 1e-6f)
        assertEquals(sqrt(0.5f), b, 1e-6f)
    }

    @Test
    fun `power is constant across the whole sweep`() {
        // This is the property the previous implementation violated: it returned
        // full gain for both decks at centre, making mid-transition about 6 dB
        // hotter than either end.
        var position = 0f
        while (position <= 1f) {
            val (a, b) = Crossfade.equalPower(position)
            assertEquals("at $position", 1.0, (a * a + b * b).toDouble(), 1e-5)
            position += 0.02f
        }
    }

    @Test
    fun `centre is not unity for both decks`() {
        // Guards specifically against a regression to the old additive law.
        val (a, b) = Crossfade.equalPower(0.5f)
        assertTrue("both gains at unity would be the old +6 dB bug", a + b < 1.9f)
    }

    @Test
    fun `positions outside the range clamp`() {
        assertEquals(Crossfade.equalPower(0f), Crossfade.equalPower(-1f))
        assertEquals(Crossfade.equalPower(1f), Crossfade.equalPower(2f))
    }

    @Test
    fun `gains move monotonically`() {
        var previousA = Float.MAX_VALUE
        var previousB = -Float.MAX_VALUE
        var position = 0f
        while (position <= 1f) {
            val (a, b) = Crossfade.equalPower(position)
            assertTrue("A should fall", a <= previousA + 1e-6f)
            assertTrue("B should rise", b >= previousB - 1e-6f)
            previousA = a
            previousB = b
            position += 0.05f
        }
    }
}

class LimiterTest {

    @Test
    fun `is bit transparent at ordinary listening levels`() {
        // Anything below -1 dBFS must pass through untouched. A limiter that
        // colours normal material is unacceptable for critical listening.
        for (v in listOf(0f, 0.1f, -0.25f, 0.5f, -0.5f, 0.7f, -0.85f, 0.891f)) {
            assertEquals(v, Limiter.softClip(v), 1e-6f)
        }
    }

    @Test
    fun `threshold sits just below full scale`() {
        // Guards against a regression to the earlier -6 dBFS threshold, which
        // compressed ordinary levels.
        assertTrue("threshold too low: ${Limiter.THRESHOLD}", Limiter.THRESHOLD > 0.8f)
        assertTrue("threshold must leave headroom", Limiter.THRESHOLD < 1f)
    }

    @Test
    fun `bounds every input below full scale`() {
        for (v in listOf(0.95f, 1f, 2f, 50f, 1000f)) {
            assertTrue("$v was not bounded", Limiter.softClip(v) < 1f)
            assertTrue("-$v was not bounded", Limiter.softClip(-v) > -1f)
        }
    }

    @Test
    fun `is odd symmetric so it adds no DC or even order distortion`() {
        for (v in listOf(0.3f, 0.95f, 1.5f, 4f)) {
            assertEquals(-Limiter.softClip(v), Limiter.softClip(-v), 1e-6f)
        }
    }

    @Test
    fun `is monotonic and continuous at the knee`() {
        var previous = -Float.MAX_VALUE
        var x = 0f
        while (x < 3f) {
            val y = Limiter.softClip(x)
            assertTrue("not monotonic at $x", y >= previous)
            previous = y
            x += 0.005f
        }
        // Continuity: approaching the knee from both sides agrees.
        val below = Limiter.softClip(Limiter.THRESHOLD - 1e-4f)
        val above = Limiter.softClip(Limiter.THRESHOLD + 1e-4f)
        assertEquals(below, above, 1e-3f)
    }

    @Test
    fun `processes a buffer in place`() {
        val buffer = floatArrayOf(0.1f, 5f, -5f, 0.2f)
        Limiter.processInPlace(buffer)
        assertEquals(0.1f, buffer[0], 1e-6f)
        assertTrue(buffer[1] < 1f && buffer[1] > 0.9f)
        assertTrue(buffer[2] > -1f && buffer[2] < -0.9f)
        assertEquals(0.2f, buffer[3], 1e-6f)
    }
}

class MixerTest {

    private val sampleRate = 44_100
    private val frames = 256

    /** A deck holding one looping clip of constant amplitude. */
    private fun deckWithConstant(name: String, amplitude: Float): Deck {
        val deck = Deck(name, sampleRate)
        deck.clips = listOf(
            Clip(
                id = "$name-clip",
                buffer = PcmBuffer.monoFromFloat(FloatArray(1024) { amplitude }, sampleRate),
                loop = true,
            ),
        )
        deck.playing = true
        return deck
    }

    private fun mixerOf(amplitudeA: Float, amplitudeB: Float): Mixer =
        Mixer(
            deckA = deckWithConstant("A", amplitudeA),
            deckB = deckWithConstant("B", amplitudeB),
            sampleRate = sampleRate,
            maxFrames = frames,
        ).apply { masterGain = 1f }

    private fun render(mixer: Mixer): FloatArray {
        val out = FloatArray(frames * Deck.CHANNELS)
        // Two blocks; read the second so gain smoothing has settled.
        mixer.render(out, frames)
        mixer.render(out, frames)
        return out
    }

    @Test
    fun `full left plays only deck A`() {
        val mixer = mixerOf(0.2f, 0.4f)
        mixer.crossfade = 0f
        val out = render(mixer)
        assertEquals(0.2f, out[out.size - 2], 5e-3f)
    }

    @Test
    fun `full right plays only deck B`() {
        val mixer = mixerOf(0.2f, 0.4f)
        mixer.crossfade = 1f
        val out = render(mixer)
        assertEquals(0.4f, out[out.size - 2], 5e-3f)
    }

    @Test
    fun `centre applies equal power gain to both decks`() {
        val mixer = mixerOf(0.2f, 0.2f)
        mixer.crossfade = 0.5f
        val out = render(mixer)
        // Identical (correlated) sources, so amplitudes add: 0.2 * 2 * sqrt(0.5).
        val expected = 0.2f * 2f * sqrt(0.5f)
        assertEquals(expected, out[out.size - 2], 5e-3f)
        // The old additive law would have produced 0.4 here.
        assertTrue("looks like the old +6 dB centre bump", abs(out[out.size - 2] - 0.4f) > 0.05f)
    }

    @Test
    fun `master gain scales the output`() {
        val mixer = mixerOf(0.4f, 0.4f)
        mixer.crossfade = 0f
        mixer.masterGain = 0.5f
        val out = render(mixer)
        assertEquals(0.2f, out[out.size - 2], 5e-3f)
    }

    @Test
    fun `silent decks produce silence and a silent meter`() {
        val mixer = Mixer(Deck("A", sampleRate), Deck("B", sampleRate), sampleRate, frames)
        val out = FloatArray(frames * Deck.CHANNELS)
        mixer.render(out, frames)
        for (v in out) assertEquals(0f, v, 0f)
        assertEquals(0f, mixer.level.peak, 0f)
        assertEquals(0f, mixer.level.rms, 0f)
    }

    @Test
    fun `meter reports peak and rms of the block`() {
        val mixer = mixerOf(0.5f, 0.5f)
        mixer.crossfade = 0f
        mixer.masterGain = 1f
        render(mixer)
        // Constant 0.5 signal: peak and rms coincide.
        assertEquals(0.5f, mixer.level.peak, 1e-2f)
        assertEquals(0.5f, mixer.level.rms, 1e-2f)
    }

    @Test
    fun `output never exceeds full scale even when both decks are hot`() {
        val mixer = mixerOf(1f, 1f)
        mixer.crossfade = 0.5f
        mixer.masterGain = 1f
        val out = render(mixer)
        for (v in out) {
            assertTrue("sample $v exceeded full scale", abs(v) < 1f)
        }
    }

    @Test
    fun `rejects a block larger than its capacity`() {
        val mixer = mixerOf(0.2f, 0.2f)
        try {
            mixer.render(FloatArray(frames * 4 * Deck.CHANNELS), frames * 2)
            throw AssertionError("expected an oversized block to be rejected")
        } catch (expected: IllegalArgumentException) {
            // intended
        }
    }

    @Test
    fun `crossfade sweep does not step discontinuously`() {
        // Gain smoothing exists so a fast sweep does not zipper. Jump the fader
        // from one extreme to the other and check the output has no cliff.
        val mixer = mixerOf(0.3f, 0.3f)
        mixer.crossfade = 0f
        val out = FloatArray(frames * Deck.CHANNELS)
        mixer.render(out, frames)

        mixer.crossfade = 1f
        mixer.render(out, frames)

        var maxStep = 0f
        var i = 2
        while (i < out.size) {
            maxStep = maxOf(maxStep, abs(out[i] - out[i - 2]))
            i += 2
        }
        assertTrue("crossfade stepped by $maxStep", maxStep < 0.02f)
    }

    // --- The render path's own contract ---

    @Test
    fun `rendering a block allocates nothing`() {
        // Both Deck.render and Mixer.render say "allocates nothing" in their
        // KDoc, and both did: `for (clip in snapshot)` inside the per-frame loop
        // is a `List.iterator()` per frame, `Crossfade.equalPower` returned a
        // `Pair<Float, Float>`, and the metered level was a fresh `OutputLevel`
        // every block. This is the assertion those comments were making.
        //
        // Measured as bytes allocated on this thread, which is what the JVM will
        // report without a profiler. A tolerance rather than zero: the
        // measurement is not itself free, and the difference that matters is
        // between "a handful per block" and "one object per frame per clip".
        val mixer = mixerOf(0.2f, 0.2f)
        val out = FloatArray(frames * Deck.CHANNELS)

        // Warm up, so class loading and JIT are not counted.
        repeat(200) { mixer.render(out, frames) }

        val before = allocatedBytes() ?: return
        repeat(100) { mixer.render(out, frames) }
        val after = allocatedBytes() ?: return

        val perBlock = (after - before) / 100.0
        // One iterator per clip per frame is ~1,000 objects a block at this
        // size, so the old behaviour is orders of magnitude above this bound.
        assertTrue(
            "%.0f bytes allocated per render block".format(perBlock),
            perBlock < 512,
        )
    }

    /** Bytes this thread has allocated, or null where the JVM will not say. */
    private fun allocatedBytes(): Long? = runCatching {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        val method = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        method.invoke(bean, Thread.currentThread().id) as Long
    }.getOrNull()
}
