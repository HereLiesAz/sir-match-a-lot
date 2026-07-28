package com.hereliesaz.sirmatchalot.crash

import com.hereliesaz.sirmatchalot.data.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportingHandlerTest {

    private class MapStore(
        private val values: MutableMap<String, String> = mutableMapOf(),
    ) : KeyValueStore {
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        var seen: Pair<Thread, Throwable>? = null
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            seen = thread to throwable
        }
    }

    @Test
    fun `an uncaught exception is saved as a crash report`() {
        val store = CrashReportStore(MapStore())
        val handler = CrashReportingHandler(
            store = store,
            appVersion = "1.2.3.4",
            previous = null,
            device = "Google Pixel 9",
            androidRelease = "16",
            now = { 42L },
        )

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        val saved = store.load()
        requireNotNull(saved)
        assertEquals(42L, saved.timestampMillis)
        assertEquals(Thread.currentThread().name, saved.threadName)
        assertEquals("java.lang.IllegalStateException", saved.exceptionType)
        assertEquals("boom", saved.message)
        assertTrue(saved.stackTrace.contains("IllegalStateException"))
        assertEquals("1.2.3.4", saved.appVersion)
        assertEquals("Google Pixel 9", saved.device)
        assertEquals("16", saved.androidRelease)
    }

    @Test
    fun `an exception with no message still saves a report`() {
        val store = CrashReportStore(MapStore())
        val handler = CrashReportingHandler(store, "1.0", previous = null)

        handler.uncaughtException(Thread.currentThread(), NullPointerException())

        assertEquals("", store.load()?.message)
    }

    @Test
    fun `the previously installed handler still runs afterwards`() {
        val previous = RecordingHandler()
        val handler = CrashReportingHandler(CrashReportStore(MapStore()), "1.0", previous)
        val thread = Thread.currentThread()
        val thrown = IllegalStateException("boom")

        handler.uncaughtException(thread, thrown)

        assertEquals(thread to thrown, previous.seen)
    }

    @Test
    fun `no previously installed handler is tolerated, not a second crash`() {
        val handler = CrashReportingHandler(CrashReportStore(MapStore()), "1.0", previous = null)

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
    }

    @Test
    fun `a store that fails to write does not stop the previous handler from running`() {
        val throwingStore = CrashReportStore(
            object : KeyValueStore {
                override fun getString(key: String): String? = null
                override fun putString(key: String, value: String) {
                    throw java.io.IOException("disk full")
                }
            },
        )
        val previous = RecordingHandler()
        val handler = CrashReportingHandler(throwingStore, "1.0", previous)

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertTrue(previous.seen != null)
    }
}
