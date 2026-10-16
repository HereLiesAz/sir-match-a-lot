package com.hereliesaz.sirmatchalot.sync

/**
 * Off unless asked for, with `-Dsirmatchalot.debug=true` or
 * `SIRMATCHALOT_DEBUG=1`. These lines carry host addresses and discovery
 * replies, and a desktop app's stdout ends up in terminals, launcher logs and
 * bug reports nobody meant to put a network map in.
 */
private val enabled: Boolean by lazy {
    System.getProperty("sirmatchalot.debug")?.toBoolean() == true ||
        System.getenv("SIRMATCHALOT_DEBUG").let { it == "1" || it.equals("true", ignoreCase = true) }
}

internal actual fun debugLog(tag: String, message: String) {
    if (enabled) println("[$tag] $message")
}
