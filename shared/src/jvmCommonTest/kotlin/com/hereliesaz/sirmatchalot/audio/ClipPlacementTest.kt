package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipPlacementTest {

    @Test
    fun `leaves a placement alone when nothing is in the way`() {
        val start = ClipPlacement.nonOverlappingStart(
            desiredStart = 500,
            length = 100,
            occupied = listOf(0 to 200),
        )
        assertEquals(500, start)
    }

    @Test
    fun `an empty deck accepts any placement`() {
        val start = ClipPlacement.nonOverlappingStart(
            desiredStart = 50,
            length = 100,
            occupied = emptyList(),
        )
        assertEquals(50, start)
    }

    @Test
    fun `pulls back to just before the clip it would overlap`() {
        // Dropped at 150, which is inside the existing clip's [100, 300).
        // The nearest free edge behind it is 0 — before 100 leaves only 100
        // frames of gap, too little for a 100-frame clip landing exactly at
        // its edge, so 0 is the closer valid candidate to 150 than 300.
        val start = ClipPlacement.nonOverlappingStart(
            desiredStart = 150,
            length = 100,
            occupied = listOf(100 to 300),
        )
        assertEquals(0, start)
    }

    @Test
    fun `pushes forward to just after the clip when that is nearer`() {
        val start = ClipPlacement.nonOverlappingStart(
            desiredStart = 280,
            length = 50,
            occupied = listOf(100 to 300),
        )
        assertEquals(300, start)
    }

    @Test
    fun `slots into a gap between two clips`() {
        // Dropped at 290, overlapping the second clip's [300, 500) by ten
        // frames. Both edges of the [200, 300) gap are candidates; 260 is the
        // one nearer the drop.
        val start = ClipPlacement.nonOverlappingStart(
            desiredStart = 290,
            length = 40,
            occupied = listOf(0 to 200, 300 to 500),
        )
        assertEquals(260, start)
    }

    @Test
    fun `appends after the last clip when nothing fits`() {
        val start = ClipPlacement.nonOverlappingStart(
            desiredStart = 50,
            length = 1000,
            occupied = listOf(0 to 200, 200 to 400),
        )
        assertEquals(400, start)
    }

    @Test
    fun `a clip is never blocked by itself`() {
        val existing = listOf(
            Clip(id = "a", buffer = PcmBuffer.silence(200, 1, 44_100), startFrame = 0),
            Clip(id = "moving", buffer = PcmBuffer.silence(100, 1, 44_100), startFrame = 400),
        )
        val start = ClipPlacement.nonOverlappingStart(
            desiredStart = 410,
            length = 100,
            existingClips = existing,
            excludingId = "moving",
        )
        assertEquals(410, start)
    }

    @Test
    fun `zero length placements pass through untouched`() {
        val start = ClipPlacement.nonOverlappingStart(
            desiredStart = 150,
            length = 0,
            occupied = listOf(100 to 300),
        )
        assertEquals(150, start)
    }
}
