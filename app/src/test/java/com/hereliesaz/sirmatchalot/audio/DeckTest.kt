package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmBufferTest {

    @Test
    fun `reports geometry`() {
        val buffer = PcmBuffer.silence(frameCount = 4410, channelCount = 2, sampleRate = 44_100)
        assertEquals(4410, buffer.frameCount)
        assertEquals(2, buffer.channelCount)
        assertEquals(0.1, buffer.durationSeconds, 1e-9)
    }

    @Test
    fun `mono buffers play centred rather than only left`() {
        // Asking a mono buffer for its right channel must return the same data,
        // or a mono clip would be panned hard left.
        val buffer = PcmBuffer.monoFromFloat(floatArrayOf(0.5f, -0.5f), 44_100)
        assertTrue(buffer.channel(0) === buffer.channel(1))
    }

    @Test
    fun `float round trip preserves values within quantisation`() {
        val original = FloatArray(64) { it / 64f - 0.5f }
        val buffer = PcmBuffer.monoFromFloat(original, 44_100)
        val restored = buffer.toMonoFloat()
        for (i in original.indices) {
            assertEquals(original[i], restored[i], 1e-3f)
        }
    }

    @Test
    fun `slicing trims to a range`() {
        val buffer = PcmBuffer.monoFromFloat(FloatArray(100) { it / 100f }, 44_100)
        val sliced = buffer.slice(10, 20)
        assertEquals(10, sliced.frameCount)
        assertEquals(0.10f, sliced.toMonoFloat()[0], 1e-3f)
    }

    @Test
    fun `slicing clamps out of range bounds`() {
        val buffer = PcmBuffer.monoFromFloat(FloatArray(10), 44_100)
        assertEquals(10, buffer.slice(-5, 500).frameCount)
        assertEquals(0, buffer.slice(8, 2).frameCount)
    }

    @Test
    fun `mono downmix averages channels`() {
        val buffer = PcmBuffer.fromFloat(
            arrayOf(FloatArray(4) { 0.5f }, FloatArray(4) { -0.5f }),
            44_100,
        )
        for (v in buffer.toMonoFloat()) assertEquals(0f, v, 1e-3f)
    }

    @Test
    fun `rejects invalid construction`() {
        for (block in listOf<() -> Unit>(
            { PcmBuffer(emptyArray(), 44_100) },
            { PcmBuffer(arrayOf(ShortArray(4)), 0) },
            { PcmBuffer(arrayOf(ShortArray(4), ShortArray(5)), 44_100) },
        )) {
            try {
                block()
                throw AssertionError("expected rejection")
            } catch (expected: IllegalArgumentException) {
                // intended
            }
        }
    }
}

class DeckTest {

    private val sampleRate = 44_100

    /** A clip whose sample values equal their frame index / 100. */
    private fun rampClip(frames: Int = 8, loop: Boolean = false, startFrame: Int = 0, gain: Float = 1f) =
        Clip(
            id = "ramp",
            buffer = PcmBuffer.monoFromFloat(FloatArray(frames) { it / 100f }, sampleRate),
            startFrame = startFrame,
            gain = gain,
            loop = loop,
        )

    private fun deckWith(vararg clips: Clip): Deck =
        Deck("test", sampleRate).apply {
            this.clips = clips.toList()
            playing = true
        }

    /** Left-channel samples of a rendered block. */
    private fun leftChannel(deck: Deck, frames: Int): FloatArray {
        val out = FloatArray(frames * Deck.CHANNELS)
        deck.render(out, frames)
        return FloatArray(frames) { out[it * Deck.CHANNELS] }
    }

    @Test
    fun `cycle length is the longest clip extent`() {
        val deck = deckWith(rampClip(frames = 8), rampClip(frames = 4, startFrame = 20))
        assertEquals(24, deck.cycleFrames)
    }

    @Test
    fun `an empty deck renders silence`() {
        val deck = Deck("empty", sampleRate)
        val out = leftChannel(deck, 16)
        for (v in out) assertEquals(0f, v, 0f)
        assertEquals(0, deck.cycleFrames)
    }

