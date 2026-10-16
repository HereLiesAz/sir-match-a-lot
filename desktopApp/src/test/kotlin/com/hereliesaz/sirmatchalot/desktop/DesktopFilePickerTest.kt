package com.hereliesaz.sirmatchalot.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The post-pick extension check, which backs up filters the OS dialog may ignore. */
class DesktopFilePickerTest {

    @Test
    fun `supported extensions pass regardless of case`() {
        listOf("a.wav", "b.WAVE", "c.aif", "d.AIFF", "e.au", "f.snd").forEach {
            assertTrue(it, DesktopFilePicker.isAudioFileName(it))
        }
    }

    @Test
    fun `unsupported files are rejected`() {
        listOf("a.mp3", "b.flac", "notes.txt", "wav", "a.wav.zip").forEach {
            assertFalse(it, DesktopFilePicker.isAudioFileName(it))
        }
    }
}
