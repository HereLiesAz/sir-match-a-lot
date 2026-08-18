package com.hereliesaz.sirmatchalot.sync

/**
 * A debug line from the sync protocol, platform-dispatched.
 *
 * `android.util.Log` isn't available outside Android — this is the one call
 * [SyncClient] needs a platform behind, so a desktop build gets its own
 * console-based [actual] rather than every log line becoming an expect/actual
 * pair of its own.
 */
internal expect fun debugLog(tag: String, message: String)