    @Test
    fun `a paused deck renders silence and does not advance`() {
        val deck = deckWith(rampClip())
        deck.playing = false
        val out = leftChannel(deck, 8)
        for (v in out) assertEquals(0f, v, 0f)
        assertEquals(0.0, deck.playhead, 0.0)
    }

    @Test
    fun `plays a clip forward at unit rate`() {
        val deck = deckWith(rampClip(frames = 8))
        val out = leftChannel(deck, 8)
        for (i in 0 until 8) {
            assertEquals("frame $i", i / 100f, out[i], 2e-3f)
        }
    }

    @Test
    fun `a negative rate plays backwards`() {
        // The requested behaviour: past zero, the record runs in reverse. With a
        // cycle of 8 starting at 0, reverse reads 0 then wraps to 7, 6, 5...
        val deck = deckWith(rampClip(frames = 8, loop = true))
        deck.rate = -1.0
        val out = leftChannel(deck, 8)
        val expected = floatArrayOf(0f, 0.07f, 0.06f, 0.05f, 0.04f, 0.03f, 0.02f, 0.01f)
        for (i in 0 until 8) {
            assertEquals("frame $i", expected[i], out[i], 2e-3f)
        }
    }

    @Test
    fun `a zero rate holds the playhead`() {
        val deck = deckWith(rampClip(frames = 8, loop = true))
        deck.playhead = 3.0
        deck.rate = 0.0
        val out = leftChannel(deck, 6)
        for (v in out) assertEquals(0.03f, v, 2e-3f)
        assertEquals(3.0, deck.playhead, 1e-9)
    }

    @Test
    fun `a fractional rate interpolates rather than stepping`() {
        val deck = deckWith(rampClip(frames = 8, loop = true))
        deck.rate = 0.5
        val out = leftChannel(deck, 4)
        // Positions 0, 0.5, 1.0, 1.5 on a linear ramp.
        assertEquals(0.000f, out[0], 2e-3f)
        assertEquals(0.005f, out[1], 2e-3f)
        assertEquals(0.010f, out[2], 2e-3f)
        assertEquals(0.015f, out[3], 2e-3f)
    }

    @Test
    fun `a single looping clip circles the whole timeline`() {
        // "If the user adds a single sample to a deck and only plays that deck,
        // that sample is now looping" — one clip alone fills the revolution.
        val deck = deckWith(rampClip(frames = 4, loop = true))
        val out = leftChannel(deck, 12)
        for (i in 0 until 12) {
            assertEquals("frame $i", (i % 4) / 100f, out[i], 2e-3f)
        }
    }

    @Test
    fun `a non looping clip is silent outside its own span`() {
        val deck = deckWith(rampClip(frames = 4, startFrame = 4))
        // Timeline is 8 long; the clip occupies frames 4..7 only.
        val out = leftChannel(deck, 8)
        for (i in 0 until 4) assertEquals("frame $i", 0f, out[i], 1e-6f)
        for (i in 4 until 8) assertEquals("frame $i", (i - 4) / 100f, out[i], 2e-3f)
    }

    @Test
    fun `clips sum where they overlap`() {
        val deck = deckWith(
            rampClip(frames = 8, loop = true),
            rampClip(frames = 8, loop = true),
        )
        val out = leftChannel(deck, 8)
        for (i in 0 until 8) {
            assertEquals("frame $i", 2f * i / 100f, out[i], 3e-3f)
        }
    }

    @Test
    fun `clip gain scales its contribution`() {
        val deck = deckWith(rampClip(frames = 8, loop = true, gain = 0.5f))
        val out = leftChannel(deck, 8)
        for (i in 0 until 8) {
            assertEquals("frame $i", 0.5f * i / 100f, out[i], 2e-3f)
        }
    }

    @Test
    fun `deck gain scales the whole output`() {
        val deck = deckWith(rampClip(frames = 8, loop = true))
        deck.gain = 0.25f
        val out = leftChannel(deck, 8)
        for (i in 0 until 8) {
            assertEquals("frame $i", 0.25f * i / 100f, out[i], 2e-3f)
        }
    }

