package com.hereliesaz.sirmatchalot.desktop

import org.junit.Assert.assertEquals
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

/** [DesktopAudioDecoder] round-tripped against a WAV file it writes itself. */
class DesktopAudioDecoderTest {

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
    fun `decodes a stereo WAV back to the same shape it was written in`() {
        val file = File.createTempFile("tone", ".wav").apply { deleteOnExit() }
        toneWav(file, sampleRate = 44_100, frames = 4_410, channels = 2)

        val decoded = DesktopAudioDecoder.decode(file)

        assertTrue("expected a decoded buffer", decoded != null)
        assertEquals(44_100, decoded!!.sampleRate)
        assertEquals(2, decoded.channelCount)
        assertEquals(4_410, decoded.frameCount)
        // Not literal silence — the tone actually made it through the decode.
        assertTrue(decoded.channel(0).any { it != 0.toShort() })
    }

    @Test
    fun `decodes a mono WAV without duplicating it into stereo`() {
        val file = File.createTempFile("tone-mono", ".wav").apply { deleteOnExit() }
        toneWav(file, channels = 1, frames = 1_000)

        val decoded = DesktopAudioDecoder.decode(file)

        assertEquals(1, decoded?.channelCount)
    }

    @Test
    fun `a file that is not audio decodes to null rather than throwing`() {
        val file = File.createTempFile("not-audio", ".wav").apply {
            deleteOnExit()
            writeText("this is not a wav file")
        }
        assertNull(DesktopAudioDecoder.decode(file))
    }
}
