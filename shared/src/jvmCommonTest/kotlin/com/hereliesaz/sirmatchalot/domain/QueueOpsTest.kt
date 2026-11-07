package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Editing a queue, and planning a fixed running order. */
class QueueOpsTest {

    private fun track(id: String, bpm: Double? = 120.0, key: String? = "8A", energy: Int = 5) =
        Track(id = id, title = id, artist = "x", bpm = bpm, camelotKey = key, energyLevel = energy, durationMs = 200_000)

    private val abcde = "abcde".map { track(it.toString()) }
    private fun ids(list: List<Track>) = list.joinToString("") { it.id }

    @Test
    fun `a track moves to a position`() {
        assertEquals("bcade", ids(QueueOps.moved(abcde, 0, 2)))
        assertEquals("eabcd", ids(QueueOps.moved(abcde, 4, 0)))
    }

    @Test
    fun `moves out of range or to the same place change nothing`() {
        assertSame(abcde, QueueOps.moved(abcde, 2, 2))
        assertSame(abcde, QueueOps.moved(abcde, -1, 2))
        assertSame(abcde, QueueOps.moved(abcde, 1, 9))
    }

    @Test
    fun `a track is removed`() {
        assertEquals("abde", ids(QueueOps.removed(abcde, 2)))
        assertSame(abcde, QueueOps.removed(abcde, 7))
    }

    @Test
    fun `a track is inserted, and a queued one is moved rather than duplicated`() {
        assertEquals("abxcde", ids(QueueOps.inserted(abcde, track("x"), 2)))
        assertEquals("abcdex", ids(QueueOps.inserted(abcde, track("x"))))
        assertEquals("dabce", ids(QueueOps.inserted(abcde, abcde[3], 0)))
    }

    @Test
    fun `play next goes straight after the current track`() {
        assertEquals("abxcde", ids(QueueOps.playNext(abcde, track("x"), current = 1)))
        // Moving a later track up: it leaves its old place and lands after the current one.
        assertEquals("abecd", ids(QueueOps.playNext(abcde, abcde[4], current = 1)))
        // Moving an earlier track down to play next.
        assertEquals("bcade", ids(QueueOps.playNext(abcde, abcde[0], current = 2)))
    }

    @Test
    fun `shuffling leaves what has played and what is playing in place`() {
        val shuffled = QueueOps.shuffledAfter(abcde, current = 1, random = Random(7))

        assertEquals("ab", ids(shuffled.take(2)))
        assertEquals("cde".toSet(), ids(shuffled.drop(2)).toSet())
        assertEquals(5, shuffled.size)
    }

    @Test
    fun `shuffling from the last track changes nothing`() {
        assertEquals("abcde", ids(QueueOps.shuffledAfter(abcde, current = 4)))
    }

    @Test
    fun `radio leads with the seed and follows the planner`() {
        val library = listOf(
            track("seed", bpm = 120.0, key = "8A", energy = 3),
            track("near", bpm = 122.0, key = "8A", energy = 4),
            track("far", bpm = 90.0, key = "3B", energy = 9),
        )

        val radio = QueueOps.radio(library[0], library, length = 3)

        assertEquals("seed", radio.first().id)
        assertEquals(library.map { it.id }.toSet(), radio.map { it.id }.toSet())
        assertEquals("near", radio[1].id)
    }

    @Test
    fun `radio from an unanalysed seed still leads with it`() {
        val seed = track("seed", bpm = null, key = null)
        val library = listOf(seed, track("a"), track("b"))

        val radio = QueueOps.radio(seed, library, length = 2)

        assertEquals("seed", radio.first().id)
        assertEquals(2, radio.size)
    }

    @Test
    fun `a plan for a fixed order keeps every track and chains its transitions`() {
        val order = listOf(track("a"), track("b", bpm = 126.0), track("c", bpm = 132.0, key = "9A"))

        val plan = MixPlanner.planFor(order)

        assertEquals("abc", ids(plan.steps.map { it.track }))
        assertNull(plan.steps[0].transition)
        assertNotNull(plan.steps[1].transition)
        assertNotNull(plan.steps[2].transition)
        assertTrue(plan.skipped.isEmpty())
        // Each is stretched to what the one before is actually played at.
        assertEquals(120.0 / 126.0, plan.steps[1].alignment!!.tempoRatio, 1e-9)
        assertEquals(120.0 / 132.0, plan.steps[2].alignment!!.tempoRatio, 1e-9)
    }

    @Test
    fun `an unanalysed track in the order is kept and simply has no transition score`() {
        val plan = MixPlanner.planFor(listOf(track("a"), track("b", bpm = null, key = null), track("c")))

        assertEquals(3, plan.steps.size)
        assertNull(plan.steps[1].transition)
        assertNull(plan.steps[2].transition)
    }

    @Test
    fun `an empty order is an empty plan`() {
        val plan = MixPlanner.planFor(emptyList())

        assertTrue(plan.steps.isEmpty())
        assertEquals(0, plan.averageScore)
    }
}
