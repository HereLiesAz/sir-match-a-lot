package com.hereliesaz.sirmatchalot.data

/**
 * The smallest thing a settings store has to be.
 *
 * An interface rather than `SharedPreferences` directly, so anything built on
 * it — `SettingsStore` in `:app`, `DeviceIdentity`/`KnownDevices` here — is
 * testable on the JVM and has a real desktop-side implementation to reach
 * for (a plain properties file, most likely) without touching Android at
 * all.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}
