package com.hereliesaz.sirmatchalot.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

/**
 * [PlaybackSession] wires two decks, a crossfader, and a sampler onto a live
 * [com.hereliesaz.sirmatchalot.audio.AudioEngine]. None of this needs a real
 * audio line to be true — [DesktopAudioOutput] may fail to open one (this is
 * exactly the environment [DesktopAudioOutputTest] proves that for), but the
 * deck/mixer/sampler state these tests assert on lives entirely outside the
 * render thread.
 */
class PlaybackSessionTest {

    private fun toneWav(
        file: File,
        sampleRate: Int = 44_100,
        frames: Int = 4_410,
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

    @Test
    fun `deck A and deck B load and play independently`() {
        val session = PlaybackSession()
        try {
            val fileA = File.createTempFile("deck-a", ".wav").apply { deleteOnExit() }
            val fileB = File.createTempFile("deck-b", ".wav").apply { deleteOnExit() }
            toneWav(fileA, frequencyHz = 440.0)
            toneWav(fileB, frequencyHz = 880.0)

            session.deckA.load(fileA.path)
            session.deckB.load(fileB.path)

            assertEquals(fileA.name, session.deckA.loadedFileName.value)
            assertEquals(fileB.name, session.deckB.loadedFileName.value)

            session.deckA.play()
            assertTrue(session.deckA.isPlaying.value)
            assertFalse(session.deckB.isPlaying.value)

            session.deckB.play()
            session.deckA.stop()
            assertFalse(session.deckA.isPlaying.value)
            assertTrue(session.deckB.isPlaying.value)
        } finally {
            session.release()
        }
    }

    @Test
    fun `the crossfader setter clamps and updates the shared mixer`() {
        val session = PlaybackSession()
        try {
            session.setCrossfade(0.25f)
            assertEquals(0.25f, session.crossfade.value)
            assertEquals(0.25f, session.engine.mixer.crossfade)

            session.setCrossfade(5f)
            assertEquals(1f, session.crossfade.value)

            session.setCrossfade(-2f)
            assertEquals(0f, session.crossfade.value)
        } finally {
            session.release()
        }
    }

    @Test
    fun `a sampler pad loads and can be triggered and stopped`() {
        val session = PlaybackSession()
        try {
            val file = File.createTempFile("pad", ".wav").apply { deleteOnExit() }
            toneWav(file)

            val pad = session.samplerPads.first()
            assertNull(pad.label.value)

            pad.load(file.path)
            assertEquals(file.name, pad.label.value)

            pad.trigger()
            assertTrue(session.engine.sampler.pads[pad.index].isPlaying)

            pad.stop()
            assertFalse(session.engine.sampler.pads[pad.index].isPlaying)
        } finally {
            session.release()
        }
    }

    @Test
    fun `loading an unsupported file reports a load error without touching the deck`() {
        val session = PlaybackSession()
        try {
            val file = File.createTempFile("bad", ".wav").apply {
                deleteOnExit()
                writeText("not a wav file")
            }

            session.deckA.load(file.path)

            assertNull(session.deckA.loadedFileName.value)
            assertTrue(session.deckA.loadErrorMessage.value!!.contains("Could not decode"))
        } finally {
            session.release()
        }
    }
}
