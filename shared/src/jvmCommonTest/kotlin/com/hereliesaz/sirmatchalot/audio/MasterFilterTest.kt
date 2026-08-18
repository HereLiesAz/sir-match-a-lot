package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The XY pad's filter.
 *
 * The requirement these cover is "the pad must affect the music", which the
 * previous implementation failed by generating a separate tone instead. So the
 * assertions are about what happens to a signal passed through the master bus,
 * not about whether the pad reports a position.
 */
class MasterFilterTest {

    private val sampleRate = 44_100

    /**
     * Pad position putting the lowpass at roughly 2 kHz:
     * `20000 * (80/20000)^depth = 2000` gives depth ~= 0.417.
     */
    private val MID_LEFT = -0.417f

    // --- Bypass ---

    @Test
    fun `a disengaged filter leaves the signal untouched`() {
        val filter = MasterFilter(sampleRate)
        val signal = tone(1_000.0, 2_048)
        val original = signal.copyOf()
        filter.process(signal, 2_048 / Deck.CHANNELS)
        for (i in signal.indices) assertEquals("at $i", original[i], signal[i], 0f)
    }

    @Test
    fun `the centre dead zone passes the signal through`() {
        val filter = MasterFilter(sampleRate).apply {
            enabled = true
            x = 0f
            y = 0.5f
        }
        val signal = tone(1_000.0, 2_048)
        val original = signal.copyOf()
        filter.process(signal, 2_048 / Deck.CHANNELS)
        for (i in signal.indices) assertEquals("at $i", original[i], signal[i], 0f)
        assertFalse(filter.active)
    }

    @Test
    fun `releasing a resonant sweep does not step the level`() {
        // The dead zone was a branch, not a fade: inside it the filter ran and
        // its output was discarded, outside it the output was multiplied by
        // `gainCompensation`. Crossing the boundary switched between `input` and
        // `input * 1/sqrt(Q)` in one sample — at Q = 8 a +9.03 dB step, on every
        // release of the pad and every sweep through centre. The comment on the
        // dead zone said it existed so that entering it does not click.
        val filter = MasterFilter(sampleRate).apply {
            enabled = true
            x = 0.6f
            y = 1f
        }
        val frames = 512
        val block = FloatArray(frames * Deck.CHANNELS)

        // Settle at full resonance on a constant signal, where any level step is
        // the filter's doing and not the signal's.
        repeat(40) {
            java.util.Arrays.fill(block, 0.25f)
            filter.process(block, frames)
        }

        filter.release()

        var previous = Float.NaN
        var worstStep = 0f
        // Long enough for the smoother to glide all the way back through the
        // dead zone to neutral.
        repeat(400) {
            java.util.Arrays.fill(block, 0.25f)
            filter.process(block, frames)
            for (value in block) {
                if (!previous.isNaN()) {
                    val step = kotlin.math.abs(value - previous)
                    if (step > worstStep) worstStep = step
                }
                previous = value
            }
        }

        // 0.25 -> 0.0884 is what the branch did in a single sample. The smoother
        // moves at most a fraction of a percent per sample, so anything close to
        // that magnitude is the discontinuity coming back.
        assertTrue(
            "level stepped by $worstStep in one sample on release",
            worstStep < 0.01f,
        )
    }

    // --- It actually filters ---

    @Test
    fun `sweeping left attenuates high frequencies and keeps low ones`() {
        // Measured at a mid sweep, where the cutoff is about 2 kHz. At a *full*
        // left sweep the lowpass sits at 80 Hz and correctly attenuates 500 Hz
        // too, so that position says nothing about whether the filter passes
        // lows — it only says the sweep goes deep, which the next test checks.
        val lowDb = gainAfterSettling(500.0, x = MID_LEFT, y = 0f)
        val highDb = gainAfterSettling(10_000.0, x = MID_LEFT, y = 0f)

        assertTrue("500 Hz lost ${"%.1f".format(lowDb)} dB", lowDb > -3.0)
        assertTrue("10 kHz only ${"%.1f".format(highDb)} dB down", highDb < -20.0)
    }

    @Test
    fun `a full left sweep closes the band right down`() {
        val highDb = gainAfterSettling(10_000.0, x = -1f, y = 0f)
        assertTrue("10 kHz only ${"%.1f".format(highDb)} dB down", highDb < -60.0)
    }

    @Test
    fun `sweeping right attenuates low frequencies and keeps high ones`() {
        val lowDb = gainAfterSettling(100.0, x = 1f, y = 0f)
        val highDb = gainAfterSettling(12_000.0, x = 1f, y = 0f)

        assertTrue("100 Hz only ${"%.1f".format(lowDb)} dB down", lowDb < -30.0)
        assertTrue("12 kHz lost ${"%.1f".format(highDb)} dB", highDb > -6.0)
    }

