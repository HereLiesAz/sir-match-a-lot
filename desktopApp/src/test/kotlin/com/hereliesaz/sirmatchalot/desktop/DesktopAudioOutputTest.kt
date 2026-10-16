package com.hereliesaz.sirmatchalot.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine

/**
 * [DesktopAudioOutput]'s lifecycle against a fake [SourceDataLine], so the
 * logic is exercised on any machine — CI has no audio hardware at all, which
 * is exactly why the line is injected rather than taken from the system.
 */
class DesktopAudioOutputTest {

    /** A line that accepts every write instantly and records what was done to it. */
    private class FakeLine {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val writes = AtomicInteger()

        val line: SourceDataLine = Proxy.newProxyInstance(
            SourceDataLine::class.java.classLoader,
            arrayOf(SourceDataLine::class.java),
        ) { _, method, args ->
            when (method.name) {
                "open" -> { opened.incrementAndGet(); null }
                "close" -> { closed.incrementAndGet(); null }
                "write" -> {
                    writes.incrementAndGet()
                    // Pace the loop a little, as a real line's blocking write would.
                    Thread.sleep(1)
                    args!![2] as Int
                }
                "isOpen" -> opened.get() > closed.get()
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                "toString" -> "FakeLine"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as SourceDataLine
    }

    @Test
    fun `a missing audio line reports through onError instead of throwing`() {
        val output = DesktopAudioOutput(openLine = { throw LineUnavailableException("no line") })
        val errorLatch = CountDownLatch(1)
        val rendered = AtomicBoolean(false)
        output.onError = { errorLatch.countDown() }

        output.start { _, _ -> rendered.set(true) }

        assertTrue("onError should fire", errorLatch.await(2, TimeUnit.SECONDS))
        assertFalse("render must never run without a line", rendered.get())

        // Safe to call even though start() never actually got a line.
        output.stop()
        output.release()
    }

    @Test
    fun `a render callback that throws reports through onError and closes the line`() {
        val fake = FakeLine()
        val output = DesktopAudioOutput(openLine = { fake.line })
        val errorLatch = CountDownLatch(1)
        output.onError = { errorLatch.countDown() }

        output.start { _, _ -> throw IllegalStateException("boom") }

        assertTrue("onError should fire", errorLatch.await(2, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 2_000
        while (fake.closed.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals("the failed line must be closed", 1, fake.closed.get())
        output.release()
    }

    @Test
    fun `stop closes the line and start afterwards opens a fresh one`() {
        val first = FakeLine()
        val second = FakeLine()
        val lines = ArrayDeque(listOf(first, second))
        val output = DesktopAudioOutput(openLine = { lines.removeFirst().line })
        val errors = AtomicInteger()
        output.onError = { errors.incrementAndGet() }

        output.start { buffer, _ -> buffer.fill(0f) }
        awaitWrites(first)
        output.stop()
        assertTrue("stop must close the line", first.closed.get() >= 1)

        val writesAfterStop = first.writes.get()
        output.start { buffer, _ -> buffer.fill(0f) }
        awaitWrites(second)
        assertEquals("the old line must not be written to again", writesAfterStop, first.writes.get())

        output.release()
        assertTrue(second.closed.get() >= 1)
        assertEquals("a clean stop is not an error", 0, errors.get())
    }

    @Test
    fun `stop is safe before start and when repeated`() {
        val output = DesktopAudioOutput(openLine = { FakeLine().line })
        output.stop()
        output.stop()
        output.release()
    }

    private fun awaitWrites(fake: FakeLine) {
        val deadline = System.currentTimeMillis() + 2_000
        while (fake.writes.get() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("the render loop should be writing", fake.writes.get() >= 3)
    }
}
