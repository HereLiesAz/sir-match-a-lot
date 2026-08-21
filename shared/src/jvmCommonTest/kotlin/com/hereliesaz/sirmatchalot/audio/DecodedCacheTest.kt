package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun `concurrent put and get from multiple threads does not corrupt the map or the byte count`() {
        // get() moves an entry to the end of the LinkedHashMap for LRU order —
        // a mutation, not a plain read — and put() reads-then-writes
        // heldBytes. Two threads doing either at once on an unsynchronized
        // LinkedHashMap is the textbook case for a corrupted bucket list or a
        // lost update; this used to be exactly that, reachable in production
        // from loadOntoDeck (Dispatchers.IO), harmonizeDeck (Dispatchers.Default)
        // and clearDecks/removeTrackFromDecks (the calling thread, often main).
        val cache = DecodedCache(maxBytes = Long.MAX_VALUE / 2)
        val ids = (0 until 40).map { "track-$it" }
        val threads = 8
        val iterationsPerThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        repeat(threads) { threadIndex ->
            pool.submit {
                try {
                    ready.countDown()
                    go.await()
                    val random = kotlin.random.Random(threadIndex)
                    repeat(iterationsPerThread) {
                        val id = ids[random.nextInt(ids.size)]
                        if (random.nextBoolean()) {
                            cache.put(id, buffer(64))
                        } else {
                            cache.get(id)
                        }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }
        }
        ready.await()
        go.countDown()
        assertTrue("threads did not finish in time", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        failure.get()?.let { throw AssertionError("concurrent access threw", it) }

        // heldBytes must exactly match what the map actually holds — the
        // symptom of the race was this counter drifting from reality, which
        // silently defeats the memory budget this class exists to enforce.
        var actual = 0L
        for (id in ids) {
            cache.get(id)?.let { actual += it.byteCount }
        }
        assertEquals("heldBytes drifted from the map's real contents", actual, cache.heldBytes)
    }

    @Test
    fun `the default budget is a third of the heap`() {
        val heap = Runtime.getRuntime().maxMemory()
        assertEquals(heap / 3, DecodedCache.defaultBudgetBytes())
    }

    // --- The budget the heap can actually afford ---

    @Test
    fun `a small heap gets the fraction and not a fixed floor`() {
        // The bug: `(heap / 3).coerceAtLeast(MINIMUM_BYTES)`, where the floor
        // was two five-minute tracks — 115 MB. On a 256 MB heap the third is
        // 85 MB, so the floor won, and the cache ceiling on exactly the devices
        // that cannot afford it was set to 45% of the entire heap. The decoder
        // still needs a whole-track accumulation buffer and a converted copy
        // underneath that. The mechanism meant to prevent OutOfMemoryError was
        // itself a cause of one.
        val smallHeap = 256L * 1024 * 1024
        assertEquals(smallHeap / 3, DecodedCache.budgetFor(smallHeap, divisor = 3))
    }

    @Test
    fun `the budget scales with the heap at every setting`() {
        val small = 256L * 1024 * 1024
        val large = 1024L * 1024 * 1024
        for (divisor in listOf(2, 3, 6)) {
            assertTrue(
                "divisor $divisor did not scale with the heap",
                DecodedCache.budgetFor(large, divisor) > DecodedCache.budgetFor(small, divisor),
            )
            assertTrue(
                "divisor $divisor claimed more than half the heap",
                DecodedCache.budgetFor(small, divisor) <= small / 2,
            )
        }
    }

    @Test
    fun `a tiny heap still yields a positive budget rather than zero`() {
        val budget = DecodedCache.budgetFor(heapBytes = 4L * 1024 * 1024, divisor = 6)
        assertTrue("budget must be positive", budget > 0)
    }

    @Test
    fun `a budget too small to hold anything still cannot break playback`() {
        // The consequence of having no floor, stated as a test: a cache smaller
        // than one track stops saving re-decodes, and that is all it does. What
        // a deck is reading is pinned, so it survives regardless.
        val cache = DecodedCache(maxBytes = 1)
        cache.put("playing", mono(1_000), pinned = setOf("playing"))
        assertNotNull(cache["playing"])
        assertTrue(cache.overBudget)
    }

    // --- Admission, before the allocator gets a say ---

    @Test
    fun `an entry that fits once the evictable is gone is admitted`() {
        val cache = DecodedCache(maxBytes = 6_000)
        cache.put("old", mono(1_000))
        cache.put("playing", mono(1_000))

        // 4000 bytes will not fit alongside both, but "old" can go.
        assertTrue(cache.canAdmit(4_000, pinned = setOf("playing")))
    }

    @Test
    fun `an entry larger than everything unpinnable can free is refused`() {
        val cache = DecodedCache(maxBytes = 6_000)
        cache.put("playing", mono(2_000))

        // 4000 held by the playing clip, which cannot be dropped; a 4000-byte
        // arrival would take the total to 8000 against a 6000 budget.
        assertFalse(cache.canAdmit(4_000, pinned = setOf("playing")))
    }

    @Test
    fun `an empty cache admits anything inside the budget and nothing beyond it`() {
        val cache = DecodedCache(maxBytes = 6_000)
        assertTrue(cache.canAdmit(6_000, pinned = emptySet()))
        assertFalse(cache.canAdmit(6_001, pinned = emptySet()))
    }

    // --- Moving the ceiling ---

    @Test
    fun `lowering the budget evicts down to it`() {
        val cache = DecodedCache(maxBytes = 8_000)
        cache.put("a", mono(1_000))
        cache.put("b", mono(1_000))
        cache.put("c", mono(1_000))
        assertEquals(6_000L, cache.heldBytes)

        cache.setBudget(2_000, pinned = emptySet())
        assertEquals(2_000L, cache.maxBytes)
        assertTrue("nothing was evicted: ${cache.heldBytes}", cache.heldBytes <= 2_000)
        assertNotNull("the most recent should be what survives", cache["c"])
    }

    @Test
    fun `lowering the budget still refuses to drop what is playing`() {
        val cache = DecodedCache(maxBytes = 8_000)
        cache.put("playing", mono(2_000))
        cache.setBudget(1_000, pinned = setOf("playing"))

        assertNotNull(cache["playing"])
        assertTrue("being over budget was not reported", cache.overBudget)
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
