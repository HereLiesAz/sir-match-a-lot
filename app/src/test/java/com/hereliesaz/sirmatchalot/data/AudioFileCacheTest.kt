package com.hereliesaz.sirmatchalot.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Local copies of imported audio.
 *
 * A `content://` source is a reference, not a file: a cloud provider refetches it
 * over the network on every read, a document grant can lapse, and a file can move
 * or be unmounted — each of which leaves a library row pointing at audio the app
 * can no longer get. A copy in the app's own storage answers all three.
 *
 * The IO itself needs a `ContentResolver`, so what is tested here is everything
 * around it: what counts as a copy, what eviction takes, and what it refuses to
 * take.
 */
class AudioFileCacheTest {

    private val directory: File = Files.createTempDirectory("audio-cache").toFile()
    private val cache = AudioFileCache(directory)

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** A copy of [id] holding [bytes] bytes, last used [ageMillis] ago. */
    private fun copy(id: String, bytes: Int, ageMillis: Long = 0L): File {
        val file = File(directory, "$id.mp3")
        file.writeBytes(ByteArray(bytes))
        file.setLastModified(System.currentTimeMillis() - ageMillis)
        return file
    }

    @Test
    fun `an empty cache holds nothing`() {
        assertEquals(0L, cache.heldBytes())
        assertEquals(0, cache.count())
        assertNull(cache.localFile("anything"))
    }

    @Test
    fun `a copy is found by track id, whatever its extension`() {
        copy("track-1", 100)
        assertNotNull(cache.localFile("track-1"))
        assertEquals(100L, cache.heldBytes())
    }

    @Test
    fun `an empty file is not a copy`() {
        // A zero-length file is what a failed or interrupted write leaves.
        // Treating it as a copy would mean decoding nothing and reporting the
        // source as broken.
        File(directory, "track-1.mp3").writeBytes(ByteArray(0))
        assertNull(cache.localFile("track-1"))
    }

    @Test
    fun `a partial write is not mistaken for a copy`() {
        // Copies are written to `.partial` and renamed on completion. A
        // truncated file that looked complete would decode to a track that ends
        // early — which reads as a corrupt source rather than as a failed copy.
        File(directory, "track-1.partial").writeBytes(ByteArray(500))
        assertNull(
            "a partial write must not satisfy a lookup for track-1",
            cache.localFile("track-1"),
        )
    }

    @Test
    fun `removing a copy frees it`() {
        copy("track-1", 100)
        cache.remove("track-1")
        assertNull(cache.localFile("track-1"))
        assertEquals(0L, cache.heldBytes())
    }

    // --- Eviction ---

    @Test
    fun `nothing is evicted while inside the budget`() {
        copy("a", 100)
        copy("b", 100)
        assertEquals(0, cache.trim(budgetBytes = 1_000))
        assertEquals(2, cache.count())
    }

    @Test
    fun `the least recently used copy goes first`() {
        copy("old", 100, ageMillis = 60_000)
        copy("recent", 100, ageMillis = 1_000)

        cache.trim(budgetBytes = 100)

        assertNull("the oldest should have gone", cache.localFile("old"))
        assertNotNull("the newest should have stayed", cache.localFile("recent"))
    }

    @Test
    fun `eviction continues until it is within budget`() {
        copy("a", 100, ageMillis = 5_000)
        copy("b", 100, ageMillis = 4_000)
        copy("c", 100, ageMillis = 3_000)

        val removed = cache.trim(budgetBytes = 100)

        assertEquals(2, removed)
        assertTrue(cache.heldBytes() <= 100)
    }

    @Test
    fun `a track on a deck is never evicted`() {
        // The same rule the decoded-audio cache follows: what is in use stays,
        // whatever the budget says. Dropping the file under a playing deck would
        // mean the next load has to fetch it over the network again — the exact
        // thing copies exist to prevent.
        copy("playing", 500, ageMillis = 90_000)
        copy("idle", 100, ageMillis = 1_000)

        cache.trim(budgetBytes = 100, keep = setOf("playing"))

        assertNotNull("the playing track was evicted", cache.localFile("playing"))
    }

    @Test
    fun `a budget of zero clears everything that is not in use`() {
        copy("a", 100)
        copy("b", 100)
        cache.trim(budgetBytes = 0)
        assertEquals(0, cache.count())
    }

    @Test
    fun `touching a copy protects it from the next eviction`() {
        copy("stale", 100, ageMillis = 60_000)
        copy("other", 100, ageMillis = 30_000)

        cache.touch("stale")
        cache.trim(budgetBytes = 100)

        assertNotNull("touching should have saved it", cache.localFile("stale"))
        assertNull(cache.localFile("other"))
    }

    @Test
    fun `clearing removes every copy`() {
        copy("a", 100)
        copy("b", 100)
        assertEquals(2, cache.clear())
        assertEquals(0L, cache.heldBytes())
    }

    @Test
    fun `a missing directory is empty rather than an error`() {
        val absent = AudioFileCache(File(directory, "not-created-yet"))
        assertEquals(0L, absent.heldBytes())
        assertEquals(0, absent.count())
        assertEquals(0, absent.trim(budgetBytes = 0))
        assertEquals(0, absent.clear())
        assertNull(absent.localFile("x"))
    }

    // --- The budget the setting expresses ---

    @Test
    fun `off means a ceiling of zero, so no copy survives`() {
        assertEquals(0L, LocalCopyBudget.OFF.ceiling())
        assertFalse(LocalCopyBudget.OFF.isEnabled)
    }

    @Test
    fun `unlimited has no ceiling to trim against`() {
        assertEquals(Long.MAX_VALUE, LocalCopyBudget.UNLIMITED.ceiling())
        assertTrue(LocalCopyBudget.UNLIMITED.isEnabled)
    }

    @Test
    fun `the offered budgets ascend`() {
        val ceilings = LocalCopyBudget.entries.map { it.ceiling() }
        assertEquals(ceilings.sorted(), ceilings)
    }
}
