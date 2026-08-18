package com.hereliesaz.sirmatchalot.desktop

import com.hereliesaz.sirmatchalot.data.KeyValueStore
import java.io.File
import java.util.Properties

/**
 * A [KeyValueStore] backed by a properties file in the user's home directory.
 *
 * Android's implementation of this interface is `SharedPreferences`; a
 * desktop process has no equivalent, but the identity/known-devices data it
 * holds is the same handful of small string values, so a properties file is
 * enough. Written on every [putString] rather than batched, since this is
 * called rarely (a pairing, a rename) and never from a render-critical path.
 */
class DesktopKeyValueStore(
    private val file: File = File(configDir(), "identity.properties"),
) : KeyValueStore {
    private val properties = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    @Synchronized
    override fun getString(key: String): String? = properties.getProperty(key)

    @Synchronized
    override fun putString(key: String, value: String) {
        properties.setProperty(key, value)
        file.parentFile?.mkdirs()
        file.outputStream().use { properties.store(it, "Sir Match-a-Lot device identity") }
    }

    companion object {
        /** `~/.sirmatchalot`, or the working directory if home isn't set. */
        fun configDir(): File {
            val home = System.getProperty("user.home")
            return if (home.isNullOrBlank()) File(".sirmatchalot") else File(home, ".sirmatchalot")
        }
    }
}
