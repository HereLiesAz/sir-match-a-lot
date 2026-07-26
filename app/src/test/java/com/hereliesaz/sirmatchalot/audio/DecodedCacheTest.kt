package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoded-audio cache.
 *
 * What this replaces was a `MutableMap` that nothing removed from, so every
 * track ever loaded stayed in memory as full PCM until the ViewModel died — the
 * dominant contributor to the reported `OutOfMemoryError`, since an Automatchic
 * Mix loads every track in the plan one after another.
 */
class DecodedCacheTest {

    /**
     * A 16-bit buffer of a known size, so budgets can be reasoned about exactly.
     * Its cost is `frames * channels * 2` bytes.
     */
    private fun buffer(frames: Int, channels: Int = 2): PcmBuffer =
        PcmBuffer(Array(channels) { ShortArray(frames) }, 44_100)

    /** One 16-bit channel, so an entry costs exactly twice its frame count. */
    private fun mono(frames: Int): PcmBuffer = buffer(frames, channels = 1)

    /** A hi-res buffer: same frames, four bytes a sample instead of two. */
    private fun hiRes(frames: Int, channels: Int = 1): PcmBuffer =
        PcmBuffer.float(Array(channels) { FloatArray(frames) }, 96_000)

    @Test
    fun `a stored buffer comes back`() {
        val cache = DecodedCache(maxBytes = 1_000_000)
        val pcm = buffer(1_000)
        cache.put("a", pcm)
        assertEquals(pcm, cache["a"])
        assertTrue("a" in cache)
    }

    @Test
    fun `a missing id returns null`() {
        assertNull(DecodedCache(maxBytes = 1_000)["nothing"])
    }

    @Test
    fun `held frames count every channel`() {
        // A count-based bound would say a mono 30-second clip and a stereo
        // ten-minute track are the same size. They are not.
        val cache = DecodedCache(maxBytes = 1_000_000)
        cache.put("stereo", buffer(1_000, channels = 2))
        assertEquals(4_000L, cache.heldBytes)
        cache.put("mono", buffer(1_000, channels = 1))
        assertEquals(6_000L, cache.heldBytes)
    }

    @Test
    fun `replacing an entry does not double count it`() {
        val cache = DecodedCache(maxBytes = 1_000_000)
        cache.put("a", buffer(1_000))
        cache.put("a", buffer(500))
        assertEquals(1, cache.size)
        assertEquals(2_000L, cache.heldBytes)
    }

    // --- Eviction ---

    @Test
    fun `exceeding the budget evicts the least recently used`() {
        // Mono buffers, so one entry is exactly 1000 frames and the budget
        // arithmetic is legible.
        val cache = DecodedCache(maxBytes = 6_000)
        cache.put("a", mono(1_000))
        cache.put("b", mono(1_000))
        cache.put("c", mono(1_000))
        assertEquals("three mono entries at 2000 bytes fits a 6000 budget", 3, cache.size)

        cache.put("d", mono(1_000))
        assertNotNull("the newest entry must survive", cache["d"])
        assertNull("the oldest should have gone first", cache["a"])
        assertTrue("still over budget: ${cache.heldBytes}", cache.heldBytes <= 6_000)
    }

    @Test
    fun `reading an entry makes it recently used`() {
        val cache = DecodedCache(maxBytes = 6_000)
        cache.put("a", mono(1_000))
        cache.put("b", mono(1_000))
        // Touching "a" makes "b" the oldest, so "b" is what the next eviction
        // should take.
        cache["a"]
        cache.put("c", mono(1_000))
        cache.put("d", mono(1_000))

        assertNotNull("a was touched and should have survived", cache["a"])
        assertNull("b was the oldest and should have gone", cache["b"])
    }

    @Test
    fun `a pinned entry is never evicted`() {
        // A deck clip is reading this buffer. Evicting it would not stop
        // playback, but the next load would decode a second copy — the cache
        // costing memory instead of saving it.
        val cache = DecodedCache(maxBytes = 4_000)
        cache.put("playing", buffer(1_000))
        repeat(6) { cache.put("filler$it", buffer(1_000), pinned = setOf("playing")) }

        assertNotNull("the pinned entry was evicted", cache["playing"])
    }

