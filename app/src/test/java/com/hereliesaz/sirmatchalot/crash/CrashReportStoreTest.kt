package com.hereliesaz.sirmatchalot.crash

import com.hereliesaz.sirmatchalot.data.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrashReportStoreTest {

    /** A settings store with no Android under it. */
    private class MapStore(
        private val values: MutableMap<String, String> = mutableMapOf(),
    ) : KeyValueStore {
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private fun report(message: String = "boom") = CrashReport(
        timestampMillis = 1_700_000_000_000L,
        threadName = "main",
        exceptionType = "java.lang.IllegalStateException",
        message = message,
        stackTrace = "java.lang.IllegalStateException: $message\n\tat com.example.Foo.bar(Foo.kt:1)",
        appVersion = "1.2.3.4",
        device = "Google Pixel 9",
        androidRelease = "16",
    )

    @Test
    fun `nothing pending reads back null`() {
        val store = CrashReportStore(MapStore())
        assertNull(store.load())
    }

    @Test
    fun `a saved report round-trips exactly`() {
        val store = CrashReportStore(MapStore())
        val original = report()

        store.save(original)

        assertEquals(original, store.load())
    }

    @Test
    fun `clearing removes the pending report`() {
        val store = CrashReportStore(MapStore())
        store.save(report())

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun `a second crash overwrites the first rather than accumulating`() {
        val store = CrashReportStore(MapStore())
        store.save(report(message = "first"))
        store.save(report(message = "second"))

        assertEquals("second", store.load()?.message)
    }

    @Test
    fun `garbage left in the slot reads back as no report, not a crash`() {
        val values = mutableMapOf("pending_crash_report" to "{not json")
        val store = CrashReportStore(MapStore(values))

        assertNull(store.load())
    }

    @Test
    fun `a report saved through one instance is visible from a fresh instance over the same backing store`() {
        // The real store writes with `commit()`, not `apply()`, specifically so
        // the write is finished — not merely queued — before the caller (a
        // crash handler, one statement before the process dies) moves on. A
        // test that only reads back through the *same* CrashReportStore
        // object never exercises that: an in-memory field read right after a
        // queued-but-not-yet-flushed write would still pass. This one shares
        // the backing map (as two `CrashReportStore.forContext` calls would
        // share one real SharedPreferences file) and constructs a brand new
        // CrashReportStore to do the read, so the only thing carrying the
        // value across is the backing store's own persistence — exactly what
        // `apply()`'s async write would put at risk.
        val backing = MapStore()
        val writer = CrashReportStore(backing)
        val original = report(message = "written by one instance")

        writer.save(original)

        val reader = CrashReportStore(backing)
        assertEquals(original, reader.load())
    }
}
