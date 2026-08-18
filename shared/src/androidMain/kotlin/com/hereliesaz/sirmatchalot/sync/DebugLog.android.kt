package com.hereliesaz.sirmatchalot.sync

internal actual fun debugLog(tag: String, message: String) {
    android.util.Log.d(tag, message)
}
