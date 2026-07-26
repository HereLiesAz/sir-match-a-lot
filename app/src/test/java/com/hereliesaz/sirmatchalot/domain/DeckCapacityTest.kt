package com.hereliesaz.sirmatchalot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounding what a deck's circle holds.
 *
 * The platter is one revolution and a revolution is a fixed number of beats.
 * Unbounded, each clip occupies a smaller arc every time another is added until
 * nothing can be aimed at with a finger — and since every clip is a fully
 * decoded track, an unbounded circle is also an unbounded heap.
 */
class DeckCapacityTest {

    private fun entry(id: String, beats: Int, order: Int) = DeckCapacity.Entry(id, beats, order)

    @Test
    fun `nothing is evicted while there is room`() {
        val existing = listOf(entry("a", 500, 0), entry("b", 500, 1))
        assertEquals(emptyList<String>(), DeckCapacity.evictionsFor(existing, incoming = 500, maxBeats = 1_600))
    }

    @Test
    fun `the oldest goes first when the circle is full`() {
        // A deck is a queue in practice: the track on longest is the one already
        // mixed out of.
        val existing = listOf(entry("oldest", 600, 0), entry("newer", 600, 1))
        assertEquals(
            listOf("oldest"),
            DeckCapacity.evictionsFor(existing, incoming = 600, maxBeats = 1_600),
        )
    }

    @Test
    fun `several go if one is not enough`() {
        val existing = listOf(entry("a", 600, 0), entry("b", 600, 1), entry("c", 300, 2))
        val evicted = DeckCapacity.evictionsFor(existing, incoming = 1_200, maxBeats = 1_600)
        assertEquals(listOf("a", "b"), evicted)
    }

    @Test
    fun `the clip under the playhead is never evicted`() {
        // Silencing what is playing to make room is never what was wanted.
        val existing = listOf(entry("playing", 900, 0), entry("idle", 600, 1))
        val evicted = DeckCapacity.evictionsFor(
            existing,
            incoming = 600,
            maxBeats = 1_600,
            protectedIds = setOf("playing"),
        )
        assertEquals(listOf("idle"), evicted)
    }

    @Test
    fun `the bound is in beats, not in songs`() {
        // One long track and two short ones is not "three songs' worth" of
        // circle, and a deck holding them should not be told it is full.
        val existing = listOf(entry("short", 100, 0), entry("shorter", 80, 1))
        assertEquals(
            emptyList<String>(),
            DeckCapacity.evictionsFor(existing, incoming = 100, maxBeats = 1_600),
        )
    }

    @Test
    fun `an empty deck evicts nothing`() {
        assertEquals(emptyList<String>(), DeckCapacity.evictionsFor(emptyList(), incoming = 500))
    }

    @Test
    fun `a zero-length incoming clip changes nothing`() {
        val existing = listOf(entry("a", 1_500, 0))
        assertEquals(emptyList<String>(), DeckCapacity.evictionsFor(existing, incoming = 0, maxBeats = 1_600))
    }

    @Test
    fun `eviction is deterministic under equal lengths`() {
        val existing = listOf(entry("a", 800, 0), entry("b", 800, 1), entry("c", 800, 2))
        val first = DeckCapacity.evictionsFor(existing, incoming = 800, maxBeats = 1_600)
        val second = DeckCapacity.evictionsFor(existing.reversed(), incoming = 800, maxBeats = 1_600)
        assertEquals(first, second)
    }

    // --- What will not fit at all ---

    @Test
    fun `a clip longer than the whole circle is reported rather than accepted`() {
        assertTrue(DeckCapacity.cannotFit(emptyList(), incoming = 5_000, maxBeats = 1_600))
    }

    @Test
    fun `a clip that fits once the evictable is gone is not reported as impossible`() {
        val existing = listOf(entry("a", 1_500, 0))
        assertFalse(DeckCapacity.cannotFit(existing, incoming = 1_000, maxBeats = 1_600))
    }

    @Test
    fun `a protected clip counts against what can fit`() {
        // Nothing can displace what is playing, so its length is part of the
        // budget the incoming clip has to live within.
        val existing = listOf(entry("playing", 1_200, 0))
        assertTrue(
            DeckCapacity.cannotFit(
                existing,
                incoming = 800,
                maxBeats = 1_600,
                protectedIds = setOf("playing"),
            ),
        )
    }

    @Test
    fun `the default is sized for about two songs`() {
        // A four-minute track at 128 BPM is about 512 beats. Two, not three,
        // because every clip is a fully decoded track: three a deck is roughly
        // 350 MB across both decks, which does not fit a 256 MB heap.
        val fourMinutesAt128 = 512
        assertTrue(DeckCapacity.DEFAULT_MAX_BEATS >= fourMinutesAt128 * 2)
        assertTrue(DeckCapacity.DEFAULT_MAX_BEATS < fourMinutesAt128 * 3)
    }
}
