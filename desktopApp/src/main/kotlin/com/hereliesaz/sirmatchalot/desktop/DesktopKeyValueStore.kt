package com.hereliesaz.sirmatchalot.desktop

import com.hereliesaz.sirmatchalot.data.KeyValueStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import java.util.Properties

/**
 * A [KeyValueStore] backed by a properties file in the per-user config
 * directory ([configDir]).
 *
 * Android's implementation of this interface is `SharedPreferences`; a
 * desktop process has no equivalent, but the identity/known-devices data it
 * holds is the same handful of small string values, so a properties file is
 * enough. Written on every [putString] rather than batched, since this is
 * called rarely (a pairing, a rename) and never from a render-critical path.
 *
 * Each write goes to an owner-only temp file that is then renamed over the
 * real one, so the private key is never readable by another account even
 * for a moment, and a crash mid-write cannot truncate the file. A file that
 * cannot be parsed is moved aside (`identity.properties.corrupt-<time>`)
 * and the store starts empty rather than failing to launch.
 */
class DesktopKeyValueStore(
    private val file: File = File(configDir(), "identity.properties"),
) : KeyValueStore {
    private val properties = loadOrQuarantine(file)

    @Synchronized
    override fun getString(key: String): String? = properties.getProperty(key)

    @Synchronized
    override fun putString(key: String, value: String) {
        properties.setProperty(key, value)
        val bytes = ByteArrayOutputStream().also {
            properties.store(it, "Sir Match-a-Lot device identity")
        }.toByteArray()
        val dir = file.absoluteFile.parentFile
        dir?.mkdirs()
        // This file holds the device's long-term private key (see
        // DeviceIdentity) — the one thing SECURITY.md's pairing story depends
        // on nobody else being able to read. Android's equivalent,
        // SharedPreferences, is sandboxed to the app's UID by the platform;
        // a plain file in the home directory is not. The directory is
        // restricted too, since a mode-700 file inside a mode-755 directory
        // still lets another local account discover the file exists and when
        // it changes. The temp file is restricted *before* anything is
        // written into it, so there is no window where the key sits in a
        // file created under the process umask.
        if (dir != null) restrictToOwner(dir, directory = true)
        AtomicFiles.write(file, bytes) { temp -> restrictToOwner(temp, directory = false) }
        restrictToOwner(file, directory = false)
    }

    companion object {
        private const val APP_DIR_NAME = "SirMatchALot"
        private const val LEGACY_DIR_NAME = ".sirmatchalot"

        private val resolvedConfigDir: File by lazy {
            val home = System.getProperty("user.home")
            val target = configDirFor(System.getProperty("os.name").orEmpty(), System.getenv(), home)
            if (!home.isNullOrBlank()) migrateLegacyDir(File(home, LEGACY_DIR_NAME), target)
            target
        }

        /**
         * The per-user config directory, following each OS's convention:
         * `%APPDATA%\SirMatchALot` on Windows, `~/Library/Application
         * Support/SirMatchALot` on macOS, and `$XDG_CONFIG_HOME/sirmatchalot`
         * (default `~/.config/sirmatchalot`) elsewhere. Data from the old
         * `~/.sirmatchalot` location is moved here on first use.
         */
        fun configDir(): File = resolvedConfigDir

        /** [configDir]'s choice, as a pure function of its inputs, for tests. */
        internal fun configDirFor(osName: String, env: Map<String, String>, home: String?): File {
            val base = home?.takeIf { it.isNotBlank() }?.let(::File) ?: File(".")
            val os = osName.lowercase()
            return when {
                os.startsWith("windows") -> {
                    val appData = env["APPDATA"]?.takeIf { it.isNotBlank() }?.let(::File)
                        ?: File(base, "AppData/Roaming")
                    File(appData, APP_DIR_NAME)
                }
                os.startsWith("mac") || os.contains("darwin") ->
                    File(base, "Library/Application Support/$APP_DIR_NAME")
                else -> {
                    val xdg = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() && File(it).isAbsolute }?.let(::File)
                        ?: File(base, ".config")
                    File(xdg, APP_DIR_NAME.lowercase())
                }
            }
        }

        /**
         * Moves [legacy] to [target] when only the former exists. Best
         * effort: a failed move leaves both places as they were, and the
         * app starts with a fresh identity in [target] rather than not at
         * all. Returns true when something was moved.
         */
        internal fun migrateLegacyDir(legacy: File, target: File): Boolean {
            if (!legacy.isDirectory || target.exists()) return false
            return runCatching {
                target.absoluteFile.parentFile?.mkdirs()
                try {
                    Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (e: IOException) {
                    // Different filesystem (or no atomic rename): copy, then
                    // remove the original only once every file has landed.
                    legacy.copyRecursively(target, overwrite = false)
                    legacy.deleteRecursively()
                }
                restrictToOwner(target, directory = true)
                true
            }.getOrDefault(false)
        }

        /**
         * Reads [file], or — when it exists but cannot be parsed — moves it
         * aside and starts empty, so one bad write (or a hand edit) costs a
         * re-pair rather than an app that will not launch.
         */
        internal fun loadOrQuarantine(file: File): Properties {
            val properties = Properties()
            if (!file.exists()) return properties
            try {
                file.inputStream().use { properties.load(it) }
            } catch (e: Exception) {
                if (e !is IOException && e !is IllegalArgumentException) throw e
                val backup = File(file.path + ".corrupt-" + System.currentTimeMillis())
                runCatching { Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                System.err.println("Sir Match-a-Lot: $file could not be read (${e.message}); moved to $backup")
                return Properties()
            }
            return properties
        }

        /**
         * Restricts [target] to the owner only. POSIX filesystems (Linux,
         * macOS) get an exact mode. NTFS has no POSIX view, so there the
         * file's ACL is replaced with a single entry granting its owner full
         * control. If neither view is available, the legacy [File]
         * accessors deny world/group access and grant the owner, which is
         * the closest a JVM gets otherwise.
         */
        internal fun restrictToOwner(target: File, directory: Boolean) {
            val path = target.toPath()
            runCatching {
                val mode = if (directory) "rwx------" else "rw-------"
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
            }.recoverCatching {
                val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                    ?: throw UnsupportedOperationException("no ACL view")
                val entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(acl.owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                    .apply {
                        if (directory) {
                            setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                        }
                    }
                    .build()
                acl.acl = listOf(entry)
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