    @Test
    fun `full sweeps are monotonic in the direction they claim`() {
        // A partial left sweep must attenuate 10 kHz less than a full one, or the
        // gesture is not doing what the finger says.
        val partial = gainAfterSettling(10_000.0, x = -0.5f, y = 0f)
        val full = gainAfterSettling(10_000.0, x = -1f, y = 0f)
        assertTrue(
            "half sweep ${"%.1f".format(partial)} dB, full ${"%.1f".format(full)} dB",
            full < partial,
        )
    }

    @Test
    fun `resonance emphasises the cutoff region`() {
        // At the lowpass cutoff, raising Y should raise the level relative to a
        // flat Q — that emphasis is what makes the sweep expressive.
        // x = -1 puts the lowpass at LOW_PASS_MIN.
        val cutoff = MasterFilter.LOW_PASS_MIN
        val flat = gainAfterSettling(cutoff, x = -1f, y = 0f)
        val resonant = gainAfterSettling(cutoff, x = -1f, y = 1f)
        assertTrue(
            "flat ${"%.1f".format(flat)} dB, resonant ${"%.1f".format(resonant)} dB",
            resonant > flat + 3.0,
        )
    }

    // --- Behaviour under a moving finger ---

    @Test
    fun `a fast sweep at low resonance produces no discontinuity`() {
        // Parameters are smoothed per frame. If they were applied per block, or
        // the coefficients swapped abruptly, the output would step — audible as a
        // click on every gesture.
        //
        // Measured at y = 0. At high resonance a fast sweep legitimately produces
        // a chirp whose bandwidth far exceeds the input tone's, so a
        // largest-sample-step bound there would be measuring the effect rather
        // than any defect. Block-size independence is the test that covers the
        // resonant case.
        val filter = MasterFilter(sampleRate).apply {
            enabled = true
            y = 0f
        }
        val frames = 64
        val collected = ArrayList<Float>()
        val buffer = FloatArray(frames * Deck.CHANNELS)
        var position = 0

        var target = 0f
        repeat(200) {
            target -= 0.005f
            filter.x = target
            for (frame in 0 until frames) {
                val sample = (0.4 * sin(2 * PI * 220.0 * position / sampleRate)).toFloat()
                buffer[frame * Deck.CHANNELS] = sample
                buffer[frame * Deck.CHANNELS + 1] = sample
                position++
            }
            filter.process(buffer, frames)
            for (frame in 0 until frames) collected.add(buffer[frame * Deck.CHANNELS])
        }

        var maxStep = 0f
        for (i in 1 until collected.size) {
            maxStep = maxOf(maxStep, abs(collected[i] - collected[i - 1]))
        }
        // One sample of a 220 Hz sine at 44.1 kHz steps by at most ~0.013 at
        // amplitude 0.4. Allow headroom for the filter's own transient and still
        // catch a genuine discontinuity.
        assertTrue("discontinuity of $maxStep during the sweep", maxStep < 0.05f)
    }

    @Test
    fun `output does not depend on how the frames are divided into blocks`() {
        // The strongest statement available about per-block artefacts: with the
        // pad held still, processing 2048 frames at once and processing them as
        // four blocks of 512 must give the same samples. Anything that resets
        // filter state, re-smooths, or recomputes coefficients per call rather
        // than per frame would diverge here — including at high resonance, where
        // the largest-step test cannot distinguish artefact from effect.
        fun processed(blockSize: Int): FloatArray {
            val filter = MasterFilter(sampleRate).apply {
                enabled = true
                x = -0.5f
                y = 1f
            }
            val total = 2_048
            val all = FloatArray(total * Deck.CHANNELS)
            for (frame in 0 until total) {
                val sample = (0.4 * sin(2 * PI * 220.0 * frame / sampleRate)).toFloat()
                all[frame * Deck.CHANNELS] = sample
                all[frame * Deck.CHANNELS + 1] = sample
            }
            var offset = 0
            val scratch = FloatArray(blockSize * Deck.CHANNELS)
            while (offset < total) {
                val count = minOf(blockSize, total - offset)
                System.arraycopy(all, offset * Deck.CHANNELS, scratch, 0, count * Deck.CHANNELS)
                filter.process(scratch, count)
                System.arraycopy(scratch, 0, all, offset * Deck.CHANNELS, count * Deck.CHANNELS)
                offset += count
            }
            return all
        }

        val whole = processed(2_048)
        val split = processed(512)
        for (i in whole.indices) {
            assertEquals("sample $i", whole[i], split[i], 1e-6f)
        }
    }

