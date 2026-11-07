package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Landmarks stored as a text column and read back. */
class MarkerCodecTest {

    private val points = listOf(
        PointOfInterest(32.5, PointOfInterest.Kind.DROP, 0.9f),
        PointOfInterest(96.0, PointOfInterest.Kind.BREAKDOWN, 0.6f),
        PointOfInterest(12.25, PointOfInterest.Kind.VOCAL, 0.4f),
    )

    @Test
    fun `landmarks round trip`() {
        assertEquals(points, MarkerCodec.decode(MarkerCodec.encode(points)))
    }

    @Test
    fun `no landmarks is a null column`() {
        assertNull(MarkerCodec.encode(emptyList()))
        assertTrue(MarkerCodec.decode(null).isEmpty())
        assertTrue(MarkerCodec.decode("").isEmpty())
    }

    @Test
    fun `a malformed record costs that landmark, not the rest`() {
        val csv = "DROP:10.0:1.0;NOPE:3.0:1.0;BREAKDOWN:x:1.0;BUILD:20.0;PEAK:30.0:0.5"

        val decoded = MarkerCodec.decode(csv)

        assertEquals(
            listOf(
                PointOfInterest(10.0, PointOfInterest.Kind.DROP, 1.0f),
                PointOfInterest(30.0, PointOfInterest.Kind.PEAK, 0.5f),
            ),
            decoded,
        )
    }

    @Test
    fun `negative and non-finite times are rejected`() {
        assertTrue(MarkerCodec.decode("DROP:-1.0:1.0;DROP:NaN:1.0;DROP:Infinity:1.0").isEmpty())
    }
}