    @Test
    fun `bass boost changes the signal`() {
        // The previous implementation routed this gesture to a detached synth's
        // field, so it could not affect the music at all. Here it must.
        fun render(boostDb: Double): FloatArray {
            val deck = deckWith(
                Clip(
                    id = "tone",
                    buffer = PcmBuffer.monoFromFloat(
                        FloatArray(4096) { kotlin.math.sin(2.0 * Math.PI * 60.0 * it / sampleRate).toFloat() * 0.3f },
                        sampleRate,
                    ),
                    loop = true,
                ),
            )
            deck.bassBoostDb = boostDb
            return leftChannel(deck, 2048)
        }

        fun rms(samples: FloatArray): Double {
            var sum = 0.0
            for (v in samples) sum += v.toDouble() * v
            return kotlin.math.sqrt(sum / samples.size)
        }

        val flat = rms(render(0.0))
        val boosted = rms(render(12.0))
        assertTrue("60 Hz should be louder with bass boost: $flat vs $boosted", boosted > flat * 1.5)
    }

    @Test
    fun `treble cut attenuates high frequencies`() {
        fun render(trebleDb: Double): FloatArray {
            val deck = deckWith(
                Clip(
                    id = "tone",
                    buffer = PcmBuffer.monoFromFloat(
                        FloatArray(4096) { kotlin.math.sin(2.0 * Math.PI * 9000.0 * it / sampleRate).toFloat() * 0.3f },
                        sampleRate,
                    ),
                    loop = true,
                ),
            )
            deck.trebleDb = trebleDb
            return leftChannel(deck, 2048)
        }

        fun rms(samples: FloatArray): Double {
            var sum = 0.0
            for (v in samples) sum += v.toDouble() * v
            return kotlin.math.sqrt(sum / samples.size)
        }

        assertTrue(rms(render(-18.0)) < rms(render(0.0)) * 0.5)
    }

    @Test
    fun `output level tracks the rendered block`() {
        val deck = deckWith(
            Clip(
                id = "constant",
                buffer = PcmBuffer.monoFromFloat(FloatArray(64) { 0.4f }, sampleRate),
                loop = true,
            ),
        )
        leftChannel(deck, 32)
        assertEquals(0.4f, deck.outputLevel, 5e-3f)

        deck.playing = false
        leftChannel(deck, 32)
        assertEquals(0f, deck.outputLevel, 0f)
    }

    @Test
    fun `cycle position maps the playhead to a fraction of a revolution`() {
        val deck = deckWith(rampClip(frames = 100, loop = true))
        deck.playhead = 25.0
        assertEquals(0.25f, deck.cyclePosition(), 1e-6f)

        deck.seekToFraction(0.75f)
        assertEquals(75.0, deck.playhead, 1e-9)
    }

    @Test
    fun `cycle position is zero for an empty deck`() {
        assertEquals(0f, Deck("empty", sampleRate).cyclePosition(), 0f)
        Deck("empty", sampleRate).seekToFraction(0.5f)
    }

    @Test
    fun `shortening the timeline keeps the playhead inside it`() {
        val deck = deckWith(rampClip(frames = 100, loop = true))
        deck.playhead = 90.0
        deck.clips = listOf(rampClip(frames = 8, loop = true))
        assertTrue("playhead ${deck.playhead} escaped the new cycle", deck.playhead < 8.0)
    }

    @Test
    fun `clips decoded at a different sample rate play at the same musical speed`() {
        // A 48 kHz clip must be read 48000/44100 faster per output frame, or it
        // would sound flat and drift out of beat with a 44.1 kHz track.
        val deck = Deck("test", outputSampleRate = 44_100).apply {
            clips = listOf(
                Clip(
                    id = "48k",
                    buffer = PcmBuffer.monoFromFloat(FloatArray(4800) { it / 4800f }, 48_000),
                    loop = true,
                ),
            )
            playing = true
        }
        leftChannel(deck, 441)
        // 441 output frames should consume 480 source frames.
        assertEquals(480.0, deck.playhead, 1.0)
    }

    @Test
    fun `reset returns the deck to a stopped start state`() {
        val deck = deckWith(rampClip(frames = 8, loop = true))
        deck.playhead = 5.0
        deck.rate = -2.0
        deck.reset()
        assertEquals(0.0, deck.playhead, 0.0)
        assertEquals(1.0, deck.rate, 0.0)
        assertTrue(!deck.playing)
    }

