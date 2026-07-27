package com.hereliesaz.sirmatchalot.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry behind "what is the app doing".
 *
 * Everything slow in this app used to happen silently unless you were on the one
 * tab that reported it — and a track load, which decodes a whole file and then
 * stretches and shifts it, reported nowhere at all.
 *
 * The failure mode of the fix is worse than the problem it fixes: an indicator
 * that outlives its work teaches you to ignore the one place the app tells you
 * anything. So most of what is tested here is that work *stops* showing — on
 * every path out, including the ones nobody remembers to handle.
 */
class BackgroundWorkTest {

    @Test
    fun `nothing is showing to begin with`() {
        val work = BackgroundWork()
        assertTrue(work.items.value.isEmpty())
        assertFalse(work.isBusy)
    }

    @Test
    fun `beginning work shows it`() {
        val work = BackgroundWork()
        work.begin("load:a", "Loading Blue Monday", "decoding")
        assertTrue(work.isBusy)
        assertEquals("Loading Blue Monday — decoding", work.items.value.single().summary())
    }

    @Test
    fun `work with no detail summarises as just its label`() {
        val work = BackgroundWork()
        work.begin("x", "Harvesting loops")
        assertEquals("Harvesting loops", work.items.value.single().summary())
    }

    @Test
    fun `the same id twice is one item, not two`() {
        // Dropping the same track twice is one load as far as anyone watching
        // is concerned.
        val work = BackgroundWork()
        work.begin("load:a", "Loading a")
        work.begin("load:a", "Loading a")
        assertEquals(1, work.items.value.size)
    }

    @Test
    fun `several things at once all show, in the order they started`() {
        // Normal here: the mix director preloads a track while the library
        // analyses. The indicator names the first, so the order has to be the
        // order they began rather than the order they last ticked.
        val work = BackgroundWork()
        work.begin("first", "Analysing")
        work.begin("second", "Loading")
        assertEquals(listOf("first", "second"), work.items.value.map { it.id })
    }

    // --- Updates ---

    @Test
    fun `an update changes the detail without moving the item`() {
        val work = BackgroundWork()
        work.begin("first", "Analysing", "1 of 90")
        work.begin("second", "Loading")
        work.update("first", detail = "2 of 90")

        assertEquals(
            "a progress tick must not reorder the list",
            listOf("first", "second"),
            work.items.value.map { it.id },
        )
        assertEquals("2 of 90", work.items.value.first().detail)
    }

    @Test
    fun `an update keeps the parts it is not given`() {
        val work = BackgroundWork()
        work.begin("a", "Loading", detail = "decoding", progress = 0.25f)
        work.update("a", progress = 0.5f)

        val item = work.items.value.single()
        assertEquals("decoding", item.detail)
        assertEquals(0.5f, item.progress!!, 1e-6f)
    }

    @Test
    fun `updating something that already finished does not resurrect it`() {
        // A progress tick racing a completion would otherwise put back an item
        // nothing will ever clear — a spinner with no work behind it.
        val work = BackgroundWork()
        work.begin("a", "Loading")
        work.end("a")
        work.update("a", detail = "still going")
        assertTrue(work.items.value.isEmpty())
    }

    @Test
    fun `progress is clamped to a fraction`() {
        val work = BackgroundWork()
        work.begin("a", "Loading")
        val handle = work.Handle("a")

        handle.progress(2f)
        assertEquals(1f, work.items.value.single().progress!!, 1e-6f)
        handle.progress(-1f)
        assertEquals(0f, work.items.value.single().progress!!, 1e-6f)
    }

    @Test
    fun `progress out of a total of zero is left unknown rather than divided by it`() {
        val work = BackgroundWork()
        work.begin("a", "Analysing")
        work.Handle("a").progress(0, 0)
        assertNull(work.items.value.single().progress)
    }

    // --- Clearing, on every path out ---

    @Test
    fun `tracked work clears when the block returns`() = runTest {
        val work = BackgroundWork()
        val result = work.track("a", "Loading") { "done" }
        assertEquals("done", result)
        assertTrue(work.items.value.isEmpty())
    }

    @Test
    fun `tracked work clears when the block throws`() = runTest {
        val work = BackgroundWork()
        runCatching { work.track<Unit>("a", "Loading") { error("decode failed") } }
        assertTrue("a failure must not leave a spinner", work.items.value.isEmpty())
    }

    @Test
    fun `tracked work clears when the block runs out of memory`() = runTest {
        // The load path catches OutOfMemoryError and carries on. If that left
        // the indicator up, the app would claim to be loading a track it had
        // already given up on.
        val work = BackgroundWork()
        runCatching { work.track<Unit>("a", "Loading") { throw OutOfMemoryError() } }
        assertTrue(work.items.value.isEmpty())
    }

    @Test
    fun `tracked work clears when the block is cancelled`() = runTest {
        // A cancelled harvest, or a load killed with the ViewModel. Cancellation
        // is the most likely path to leave an orphan, because it is the one that
        // does not look like an error.
        val work = BackgroundWork()
        runCatching { work.track<Unit>("a", "Harvesting") { throw CancellationException() } }
        assertTrue(work.items.value.isEmpty())
    }

    @Test
    fun `a handle reports into the item it belongs to`() = runTest {
        val work = BackgroundWork()
        val seen = mutableListOf<String?>()
        work.track("a", "Loading Blue Monday") { progress ->
            progress.detail("decoding")
            seen.add(work.items.value.single().detail)
            progress.detail("stretching to 128.0 BPM")
            seen.add(work.items.value.single().detail)
        }
        assertEquals(listOf("decoding", "stretching to 128.0 BPM"), seen)
    }

    @Test
    fun `one tracked block finishing leaves the others alone`() = runTest {
        val work = BackgroundWork()
        work.begin("other", "Analysing")
        work.track("a", "Loading") { }
        assertEquals(listOf("other"), work.items.value.map { it.id })
    }

    @Test
    fun `clearing empties everything`() {
        val work = BackgroundWork()
        work.begin("a", "One")
        work.begin("b", "Two")
        work.clear()
        assertFalse(work.isBusy)
    }

    // --- Ids ---

    @Test
    fun `ids are namespaced per track so two loads are two items`() {
        val work = BackgroundWork()
        work.begin(BackgroundWork.loadId("track-1"), "Loading one")
        work.begin(BackgroundWork.loadId("track-2"), "Loading two")
        assertEquals(2, work.items.value.size)
    }
}
