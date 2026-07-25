package com.hereliesaz.sirmatchalot.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which documents in a folder count as music.
 *
 * Split out of the folder walk so the judgement is testable without a
 * ContentResolver — the walk is cursor plumbing, this is the part that can be
 * wrong in ways a user notices (a folder that imports nothing, or one that
 * imports a thousand resource forks).
 */
class AudioFileFilterTest {

    @Test
    fun `an audio mime type is enough`() {
        assertTrue(AudioFileFilter.isAudio("audio/mpeg", "track.mp3"))
        assertTrue(AudioFileFilter.isAudio("audio/flac", "track.flac"))
        assertTrue(AudioFileFilter.isAudio("AUDIO/MPEG", "track.mp3"))
    }

    @Test
    fun `a generic binary type falls back to the extension`() {
        // Storage providers hand back application/octet-stream for perfectly
        // ordinary audio constantly. Trusting the MIME type alone means a folder
        // that imports nothing.
        assertTrue(AudioFileFilter.isAudio("application/octet-stream", "Song.mp3"))
        assertTrue(AudioFileFilter.isAudio(null, "Song.flac"))
        assertTrue(AudioFileFilter.isAudio("", "Song.wav"))
    }

    @Test
    fun `containers reported as video are accepted on their extension`() {
        // m4a and mp4 share a container; providers disagree about which is which.
        assertTrue(AudioFileFilter.isAudio("video/mp4", "Song.m4a"))
    }

    @Test
    fun `non-audio files are left alone`() {
        assertFalse(AudioFileFilter.isAudio("image/jpeg", "cover.jpg"))
        assertFalse(AudioFileFilter.isAudio("text/plain", "notes.txt"))
        assertFalse(AudioFileFilter.isAudio(null, "artwork.png"))
        assertFalse(AudioFileFilter.isAudio(null, "README"))
    }

    @Test
    fun `directories are walked, not imported`() {
        assertTrue(AudioFileFilter.isDirectory(AudioFileFilter.DIRECTORY_MIME))
        assertFalse(AudioFileFilter.isAudio(AudioFileFilter.DIRECTORY_MIME, "Album"))
        assertFalse(AudioFileFilter.isDirectory("audio/mpeg"))
    }

    @Test
    fun `resource forks and dotfiles are ignored`() {
        // A folder copied from macOS is full of these, and every one of them
        // ends in a real audio extension.
        assertFalse(AudioFileFilter.isAudio("audio/mpeg", "._Song.mp3"))
        assertFalse(AudioFileFilter.isAudio(null, ".hidden.mp3"))
    }

    @Test
    fun `an empty or missing name is not audio`() {
        assertFalse(AudioFileFilter.isAudio("audio/mpeg", null))
        assertFalse(AudioFileFilter.isAudio("audio/mpeg", "   "))
    }

    @Test
    fun `extension matching ignores case`() {
        assertTrue(AudioFileFilter.isAudio(null, "SONG.MP3"))
        assertTrue(AudioFileFilter.isAudio(null, "Song.FlAc"))
    }

    @Test
    fun `a name with no extension is not guessed at`() {
        assertFalse(AudioFileFilter.isAudio(null, "Track 01"))
    }
}
