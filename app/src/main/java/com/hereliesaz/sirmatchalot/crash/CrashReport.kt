package com.hereliesaz.sirmatchalot.crash

import kotlinx.serialization.Serializable

/**
 * Everything worth keeping about a crash the app did not expect to survive.
 *
 * Written from [CrashReportingHandler] on the thread that just died, so this
 * has to be built from information already in hand — the throwable and the
 * thread — plus a few facts read once at install time. Nothing here is
 * collected on a timer or in the background; there is exactly one of these on
 * disk at a time, because a crash loop should fill a dialog, not a database.
 */
@Serializable
data class CrashReport(
    val timestampMillis: Long,
    val threadName: String,
    val exceptionType: String,
    val message: String,
    val stackTrace: String,
    val appVersion: String,
    val device: String,
    val androidRelease: String,
)
