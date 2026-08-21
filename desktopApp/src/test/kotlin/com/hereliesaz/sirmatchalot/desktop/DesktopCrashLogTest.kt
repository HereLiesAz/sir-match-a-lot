package com.hereliesaz.sirmatchalot.desktop

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [DesktopCrashLog]: the desktop app's minimal last-crash capture. */
class DesktopCrashLogTest {

    private fun tempLogFile(): File =
        File.createTempFile("last-crash", ".log").apply { delete(); deleteOnExit() }

    @Test
    fun `nothing recorded yet reads back null`() {
        val log = DesktopCrashLog(tempLogFile())
        assertNull(log.read())
    }

    @Test
    fun `a recorded crash names the thread and the exception`() {
        val log = DesktopCrashLog(tempLogFile())
        val error = IllegalStateException("the deck fell off")

        log.record(Thread.currentThread(), error)

        val text = log.read()
        assertTrue(text != null && text.contains(Thread.currentThread().name))
        assertTrue(text != null && text.contains("the deck fell off"))
        assertTrue(text != null && text.contains("IllegalStateException"))
    }

    @Test
    fun `a second crash replaces the first rather than accumulating`() {
        // Distinct, made-up marker strings rather than "first"/"second" —
        // the JVM's own stack trace names this very test method by its full
        // (space-including) name, and "second crash replaces the first"
        // contains "first" itself, which made an earlier version of this
        // assertion pass or fail on the wrong thing entirely.
        val log = DesktopCrashLog(tempLogFile())

        log.record(Thread.currentThread(), IllegalStateException("xyzzy-alpha"))
        log.record(Thread.currentThread(), IllegalStateException("xyzzy-beta"))

        val text = log.read()
        assertTrue(text != null && text.contains("xyzzy-beta"))
        assertTrue(text != null && !text.contains("xyzzy-alpha"))
    }

    @Test
    fun `installing chains to the previous handler rather than replacing it`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            var previousSaw: Throwable? = null
            Thread.setDefaultUncaughtExceptionHandler { _, throwable -> previousSaw = throwable }

            val log = DesktopCrashLog(tempLogFile())
            log.installAsUncaughtExceptionHandler()

            val error = IllegalStateException("boom")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)

            assertTrue("the log should have recorded it", log.read()?.contains("boom") == true)
            assertTrue("the previously-installed handler should still run", previousSaw === error)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
