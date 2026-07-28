package com.hereliesaz.sirmatchalot.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportIssueTest {

    private fun report(
        message: String = "beatsPerBar must be positive",
        exceptionType: String = "java.lang.IllegalArgumentException",
        stackTrace: String = "java.lang.IllegalArgumentException: beatsPerBar must be positive\n\tat Foo.bar(Foo.kt:1)",
    ) = CrashReport(
        timestampMillis = 1_700_000_000_000L,
        threadName = "main",
        exceptionType = exceptionType,
        message = message,
        stackTrace = stackTrace,
        appVersion = "1.2.3.4",
        device = "Google Pixel 9",
        androidRelease = "16",
    )

    @Test
    fun `title names the exception and its message`() {
        val title = CrashReportIssue.title(report())

        assertTrue(title.contains("IllegalArgumentException"))
        assertTrue(title.contains("beatsPerBar must be positive"))
    }

    @Test
    fun `a blank message falls back to the exception type alone`() {
        val title = CrashReportIssue.title(report(message = ""))

        assertEquals("Crash: java.lang.IllegalArgumentException: java.lang.IllegalArgumentException", title)
    }

    @Test
    fun `an overlong title is cut down rather than left to fail elsewhere`() {
        val title = CrashReportIssue.title(report(message = "x".repeat(500)))

        assertTrue(title.length <= 120)
    }

    @Test
    fun `body names the device, version and thread, and fences the trace`() {
        val body = CrashReportIssue.body(report())

        assertTrue(body.contains("1.2.3.4"))
        assertTrue(body.contains("Google Pixel 9"))
        assertTrue(body.contains("Android 16"))
        assertTrue(body.contains("main"))
        assertTrue(body.contains("```"))
        assertTrue(body.contains("beatsPerBar must be positive"))
    }

    @Test
    fun `a stack trace past the cap is truncated and says so`() {
        val huge = (1..2000).joinToString("\n") { "\tat com.example.Frame$it(Frame.kt:$it)" }
        val body = CrashReportIssue.body(report(stackTrace = huge))

        assertTrue(body.length < huge.length + 500)
        assertTrue(body.contains("truncated"))
    }

    @Test
    fun `a short stack trace is not marked truncated`() {
        val body = CrashReportIssue.body(report())

        assertFalse(body.contains("truncated"))
    }

    @Test
    fun `url points at this repo's new-issue page with title and body as query params`() {
        val url = CrashReportIssue.url(report())

        assertTrue(url.startsWith("https://github.com/HereLiesAz/sir-match-a-lot/issues/new?"))
        assertTrue(url.contains("title="))
        assertTrue(url.contains("body="))
    }

    @Test
    fun `special characters in the message do not break the url`() {
        val url = CrashReportIssue.url(report(message = "a & b # c = d? \"quoted\""))

        assertFalse(url.contains(" "))
        assertFalse(url.contains("\n"))
    }

    @Test
    fun `newlines in the trace are escaped rather than splitting the query string`() {
        val url = CrashReportIssue.url(report(stackTrace = "line one\nline two\nline three"))

        assertFalse(url.contains("\n"))
        assertTrue(url.contains("line%20two"))
    }
}