    @Test
    fun `releasing the pad glides back to unity rather than snapping`() {
        val filter = MasterFilter(sampleRate).apply {
            enabled = true
            x = -1f
            y = 0f
        }
        // Settle into a deep lowpass.
        run(filter, 10_000.0, blocks = 200)
        assertTrue(filter.active)

        filter.release()
        // Immediately after release the filter must not be wide open — that jump
        // is exactly the click smoothing exists to avoid.
        val firstBlock = run(filter, 10_000.0, blocks = 1)
        assertTrue(
            "released to ${"%.1f".format(firstBlock)} dB in one block",
            firstBlock < -10.0,
        )

        // Given time, it opens fully and reports itself inactive.
        val settled = run(filter, 10_000.0, blocks = 400)
        assertTrue("settled at ${"%.1f".format(settled)} dB", settled > -1.0)
        assertFalse(filter.active)
    }

    @Test
    fun `high resonance does not run away in level`() {
        // Without gain compensation a Q of 8 would add roughly 18 dB at the
        // cutoff, slamming the limiter and ducking the whole mix.
        val resonant = gainAfterSettling(MasterFilter.LOW_PASS_MIN, x = -1f, y = 1f)
        assertTrue("resonant peak at ${"%.1f".format(resonant)} dB", resonant < 12.0)
    }

    @Test
    fun `the mixer routes the master bus through the filter`() {
        // The requirement is that the pad affects the music, so this asserts on
        // the mixer's own output rather than on the filter in isolation.
        val deck = Deck("A", sampleRate).apply {
            clips = listOf(
                Clip(
                    "c",
                    PcmBuffer.monoFromFloat(
                        FloatArray(sampleRate) { (0.5 * sin(2 * PI * 10_000.0 * it / sampleRate)).toFloat() },
                        sampleRate,
                    ),
                    loop = true,
                ),
            )
            playing = true
        }
        val mixer = Mixer(deck, Deck("B", sampleRate), sampleRate, maxFrames = 512)
        mixer.crossfade = 0f
        mixer.masterGain = 1f

        val open = mixerLevel(mixer, blocks = 40)
        mixer.filter.enabled = true
        mixer.filter.x = -1f
        mixer.filter.y = 0f
        val filtered = mixerLevel(mixer, blocks = 400)

        assertTrue(
            "open ${"%.4f".format(open)}, filtered ${"%.4f".format(filtered)}",
            filtered < open * 0.1f,
        )
    }

    // --- Helpers ---

    private fun tone(frequency: Double, samples: Int): FloatArray =
        FloatArray(samples) { (0.5 * sin(2 * PI * frequency * (it / Deck.CHANNELS) / sampleRate)).toFloat() }

    /**
     * Gain in dB applied to a [frequency] tone once the filter's smoothing has
     * settled at the given pad position.
     */
    private fun gainAfterSettling(frequency: Double, x: Float, y: Float): Double {
        val filter = MasterFilter(sampleRate).apply {
            enabled = true
            this.x = x
            this.y = y
        }
        // Long enough for the one-pole smoothing to arrive and the biquad state
        // to reach steady state.
        run(filter, frequency, blocks = 600)
        return run(filter, frequency, blocks = 20)
    }

    /**
     * Pushes a tone through [filter] and returns the output level in dB relative
     * to the input, measured over the final block.
     */
    private fun run(filter: MasterFilter, frequency: Double, blocks: Int): Double {
        val frames = 512
        val buffer = FloatArray(frames * Deck.CHANNELS)
        var position = phase
        var lastRe = 0.0
        var lastIm = 0.0
        var count = 0

        repeat(blocks) {
            val start = position
            for (frame in 0 until frames) {
                val sample = (0.5 * sin(2 * PI * frequency * position / sampleRate)).toFloat()
                buffer[frame * Deck.CHANNELS] = sample
                buffer[frame * Deck.CHANNELS + 1] = sample
                position++
            }
            filter.process(buffer, frames)

            lastRe = 0.0
            lastIm = 0.0
            count = frames
            for (frame in 0 until frames) {
                val angle = 2 * PI * frequency * (start + frame) / sampleRate
                lastRe += buffer[frame * Deck.CHANNELS] * kotlin.math.cos(angle)
                lastIm -= buffer[frame * Deck.CHANNELS] * sin(angle)
            }
        }
        phase = position
        val amplitude = 2.0 * hypot(lastRe, lastIm) / count
        if (amplitude <= 0.0) return -200.0
        return 20 * log10(amplitude / 0.5)
    }

    private var phase = 0

    /** RMS of the mixer's output over the final block of [blocks]. */
    private fun mixerLevel(mixer: Mixer, blocks: Int): Float {
        val frames = 512
        val out = FloatArray(frames * Deck.CHANNELS)
        repeat(blocks) { mixer.render(out, frames) }
        var sum = 0.0
        for (v in out) sum += v.toDouble() * v
        return sqrt(sum / out.size).toFloat()
    }
}
