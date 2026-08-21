package com.hereliesaz.sirmatchalot.desktop

import com.hereliesaz.sirmatchalot.analysis.AnalysisProgressBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

/** [DesktopLibrary] round-tripped against a JSON file it writes itself. */
class DesktopLibraryTest {

    private fun tempStoreFile(): File =
        File.createTempFile("library", ".json").apply { delete(); deleteOnExit() }

    private fun toneWav(
        file: File,
        sampleRate: Int = 44_100,
        frames: Int = sampleRate * 2,
        channels: Int = 2,
        frequencyHz: Double = 440.0,
    ) {
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val bytes = ByteArray(frames * channels * 2)
        for (frame in 0 until frames) {
            val sample = (sin(2 * PI * frequencyHz * frame / sampleRate) * Short.MAX_VALUE).toInt().toShort()
            for (channel in 0 until channels) {
                val i = (frame * channels + channel) * 2
                bytes[i] = (sample.toInt() and 0xFF).toByte()
                bytes[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
        }
        AudioSystem.write(
            AudioInputStream(bytes.inputStream(), format, frames.toLong()),
            AudioFileFormat.Type.WAVE,
            file,
        )
    }

    private fun awaitAnalysis(library: DesktopLibrary, path: String, timeoutMs: Long = 10_000): LibraryTrack {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val track = library.tracks.value.firstOrNull { it.path == path }
            if (track != null && track.isAnalysed) return track
            Thread.sleep(20)
        }
        error("timed out waiting for $path to be analysed")
    }

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

    @Test
    fun `a real audio file is measured in the background and the result persists`() {
        val storeFile = tempStoreFile()
        val file = File.createTempFile("tone", ".wav").apply { deleteOnExit() }
        toneWav(file)

        val library = DesktopLibrary(storeFile)
        library.add(listOf(file))

        val analysed = awaitAnalysis(library, file.path)
        // A pure sine tone has no beat and no tonal centre a key detector should
        // trust, but the energy curve is unconditional — that's the load-bearing
        // proof analysis genuinely ran rather than silently no-oping.
        assertTrue("expected an energy level once analysed", analysed.energyLevel != null)

        val reopened = awaitAnalysis(DesktopLibrary(storeFile), file.path, timeoutMs = 100)
        assertEquals(analysed.energyLevel, reopened.energyLevel)
    }

    @Test
    fun `a file that fails to decode is not treated as analysed`() {
        val storeFile = tempStoreFile()
        val file = File.createTempFile("not-audio", ".wav").apply {
            deleteOnExit()
            writeText("this is not a wav file")
        }

        val library = DesktopLibrary(storeFile)
        library.add(listOf(file))

        // Give the background thread time to try and fail; it should leave the
        // placeholder alone rather than fabricate an analysis.
        Thread.sleep(500)
        val track = library.tracks.value.first { it.path == file.path }
        assertTrue(!track.isAnalysed)
    }

    @Test
    fun `a decode failure does not leave the progress bus stuck running`() {
        // A batch that throws its way out of the analysis loop instead of
        // catching per-track failures used to skip AnalysisProgressBus.finish()
        // for whatever came after the bad file, leaving the UI showing
        // "Analysing…" forever even once the thread had actually stopped.
        val storeFile = tempStoreFile()
        val bad = File.createTempFile("not-audio", ".wav").apply {
            deleteOnExit()
            writeText("this is not a wav file")
        }
        val good = File.createTempFile("tone", ".wav").apply { deleteOnExit() }
        toneWav(good)

        val library = DesktopLibrary(storeFile)
        library.add(listOf(bad, good))

        awaitAnalysis(library, good.path)

        val deadline = System.currentTimeMillis() + 10_000
        while (AnalysisProgressBus.state.value.running && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertFalse(
            "the progress bus must not be left running once the batch is done",
            AnalysisProgressBus.state.value.running,
        )
    }

    @Test
    fun `concurrent add calls do not lose tracks to a race`() {
        // add() used to do a plain, unsynchronized read-modify-write of
        // _tracks.value. Two callers racing (a second folder import landing
        // while the first is still being persisted, say) could each read the
        // same starting list and then each overwrite the other's addition —
        // whichever write landed last "won" and the other's files vanished
        // from both the in-memory list and the file on disk.
        val storeFile = tempStoreFile()
        val batches = (0 until 8).map { batch ->
            (0 until 5).map { File.createTempFile("track-$batch-$it", ".wav").apply { deleteOnExit() } }
        }
        val library = DesktopLibrary(storeFile)

        val ready = CountDownLatch(batches.size)
        val go = CountDownLatch(1)
        val threads = batches.map { files ->
            Thread {
                ready.countDown()
                go.await()
                library.add(files)
            }
        }
        threads.forEach { it.start() }
        ready.await(5, TimeUnit.SECONDS)
        go.countDown()
        threads.forEach { it.join(10_000) }

        val expected = batches.flatten().size
        assertEquals(expected, library.tracks.value.size)
        assertEquals(expected, DesktopLibrary(storeFile).tracks.value.size)
    }
}
