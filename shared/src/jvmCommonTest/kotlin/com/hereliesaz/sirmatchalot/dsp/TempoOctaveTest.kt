package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A tempo read as its half is a tempo read wrong: it puts a grid on every second
 * beat and, because bars are counted from it, every bar out of step.
 *
 * Swept across the whole range rather than at the usual round numbers, because the
 * fault only showed at tempi whose period is far from a whole number of frames
 * (121.5, 127.5, 134, 137.5 ...): a clean 120 or 128 never exposed it.
 */
class TempoOctaveTest {

    private fun read(bpm: Double): Double? {
        val audio = SignalFixtures.clickTrack(bpm, 60.0, firstBeatSeconds = 0.2)
        val envelope = OnsetDetector().detect(audio, SignalFixtures.SAMPLE_RATE)
        return TempoDetector().detect(envelope)?.bpm
    }

    private fun tempi(range: IntRange) = range.map { it + if (it % 2 == 0) 0.0 else 0.5 }

    @Test
    fun `every tempo from 90 to 149 BPM is read as itself`() {
        val wrong = tempi(90..149).mapNotNull { bpm ->
            val got = read(bpm)
            if (got == null || abs(got - bpm) > bpm * 0.005) "$bpm -> $got" else null
        }

        assertTrue("misread: $wrong", wrong.isEmpty())
    }

    @Test
    fun `fast tempi land on the pulse, as itself or its half`() {
        // 150 BPM and up is genuinely ambiguous with half: both are the same pulse,
        // and the planner's half/double-time ladder treats them alike.
        val wrong = tempi(150..175).mapNotNull { bpm ->
            val got = read(bpm)
            val ok = got != null && (abs(got - bpm) < bpm * 0.005 || abs(got - bpm / 2) < bpm * 0.005)
            if (ok) null else "$bpm -> $got"
        }

        assertTrue("off the pulse: $wrong", wrong.isEmpty())
    }
}
