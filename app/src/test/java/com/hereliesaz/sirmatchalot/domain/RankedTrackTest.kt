package com.hereliesaz.sirmatchalot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every track carrying its own match.
 *
 * The app measured tempo and key for every track, scored every pairing, and
 * delivered all of it as an optional sort order on the Library tab. The list a
 * performer actually drags from — the strip under the platter — was the raw
 * table in insertion order, with nothing on any card to say what would mix and
 * what would clash. The measurement existed; the answer never reached the
 * screen where the choice is made.
 *
 * Two properties matter, and they are what these tests are for. The score must
 * travel *with* its track, so no call site has to re-pair them by id. And a
 * track that cannot be scored must carry no score, rather than a zero that
 * reads as "will not mix".
 */
class RankedTrackTest {

    private fun library() = listOf(
        track("perfect", bpm = 128.0, camelot = "8A"),
        track("neighbour", bpm = 128.0, camelot = "9A"),
        track("distant", bpm = 128.0, camelot = "2B"),
        track("unmeasured", bpm = null, camelot = null),
    )

    // --- Scores travel with their tracks ---

    @Test
    fun `every track comes back, matched or not`() {
        // Not a filtered list of the good ones. A DJ who wants the fourth track
        // wants to know what it will take, not to have it disappear.
        val ranked = MixPlanner.rank(library(), reference = track("ref", camelot = "8A"))
        assertEquals(4, ranked.size)
        assertEquals(
            library().map { it.id },
            ranked.map { it.track.id },
        )
    }

    @Test
    fun `an unmeasured track carries no score rather than a zero`() {
        // Zero would read as "will not mix", which is a claim. "Not measured"
        // is the truth, and the UI has to be able to tell them apart.
        val ranked = MixPlanner.rank(library(), reference = track("ref", camelot = "8A"))
        val unmeasured = ranked.first { it.track.id == "unmeasured" }
        assertNull(unmeasured.match)
        assertNull(unmeasured.score)
        assertNull(unmeasured.advice())
    }

    @Test
    fun `with nothing loaded there are no scores at all`() {
        // "What mixes next" is meaningless with no next. Every card falls back
        // to showing what the track *is* — its own tempo and key.
        val ranked = MixPlanner.rank(library(), reference = null)
        assertEquals(4, ranked.size)
        assertTrue(ranked.all { it.match == null && !it.isReference })
    }

    @Test
    fun `the reference is marked rather than scored against itself`() {
        val reference = track("perfect", bpm = 128.0, camelot = "8A")
        val ranked = MixPlanner.rank(library(), reference)
        val self = ranked.first { it.track.id == "perfect" }
        assertTrue("the reference must be identifiable", self.isReference)
        assertNull("a track does not mix with itself at 100%", self.match)
    }

    @Test
    fun `a compatible track scores above a distant one`() {
        val ranked = MixPlanner.rank(library(), reference = track("ref", bpm = 128.0, camelot = "8A"))
        val neighbour = requireNotNull(ranked.first { it.track.id == "neighbour" }.score)
        val distant = requireNotNull(ranked.first { it.track.id == "distant" }.score)
        assertTrue("neighbour $neighbour should beat distant $distant", neighbour > distant)
    }

    // --- The order matches the numbers ---

    @Test
    fun `ordering by score puts the best first`() {
        val ranked = MixPlanner.byMixScore(library(), track("ref", bpm = 128.0, camelot = "8A"))
        val scores = ranked.mapNotNull { it.score }
        assertEquals("the list must descend", scores.sortedDescending(), scores)
    }

    @Test
    fun `the order agrees with the number on every card`() {
        // The property that makes the list readable at all: a card showing 82%
        // must not sit above one showing 91%. Sorting by Camelot distance while
        // labelling with the overall score produced exactly that, and there was
        // nothing on screen to explain it.
        val ranked = MixPlanner.byMixScore(library(), track("ref", bpm = 128.0, camelot = "8A"))
        for (i in 0 until ranked.size - 1) {
            val here = ranked[i].score ?: -1
            val next = ranked[i + 1].score ?: -1
            assertTrue(
                "${ranked[i].track.id} ($here) sorted above ${ranked[i + 1].track.id} ($next)",
                here >= next,
            )
        }
    }

    @Test
    fun `unmeasured tracks sort last, not first`() {
        val ranked = MixPlanner.byMixScore(library(), track("ref", bpm = 128.0, camelot = "8A"))
        assertEquals("unmeasured", ranked.last().track.id)
    }

    @Test
    fun `with no reference the given order is preserved`() {
        // Whatever the caller sorted by — title, energy, import order — survives
        // when there is nothing to rank against. Ranking must not silently
        // reorder a list it cannot improve.
        val input = library()
        val ranked = MixPlanner.byMixScore(input, reference = null)
        assertEquals(input.map { it.id }, ranked.map { it.track.id })
    }

    @Test
    fun `ranking an empty library is empty, not an error`() {
        assertTrue(MixPlanner.rank(emptyList(), track("ref")).isEmpty())
        assertTrue(MixPlanner.byMixScore(emptyList(), track("ref")).isEmpty())
        assertTrue(MixPlanner.byMixScore(emptyList(), null).isEmpty())
    }

    // --- What the card says ---

    @Test
    fun `advice names the key move`() {
        val ranked = MixPlanner.rank(
            listOf(track("up", bpm = 128.0, camelot = "9A")),
            reference = track("ref", bpm = 128.0, camelot = "8A"),
        )
        val advice = requireNotNull(ranked.single().advice())
        assertTrue("advice was empty", advice.isNotBlank())
        assertEquals(ranked.single().match?.keyAdvice, advice.substringBefore(" ·"))
    }

    @Test
    fun `a large tempo gap is called out, since the stretch will be audible`() {
        // 128 against 100 is well past what a nudge covers, and conforming it
        // means a stretch big enough to hear. Worth a word before it loads.
        val ranked = MixPlanner.rank(
            listOf(track("slow", bpm = 100.0, camelot = "8A")),
            reference = track("ref", bpm = 128.0, camelot = "8A"),
        )
        val entry = ranked.single()
        assertNotNull(entry.match)
        assertFalse("100 against 128 is not a nudge", entry.match!!.canMixWithNudge)
        assertTrue(
            "the tempo gap should be mentioned: ${entry.advice()}",
            entry.advice()!!.contains("tempo") || entry.advice()!!.contains("time"),
        )
    }

    @Test
    fun `a track needing nothing says only the key advice`() {
        val ranked = MixPlanner.rank(
            listOf(track("twin", bpm = 128.0, camelot = "8A")),
            reference = track("ref", bpm = 128.0, camelot = "8A"),
        )
        val entry = ranked.single()
        assertEquals(entry.match?.keyAdvice, entry.advice())
    }
}
