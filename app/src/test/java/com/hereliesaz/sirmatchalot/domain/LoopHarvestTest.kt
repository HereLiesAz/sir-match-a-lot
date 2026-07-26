package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.dsp.LoopCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopHarvestTest {

    private fun candidate(start: Double, bars: Int = 4, score: Float, seconds: Double = 8.0) =
        LoopCandidate(startSeconds = start, bars = bars, durationSeconds = seconds, score = score)

    private fun source(id: String, vararg candidates: LoopCandidate) =
        LoopHarvest.Source(trackId = id, title = "Track $id", candidates = candidates.toList())

    @Test
    fun `every song gets its best loop before any song gets its second`() {
        // The whole point. Track A scores higher on everything; taking the best
        // eight globally would fill every pad from A.
        val sources = listOf(
            source("A", candidate(0.0, score = 0.99f), candidate(16.0, score = 0.98f)),
            source("B", candidate(0.0, score = 0.60f), candidate(16.0, score = 0.55f)),
            source("C", candidate(0.0, score = 0.51f)),
        )

        val plan = LoopHarvest.plan(sources, freePads = listOf(0, 1, 2, 3, 4, 5))

        assertEquals(listOf("A", "B", "C", "A", "B"), plan.map { it.trackId })
        assertEquals(listOf(0, 0, 0, 1, 1), plan.map { it.rankWithinTrack })
    }

    @Test
    fun `playlist order wins over score when pads run out`() {
        // One pad, and the second song is the stronger material. The playlist
        // was ordered by a person; that ordering is the answer.
        val sources = listOf(
            source("first", candidate(0.0, score = 0.60f)),
            source("second", candidate(0.0, score = 0.99f)),
        )

        val plan = LoopHarvest.plan(sources, freePads = listOf(3))

        assertEquals(1, plan.size)
        assertEquals("first", plan[0].trackId)
        assertEquals("pads fill in the order given", 3, plan[0].padIndex)
    }

    @Test
    fun `weak candidates are not worth a pad`() {
        val sources = listOf(source("A", candidate(0.0, score = 0.2f), candidate(16.0, score = 0.9f)))

        val plan = LoopHarvest.plan(sources, freePads = listOf(0, 1), minimumScore = 0.5f)

        assertEquals(1, plan.size)
        assertEquals(0.9f, plan[0].candidate.score, 1e-6f)
    }

    @Test
    fun `overlapping candidates from one track are the same loop twice`() {
        // A 4-bar and an 8-bar loop starting at the same second are one loop at
        // two lengths, and a bank of the same bar twice is not a bank.
        val sources = listOf(
            source(
                "A",
                candidate(0.0, bars = 8, score = 0.95f, seconds = 16.0),
                candidate(4.0, bars = 4, score = 0.90f, seconds = 8.0),
                candidate(40.0, bars = 4, score = 0.80f, seconds = 8.0),
            ),
        )

        val plan = LoopHarvest.plan(sources, freePads = listOf(0, 1, 2))

        assertEquals(2, plan.size)
        assertEquals(listOf(0.0, 40.0), plan.map { it.candidate.startSeconds })
    }

    @Test
    fun `one song cannot take the whole bank when capped`() {
        val sources = listOf(
            source("A", candidate(0.0, score = 0.9f), candidate(20.0, score = 0.9f), candidate(40.0, score = 0.9f)),
            source("B", candidate(0.0, score = 0.9f)),
        )

        val plan = LoopHarvest.plan(sources, freePads = listOf(0, 1, 2, 3), maxPerTrack = 1)

        assertEquals(2, plan.size)
        assertEquals(listOf("A", "B"), plan.map { it.trackId })
    }

    @Test
    fun `a playlist with nothing loopable plans nothing`() {
        val sources = listOf(source("A"), source("B", candidate(0.0, score = 0.1f)))

        assertTrue(LoopHarvest.plan(sources, freePads = listOf(0, 1)).isEmpty())
    }

    @Test
    fun `no free pads means no work`() {
        val sources = listOf(source("A", candidate(0.0, score = 0.9f)))

        assertTrue(LoopHarvest.plan(sources, freePads = emptyList()).isEmpty())
    }

    @Test
    fun `planning terminates when candidates run out before pads do`() {
        // Two candidates, six pads. The round-robin has to notice it placed
        // nothing this round rather than looping for ever.
        val sources = listOf(source("A", candidate(0.0, score = 0.9f)), source("B", candidate(0.0, score = 0.9f)))

        val plan = LoopHarvest.plan(sources, freePads = listOf(0, 1, 2, 3, 4, 5))

        assertEquals(2, plan.size)
    }

    @Test
    fun `decode order names each track once, in playlist order`() {
        val sources = listOf(
            source("A", candidate(0.0, score = 0.9f), candidate(20.0, score = 0.8f)),
            source("B", candidate(0.0, score = 0.9f)),
        )

        val plan = LoopHarvest.plan(sources, freePads = listOf(0, 1, 2))

        // A appears twice in the plan; it is decoded once. A playlist does not
        // fit in memory all at once, so this is what makes the harvest possible.
        assertEquals(listOf("A", "B"), LoopHarvest.decodeOrder(plan))
    }

    @Test
    fun `labels name the song and the loop length`() {
        val sources = listOf(source("A", candidate(0.0, bars = 8, score = 0.9f)))

        assertEquals("Track A 8bar", LoopHarvest.plan(sources, freePads = listOf(0))[0].label)
    }
}