    @Test
    fun `the newly inserted entry is never evicted by its own insertion`() {
        val cache = DecodedCache(maxBytes = 100)
        cache.put("big", buffer(10_000))
        assertNotNull(cache["big"])
    }

    @Test
    fun `pinning more than the budget reports being over rather than evicting`() {
        // Correctness first: dropping audio the render thread is reading would
        // be worse than being over budget. But it must be visible.
        val cache = DecodedCache(maxBytes = 2_000)
        cache.put("one", buffer(2_000), pinned = setOf("one"))
        cache.put("two", buffer(2_000), pinned = setOf("one", "two"))

        assertEquals(2, cache.size)
        assertTrue("being over budget was not reported", cache.overBudget)
    }

    @Test
    fun `dropping back under budget clears the over-budget flag`() {
        val cache = DecodedCache(maxBytes = 2_000)
        cache.put("one", buffer(2_000), pinned = setOf("one"))
        assertTrue(cache.overBudget)

        cache.remove("one")
        cache.trim(emptySet())
        assertFalse(cache.overBudget)
    }

    // --- Explicit release ---

    @Test
    fun `removing an entry frees its bytes`() {
        val cache = DecodedCache(maxBytes = 1_000_000)
        cache.put("a", buffer(1_000))
        cache.remove("a")
        assertEquals(0L, cache.heldBytes)
        assertNull(cache["a"])
    }

    @Test
    fun `trimming after a track is retired releases it`() {
        // The Automatchic Mix case: each track is retired as the next takes over,
        // and the cache must let go rather than accumulating the whole set.
        val cache = DecodedCache(maxBytes = 4_000)
        cache.put("one", mono(1_000), pinned = setOf("one"))
        cache.put("two", mono(1_000), pinned = setOf("one", "two"))
        // Three tracks at once exceeds the budget, but all three are pinned
        // while the fade is in progress, so nothing may be dropped yet.
        cache.put("three", mono(1_000), pinned = setOf("one", "two", "three"))
        assertEquals(3, cache.size)
        assertTrue(cache.overBudget)

        // The fade completes: "one" is retired and may now go.
        cache.trim(pinned = setOf("two", "three"))
        assertNull("the retired track was not released", cache["one"])
        assertEquals(4_000L, cache.heldBytes)
        assertFalse(cache.overBudget)
    }

    @Test
    fun `clearing empties everything`() {
        val cache = DecodedCache(maxBytes = 1_000_000)
        cache.put("a", buffer(1_000))
        cache.put("b", buffer(1_000))
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0L, cache.heldBytes)
    }

    @Test
    fun `the default budget is a sane fraction of the heap`() {
        val bytes = DecodedCache.defaultBudgetBytes()
        // Enough for two five-minute 16-bit stereo tracks at 48 kHz, and not
        // more than the heap it has to live inside.
        assertTrue("budget $bytes is too small", bytes >= 2L * 5 * 60 * 48_000 * 2 * 2)
        assertTrue("budget $bytes exceeds the heap", bytes <= Runtime.getRuntime().maxMemory())
    }

    // --- Depth ---

    @Test
    fun `a hi-res buffer is counted at four bytes a sample, not two`() {
        // The bug this replaces would have been invisible until it was an
        // OutOfMemoryError: counting frames made the cache believe a float
        // track was half its real size, so the budget that exists to prevent
        // the crash would have permitted twice as much audio as it meant to.
        val cache = DecodedCache(maxBytes = 1_000_000)

        cache.put("cd", mono(1_000))
        assertEquals(2_000L, cache.heldBytes)

        cache.put("hires", hiRes(1_000))
        assertEquals("a float sample costs four bytes", 6_000L, cache.heldBytes)
    }

    @Test
    fun `a hi-res track evicts twice as much as a CD rip of the same length`() {
        val cache = DecodedCache(maxBytes = 6_000)
        cache.put("a", mono(1_000))
        cache.put("b", mono(1_000))
        cache.put("c", mono(1_000))
        assertEquals(3, cache.size)

        // 4000 bytes arriving into a 6000 budget already holding 6000 has to
        // displace two entries, where a 16-bit track of the same length would
        // displace one.
        cache.put("hires", hiRes(1_000))
        assertNotNull(cache["hires"])
        assertNull(cache["a"])
        assertNull(cache["b"])
        assertTrue(cache.heldBytes <= 6_000)
    }
}
