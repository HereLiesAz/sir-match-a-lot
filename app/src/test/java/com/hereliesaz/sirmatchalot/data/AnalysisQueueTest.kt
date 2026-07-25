package com.hereliesaz.sirmatchalot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What pressing "analyse" decides to do.
 *
 * The reported bug was "pressing analyse does nothing". Three of the four
 * possible outcomes genuinely *were* nothing — an empty library, an already
 * analysed one, and one whose entries have no audio file — and the old code
 * reported none of them, because it only ever spoke from inside a successful
 * per-track analysis. So the assertions here are as much about the message as
 * about the selection.
 */
class AnalysisQueueTest {

    private fun track(
        title: String,
        source: String? = "file:///music/$title.mp3",
        analysed: Boolean = false,
    ) = Track(
        title = title,
        artist = "Artist",
        sourceUri = source,
        analysisVersion = if (analysed) Track.CURRENT_ANALYSIS_VERSION else 0,
    )

    // --- Selection ---

    @Test
    fun `unanalysed tracks with audio are queued`() {
        val plan = AnalysisQueue.plan(listOf(track("a"), track("b")))
        assertEquals(listOf("a", "b"), plan.toAnalyse.map { it.title })
        assertTrue(plan.hasWork)
    }

    @Test
    fun `already analysed tracks are not re-analysed`() {
        val plan = AnalysisQueue.plan(listOf(track("a", analysed = true), track("b")))
        assertEquals(listOf("b"), plan.toAnalyse.map { it.title })
        assertEquals(listOf("a"), plan.alreadyAnalysed.map { it.title })
    }

    @Test
    fun `a track with no audio file is separated rather than queued`() {
        // This is the case that used to hit a bare `?: return` inside
        // analyseTrack and vanish without a word.
        val plan = AnalysisQueue.plan(listOf(track("named only", source = null)))
        assertTrue(plan.toAnalyse.isEmpty())
        assertEquals(listOf("named only"), plan.missingAudio.map { it.title })
        assertFalse(plan.hasWork)
    }

    @Test
    fun `a blank source counts as no audio, not as a source`() {
        val plan = AnalysisQueue.plan(listOf(track("blank", source = "   ")))
        assertEquals(1, plan.missingAudio.size)
        assertTrue(plan.toAnalyse.isEmpty())
    }

    @Test
    fun `a rescan re-analyses everything that has audio`() {
        val plan = AnalysisQueue.planFullRescan(
            listOf(track("a", analysed = true), track("b"), track("c", source = null)),
        )
        assertEquals(listOf("a", "b"), plan.toAnalyse.map { it.title })
        assertEquals(listOf("c"), plan.missingAudio.map { it.title })
    }

    // --- Every idle outcome says something ---

    @Test
    fun `an empty library says so`() {
        val plan = AnalysisQueue.plan(emptyList())
        assertFalse(plan.hasWork)
        assertTrue(plan.idleMessage(), plan.idleMessage().contains("empty", ignoreCase = true))
    }

    @Test
    fun `a fully analysed library says so`() {
        val plan = AnalysisQueue.plan(listOf(track("a", analysed = true), track("b", analysed = true)))
        assertFalse(plan.hasWork)
        assertTrue(plan.idleMessage(), plan.idleMessage().contains("already analysed"))
        assertTrue(plan.idleMessage(), plan.idleMessage().contains("2"))
    }

    @Test
    fun `a library of entries with no audio explains why nothing happened`() {
        val plan = AnalysisQueue.plan(listOf(track("x", source = null), track("y", source = null)))
        assertFalse(plan.hasWork)
        val message = plan.idleMessage()
        assertTrue(message, message.contains("no audio file"))
        assertTrue(message, message.contains("2"))
    }

    @Test
    fun `a mixed library mentions both what is done and what is unlinked`() {
        val plan = AnalysisQueue.plan(
            listOf(track("a", analysed = true), track("x", source = null)),
        )
        assertFalse(plan.hasWork)
        val message = plan.idleMessage()
        assertTrue(message, message.contains("analysed"))
        assertTrue(message, message.contains("no file"))
    }

    @Test
    fun `no idle outcome is ever silent`() {
        // The property that actually matters: whatever the library looks like,
        // pressing the button produces words.
        val libraries = listOf(
            emptyList(),
            listOf(track("a", analysed = true)),
            listOf(track("x", source = null)),
            listOf(track("a", analysed = true), track("x", source = null)),
        )
        for (library in libraries) {
            val plan = AnalysisQueue.plan(library)
            assertFalse("expected no work for $library", plan.hasWork)
            assertTrue("silent for $library", plan.idleMessage().isNotBlank())
        }
    }

    // --- Starting message ---

    @Test
    fun `the start message counts the work`() {
        val plan = AnalysisQueue.plan(listOf(track("a"), track("b"), track("c")))
        assertEquals("Analysing 3 tracks...", plan.startMessage())
    }

    @Test
    fun `one track is not called one tracks`() {
        val plan = AnalysisQueue.plan(listOf(track("a")))
        assertEquals("Analysing 1 track...", plan.startMessage())
    }

    @Test
    fun `the start message mentions what is being skipped`() {
        val plan = AnalysisQueue.plan(listOf(track("a"), track("x", source = null)))
        assertTrue(plan.startMessage(), plan.startMessage().contains("1 skipped"))
    }

    @Test
    fun `total counts every track exactly once`() {
        val tracks = listOf(
            track("a"),
            track("b", analysed = true),
            track("x", source = null),
        )
        val plan = AnalysisQueue.plan(tracks)
        assertEquals(tracks.size, plan.total)
    }
}
