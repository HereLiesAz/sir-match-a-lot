package com.hereliesaz.sirmatchalot.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [DesktopLibrary] round-tripped against a JSON file it writes itself. */
class DesktopLibraryTest {

    private fun tempStoreFile(): File =
        File.createTempFile("library", ".json").apply { delete(); deleteOnExit() }

    @Test
    fun `added files are remembered and persisted across instances`() {
        val storeFile = tempStoreFile()
        val fileA = File.createTempFile("track-a", ".wav").apply { deleteOnExit() }
        val fileB = File.createTempFile("track-b", ".wav").apply { deleteOnExit() }

        val library = DesktopLibrary(storeFile)
        library.add(listOf(fileA, fileB))

        assertEquals(2, library.tracks.value.size)
        assertTrue(library.tracks.value.any { it.path == fileA.path && it.displayName == fileA.name })

        val reopened = DesktopLibrary(storeFile)
        assertEquals(2, reopened.tracks.value.size)
    }

    @Test
    fun `adding the same path twice does not duplicate it`() {
        val storeFile = tempStoreFile()
        val file = File.createTempFile("track", ".wav").apply { deleteOnExit() }

        val library = DesktopLibrary(storeFile)
        library.add(listOf(file))
        library.add(listOf(file))

        assertEquals(1, library.tracks.value.size)
    }

    @Test
    fun `removing a track drops it and persists the removal`() {
        val storeFile = tempStoreFile()
        val file = File.createTempFile("track", ".wav").apply { deleteOnExit() }

        val library = DesktopLibrary(storeFile)
        library.add(listOf(file))
        library.remove(file.path)

        assertTrue(library.tracks.value.isEmpty())
        assertTrue(DesktopLibrary(storeFile).tracks.value.isEmpty())
    }

    @Test
    fun `a missing store file starts out empty rather than throwing`() {
        val storeFile = tempStoreFile()
        assertTrue(DesktopLibrary(storeFile).tracks.value.isEmpty())
    }
}
