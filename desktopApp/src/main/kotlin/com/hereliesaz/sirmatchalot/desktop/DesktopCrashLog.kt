package com.hereliesaz.sirmatchalot.desktop

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * A minimal desktop counterpart to the Android app's `CrashReportStore` /
 * `CrashReportPrompt`.
 *
 * Not the same pipeline — there is no prefilled GitHub issue, no dialog
 * offering to file it, and no structured [Throwable] type recorded for a
 * templated report. `:desktopApp` had none of that: an uncaught exception
 * killed the process (or printed to a console nobody but a developer at a
 * terminal is watching) with nothing left behind afterwards. This is the
 * floor under that — one plain-text file, overwritten on each crash the same
 * way [com.hereliesaz.sirmatchalot.crash.CrashReportStore] holds at most one
 * pending report, so there is at least something on disk a person hitting a
 * crash can find and attach to a bug report by hand.
 */
class DesktopCrashLog(
    private val file: File = defaultFile(),
) {
    /** Writes [throwable], from [thread], to [file], replacing whatever was there. */
    fun record(thread: Thread, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("Sir Match-a-Lot (desktop) crashed at ${Instant.now()}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace)
        }
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    /** The last recorded crash's text, or null if there is none. */
    fun read(): String? = file.takeIf { it.exists() }?.readText()

    /**
     * Installs this as the JVM's default uncaught-exception handler.
     *
     * Chains to whatever handler was previously installed (or, absent one,
     * the JVM's own default of printing to stderr) after recording — this
     * adds capture, it does not change how an uncaught exception is
     * otherwise handled or whether the process still dies from it.
     * [record] is wrapped in `runCatching` so a failure to write the log
     * itself (a read-only disk, say) cannot suppress or replace the crash it
     * was trying to describe.
     */
    fun installAsUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(thread, throwable) }
            if (previous != null) previous.uncaughtException(thread, throwable) else throwable.printStackTrace()
        }
    }

    companion object {
        fun defaultFile(): File = File(DesktopKeyValueStore.configDir(), "last-crash.log")
    }
}
