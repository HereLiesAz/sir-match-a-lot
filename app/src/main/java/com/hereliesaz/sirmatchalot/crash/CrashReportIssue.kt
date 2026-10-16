package com.hereliesaz.sirmatchalot.crash

import java.net.URLEncoder

/**
 * Turns a [CrashReport] into a prefilled "new issue" URL on the app's own repo.
 *
 * This is the whole reporting mechanism: no token travels with the app, so
 * nothing decompiled out of the APK can write to the tracker on its own. It
 * opens a browser tab with the title and body already filled in; sending the
 * report still costs the person who hit the crash one tap on GitHub's own
 * "Submit new issue" button, not a background upload.
 */
object CrashReportIssue {
    private const val REPO_URL = "https://github.com/HereLiesAz/sir-match-a-lot"

    /** GitHub silently drops issues whose URL is too long; the trace is the one field that varies. */
    private const val MAX_STACK_TRACE_CHARS = 4000
    private const val MAX_TITLE_CHARS = 120

    fun title(report: CrashReport): String {
        val summary = report.message.ifBlank { report.exceptionType }
        return "Crash: ${report.exceptionType}: $summary".take(MAX_TITLE_CHARS)
    }

    fun body(report: CrashReport): String = body(report, MAX_STACK_TRACE_CHARS)

    private fun body(report: CrashReport, traceChars: Int): String {
        val trace = report.stackTrace.take(traceChars)
        val truncated = trace.length < report.stackTrace.length
        return buildString {
            appendLine("**App version:** ${report.appVersion}")
            appendLine("**Device:** ${report.device} (Android ${report.androidRelease})")
            appendLine("**Thread:** ${report.threadName}")
            appendLine()
            appendLine("```")
            append(trace)
            if (truncated) append("\n… (truncated)")
            appendLine()
            append("```")
        }
    }

    /**
     * The longest URL produced. The character cap on the trace is not enough on
     * its own: encoding multiplies length, by three for every reserved ASCII
     * character and by up to nine or twelve for non-ASCII ones, so a trace of
     * 4000 characters could still become a URL GitHub refuses.
     */
    const val MAX_URL_CHARS = 8000

    /**
     * The full `issues/new` URL, with [title] and [body] as query parameters,
     * never longer than [MAX_URL_CHARS]: the trace is cut further, and marked
     * truncated, until the *encoded* URL fits.
     */
    fun url(report: CrashReport): String {
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        val prefix = "$REPO_URL/issues/new?title=${encode(title(report))}&body="
        var traceChars = minOf(MAX_STACK_TRACE_CHARS, report.stackTrace.length)
        while (true) {
            val url = prefix + encode(body(report, traceChars))
            if (url.length <= MAX_URL_CHARS || traceChars == 0) return url.take(MAX_URL_CHARS)
            // Shrink in proportion to the overshoot, and always by at least one.
            val over = url.length - MAX_URL_CHARS
            traceChars = (traceChars - maxOf(1, over / 3)).coerceAtLeast(0)
        }
    }
}
