package com.hereliesaz.sirmatchalot.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * This file holds the device's long-term identity private key (see
 * DeviceIdentity) — the one thing the pairing story in docs/SECURITY.md
 * depends on nobody else being able to read. Unlike Android's
 * SharedPreferences, which the platform sandboxes to the app's own UID, a
 * plain file in the home directory has no such protection unless something
 * here restricts it.
 */
class DesktopKeyValueStoreTest {

    @Test
    fun `the identity file is restricted to the owner`() {
        val dir = Files.createTempDirectory("sirmatchalot-identity-test").toFile()
        val file = dir.resolve("nested").resolve("identity.properties")
        val store = DesktopKeyValueStore(file)

        store.putString("identity_private", "super-secret-key-material")

        val permissions = Files.getPosixFilePermissions(file.toPath())
        assertEquals(
            "file must be owner read/write only, was ${PosixFilePermissions.toString(permissions)}",
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            permissions,
        )

        val dirPermissions = Files.getPosixFilePermissions(file.parentFile.toPath())
        assertTrue(
            "directory must not be group/other accessible, was ${PosixFilePermissions.toString(dirPermissions)}",
            dirPermissions.none {
                it == PosixFilePermission.GROUP_READ || it == PosixFilePermission.GROUP_WRITE ||
                    it == PosixFilePermission.GROUP_EXECUTE || it == PosixFilePermission.OTHERS_READ ||
                    it == PosixFilePermission.OTHERS_WRITE || it == PosixFilePermission.OTHERS_EXECUTE
            },
        )
    }

    @Test
    fun `a value written survives being read back by a fresh store`() {
        val dir = Files.createTempDirectory("sirmatchalot-identity-test").toFile()
        val file = dir.resolve("identity.properties")

        DesktopKeyValueStore(file).putString("k", "v")

        assertEquals("v", DesktopKeyValueStore(file).getString("k"))
    }

    @Test
    fun `a corrupt file is moved aside and the store starts empty`() {
        val dir = Files.createTempDirectory("sirmatchalot-identity-test").toFile()
        val file = dir.resolve("identity.properties")
        // A malformed \\u escape makes Properties.load throw.
        file.writeText("key=\\uZZZZ\n")

        val store = DesktopKeyValueStore(file)

        assertEquals(null, store.getString("key"))
        assertTrue(dir.list()!!.any { it.startsWith("identity.properties.corrupt-") })
        store.putString("k", "v")
        assertEquals("v", DesktopKeyValueStore(file).getString("k"))
    }

    @Test
    fun `writes leave no temp files behind`() {
        val dir = Files.createTempDirectory("sirmatchalot-identity-test").toFile()
        val file = dir.resolve("identity.properties")
        val store = DesktopKeyValueStore(file)
        store.putString("a", "1")
        store.putString("b", "2")
        assertEquals(listOf("identity.properties"), dir.list()!!.toList())
    }

    @Test
    fun `the config dir follows each platform's convention`() {
        assertEquals(
            java.io.File("C:/Users/dj/AppData/Roaming", "SirMatchALot"),
            DesktopKeyValueStore.configDirFor("Windows 11", mapOf("APPDATA" to "C:/Users/dj/AppData/Roaming"), "C:/Users/dj"),
        )
        assertEquals(
            java.io.File("/Users/dj/Library/Application Support/SirMatchALot"),
            DesktopKeyValueStore.configDirFor("Mac OS X", emptyMap(), "/Users/dj"),
        )
        assertEquals(
            java.io.File("/home/dj/.config/sirmatchalot"),
            DesktopKeyValueStore.configDirFor("Linux", emptyMap(), "/home/dj"),
        )
        assertEquals(
            java.io.File("/xdg/sirmatchalot"),
            DesktopKeyValueStore.configDirFor("Linux", mapOf("XDG_CONFIG_HOME" to "/xdg"), "/home/dj"),
        )
    }

    @Test
    fun `the legacy directory is migrated once and never over an existing one`() {
        val root = Files.createTempDirectory("sirmatchalot-migrate-test").toFile()
        val legacy = root.resolve(".sirmatchalot").apply { mkdirs() }
        legacy.resolve("identity.properties").writeText("k=v\n")
        val target = root.resolve("config/sirmatchalot")

        assertTrue(DesktopKeyValueStore.migrateLegacyDir(legacy, target))
        assertEquals("v", DesktopKeyValueStore(target.resolve("identity.properties")).getString("k"))
        assertTrue(!legacy.exists())

        legacy.mkdirs()
        assertTrue(!DesktopKeyValueStore.migrateLegacyDir(legacy, target))
    }
}
