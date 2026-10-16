package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnsetDetectorTest {

    @Test
    fun `the first frame is not a spurious onset`() {
        // A steady tone has no onsets after it starts. Frame 0 used to be
        // differenced against an all-zero spectrum, so it carried the whole
        // spectrum as "flux" and became the envelope's maximum.
        val tone = SignalFixtures.sine(440.0, 3.0)
        val envelope = OnsetDetector().detect(tone, SignalFixtures.SAMPLE_RATE)
        assertEquals(0f, envelope.values[0], 0f)
    }

    @Test
    fun `real clicks still dominate the envelope`() {
        val clicks = SignalFixtures.clickTrack(120.0, 6.0)
        val envelope = OnsetDetector().detect(clicks, SignalFixtures.SAMPLE_RATE)
        var peak = 0
        for (i in envelope.values.indices) if (envelope.values[i] > envelope.values[peak]) peak = i
        assertTrue("peak should be a click, not frame 0", peak > 0)
        assertEquals(1f, envelope.values[peak], 1e-6f)
    }
}
