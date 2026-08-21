package com.hereliesaz.sirmatchalot.desktop

import com.hereliesaz.sirmatchalot.data.KeyValueStore
import java.io.File
import java.nio.file.attribute.PosixFilePermissions
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
        val dir = file.parentFile
        dir?.mkdirs()
        file.outputStream().use { properties.store(it, "Sir Match-a-Lot device identity") }
        // This file holds the device's long-term private key (see
        // DeviceIdentity) — the one thing SECURITY.md's pairing story depends
        // on nobody else being able to read. Android's equivalent,
        // SharedPreferences, is sandboxed to the app's UID by the platform;
        // a plain file in the home directory is not, and was left at
        // whatever the process umask happened to be — typically
        // world-readable. Restricted to the owner on every write, and the
        // directory too, since a mode-700 file inside a mode-755 directory
        // still lets another local account discover the file exists and
        // when it changes, and setPosixFilePermissions does not touch a
        // directory's existing mode when it already exists.
        if (dir != null) restrictToOwner(dir, directory = true)
        restrictToOwner(file, directory = false)
    }

    companion object {
        /** `~/.sirmatchalot`, or the working directory if home isn't set. */
        fun configDir(): File {
            val home = System.getProperty("user.home")
            return if (home.isNullOrBlank()) File(".sirmatchalot") else File(home, ".sirmatchalot")
        }

        /**
         * Restricts [target] to the owner only. POSIX filesystems (Linux,
         * macOS) get an exact mode; anything else — NTFS chief among them —
         * has no POSIX view to set, so [java.nio.file.Files.setPosixFilePermissions]
         * throws [UnsupportedOperationException], and the legacy
         * [File] accessors are used instead. Those do not express "owner
         * only" as directly, but denying the world/group bits and then
         * granting the owner is the closest a JVM gets without reaching for
         * a Windows-specific ACL API for what is, on that platform, already a
         * per-user profile directory by default.
         */
        private fun restrictToOwner(target: File, directory: Boolean) {
            runCatching {
                val mode = if (directory) "rwx------" else "rw-------"
                java.nio.file.Files.setPosixFilePermissions(
                    target.toPath(),
                    PosixFilePermissions.fromString(mode),
                )
            }.recoverCatching {
                target.setReadable(false, false)
                target.setWritable(false, false)
                target.setExecutable(false, false)
                target.setReadable(true, true)
                target.setWritable(true, true)
                if (directory) target.setExecutable(true, true)
            }
        }
    }
}