    @Test
    fun `rejects an undersized output buffer`() {
        val deck = deckWith(rampClip())
        try {
            deck.render(FloatArray(4), 16)
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) {
            // intended
        }
    }

    @Test
    fun `reverse playback stays continuous with no click at the turnaround`() {
        // A scratch decelerating through zero into reverse must not produce a
        // discontinuity; this is what a seek-based implementation cannot do.
        val deck = deckWith(
            Clip(
                id = "tone",
                buffer = PcmBuffer.monoFromFloat(
                    FloatArray(8192) { kotlin.math.sin(2.0 * Math.PI * 220.0 * it / sampleRate).toFloat() * 0.5f },
                    sampleRate,
                ),
                loop = true,
            ),
        )
        deck.playhead = 4000.0

        val collected = ArrayList<Float>()
        var rate = 1.0
        val out = FloatArray(64 * Deck.CHANNELS)
        // Sweep the rate from +1 through zero to -1 over many small blocks.
        repeat(80) {
            deck.rate = rate
            deck.render(out, 64)
            for (i in 0 until 64) collected.add(out[i * Deck.CHANNELS])
            rate -= 0.025
        }

        var maxStep = 0f
        for (i in 1 until collected.size) {
            maxStep = maxOf(maxStep, abs(collected[i] - collected[i - 1]))
        }
        // One sample of a 220 Hz sine at 44.1 kHz steps by at most ~0.016 at
        // amplitude 0.5; allow headroom for |rate| up to 1.
        assertTrue("discontinuity of $maxStep at the turnaround", maxStep < 0.03f)
    }

    // --- Transport: nudging and seeking on the circular timeline ---

    @Test
    fun `nudging forward moves the playhead by the requested time`() {
        val deck = deckWith(rampClip(frames = sampleRate))
        deck.playhead = 0.0
        deck.nudgeSeconds(0.25)
        assertEquals(sampleRate * 0.25, deck.playhead, 1e-6)
    }

    @Test
    fun `nudging backwards past the start wraps to the end`() {
        // A nudge on a looping deck must behave like a nudge on a record:
        // pushing back past the start arrives at the end, not at a stop.
        val deck = deckWith(rampClip(frames = sampleRate))
        deck.playhead = 100.0
        deck.nudgeSeconds(-0.25)
        assertEquals(sampleRate - sampleRate * 0.25 + 100.0, deck.playhead, 1e-6)
        assertTrue(deck.playhead in 0.0..deck.cycleFrames.toDouble())
    }

    @Test
    fun `nudging past the end wraps rather than running off the timeline`() {
        val deck = deckWith(rampClip(frames = sampleRate))
        deck.playhead = sampleRate - 10.0
        deck.nudgeSeconds(1.0)
        assertEquals(sampleRate - 10.0, deck.playhead, 1e-6)
    }

    @Test
    fun `a nudge many revolutions long still lands on the circle`() {
        val deck = deckWith(rampClip(frames = sampleRate))
        deck.playhead = 0.0
        deck.nudgeSeconds(-12.5)
        assertTrue("landed at ${deck.playhead}", deck.playhead >= 0.0)
        assertTrue("landed at ${deck.playhead}", deck.playhead < deck.cycleFrames)
        assertEquals(sampleRate * 0.5, deck.playhead, 1e-6)
    }

    @Test
    fun `seeking places the playhead at an absolute time`() {
        val deck = deckWith(rampClip(frames = sampleRate))
        deck.playhead = 999.0
        deck.seekToSeconds(0.5)
        assertEquals(sampleRate * 0.5, deck.playhead, 1e-6)
    }

    @Test
    fun `transport does nothing on an empty deck rather than dividing by zero`() {
        val deck = Deck("empty", sampleRate)
        deck.nudgeSeconds(1.0)
        deck.seekToSeconds(1.0)
        assertEquals(0.0, deck.playhead, 0.0)
        assertEquals(0.0, deck.cycleSeconds, 0.0)
    }

    @Test
    fun `cycle length in seconds follows the material`() {
        val deck = deckWith(rampClip(frames = sampleRate / 2))
        assertEquals(0.5, deck.cycleSeconds, 1e-9)
    }
}
