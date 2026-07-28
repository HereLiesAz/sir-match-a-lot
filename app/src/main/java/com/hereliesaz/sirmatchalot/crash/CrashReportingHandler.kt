package com.hereliesaz.sirmatchalot.crash

import android.os.Build

/**
 * Installed as the process's default uncaught-exception handler so a crash is
 * still on disk the next time the app opens, in a form a person can hand to
 * GitHub without touching adb.
 *
 * Always delegates to whatever handler was already installed — on a real
 * device that is the platform's, which shows the "app has stopped" dialog and
 * ends the process. This only ever adds a write before that; it never
 * suppresses or changes what happens to the crashing process. If nothing was
 * installed before it — true only off-device, where a test builds one
 * directly — there is nothing to delegate to, and the thread simply finishes.
 */
class CrashReportingHandler(
    private val store: CrashReportStore,
    private val appVersion: String,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val device: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    private val androidRelease: String = Build.VERSION.RELEASE ?: "",
    private val now: () -> Long = System::currentTimeMillis,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            store.save(
                CrashReport(
                    timestampMillis = now(),
                    threadName = thread.name,
                    exceptionType = throwable::class.qualifiedName ?: throwable.javaClass.name,
                    message = throwable.message ?: "",
                    stackTrace = throwable.stackTraceToString(),
                    appVersion = appVersion,
                    device = device,
                    androidRelease = androidRelease,
                ),
            )
        }
        previous?.uncaughtException(thread, throwable)
    }

    companion object {
        /** Wraps whatever handler is currently installed, then replaces it with this one. */
        fun install(store: CrashReportStore, appVersion: String) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashReportingHandler(store, appVersion, previous))
        }
    }
}
