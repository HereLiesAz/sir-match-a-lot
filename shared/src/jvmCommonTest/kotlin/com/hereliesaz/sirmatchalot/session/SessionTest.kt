package com.hereliesaz.sirmatchalot.session

import com.hereliesaz.sirmatchalot.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saving a session and getting it back.
 *
 * The interesting case is never the one where the file is opened on the machine
 * that wrote it — there the ids match and nothing is being tested. It is the
 * reinstall, the second device, the file someone was sent: the ids mean nothing
 * and the format has to answer "is this the same song" from what it wrote down.
 *
 * The rule these tests keep coming back to is that **a wrong match is worse than
 * a miss**. A track reported missing is a job the user can finish; a session that
 * quietly loads a different recording is a set that goes out sounding wrong for
 * reasons nobody can see.
 */
class SessionTest {

    private fun track(
        id: String = "t1",
        title: String = "Nightdrive",
        artist: String = "Tester",
        durationMs: Long = 247_000,
        uri: String? = "content://media/1",
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        durationMs = durationMs,
        sourceUri = uri,
        bpm = 128.0,
        camelotKey = "8A",
        firstBeatSeconds = 0.25,
        energyLevel = 6,
    )

    private fun documentOf(vararg tracks: Track) = SessionDocument(
        name = "Friday",
        savedAtMillis = 1_700_000_000_000,
        deckA = tracks.map { SessionClip(it.toSessionTrack(), startSeconds = 12.5) },
    )

    // --- The manifest ---

    @Test
    fun `a session round-trips through its own encoding`() {
        val document = documentOf(track())
        val back = SessionDocument.decode(document.encode())
        assertEquals(document, back)
    }

    @Test
    fun `positions are kept in seconds so a rate change cannot move them`() {
        // Frames are meaningless at a different engine sample rate, and the rate
        // is a setting the user can change between saving and opening.
        val document = documentOf(track())
        val back = SessionDocument.decode(document.encode())!!
        assertEquals(12.5, back.deckA.single().startSeconds, 1e-9)
    }

    @Test
    fun `a file from the future is refused rather than half-read`() {
        // An older reader guessing at a newer layout produces a session that is
        // confidently wrong, which is worse than one that will not open.
        val ahead = documentOf(track()).copy(version = SessionDocument.FORMAT_VERSION + 1)
        assertNull(SessionDocument.decode(ahead.encode()))
    }

    @Test
    fun `nonsense is not a session`() {
        for (text in listOf("", "{", "[]", "not json at all", "{\"version\":0}")) {
            assertNull("'$text' parsed", SessionDocument.decode(text))
        }
    }

    @Test
    fun `unknown fields from a later minor version are ignored, not fatal`() {
        val text = """{"version":1,"name":"Friday","somethingNew":42}"""
        assertEquals("Friday", SessionDocument.decode(text)?.name)
    }

    @Test
    fun `every track referred to is listed once`() {
        val a = track(id = "a", title = "A")
        val b = track(id = "b", title = "B")
        val document = SessionDocument(
            deckA = listOf(SessionClip(a.toSessionTrack())),
            deckB = listOf(SessionClip(b.toSessionTrack())),
            lineup = listOf(SessionStep(a.toSessionTrack()), SessionStep(b.toSessionTrack())),
        )
        assertEquals(listOf("a", "b"), document.tracks().map { it.id })
    }

    // --- The archive ---

    @Test
    fun `an archive carries the manifest and the takes`() {
        val takes = mapOf("pad-0.wav" to WavCodec.encode(ShortArray(400) { 1000 }, 44_100, 2))
        val bytes = SessionArchive.writeToBytes(documentOf(track()), takes)

        val read = SessionArchive.read(bytes.inputStream())
        assertNotNull(read)
        assertEquals("Friday", read!!.document.name)
        assertNotNull("the take did not survive", read.take("pad-0.wav"))
    }

    @Test
    fun `a take comes back as the audio that went in`() {
        // Takes are the one thing in a session that exists nowhere else. Every
        // track can be found again; a recording someone made over their own mix
        // cannot.
        val samples = ShortArray(2_000) { (it * 7 % 30_000 - 15_000).toShort() }
        val takes = mapOf("pad-1.wav" to WavCodec.encode(samples, 48_000, 2))
        val bytes = SessionArchive.writeToBytes(documentOf(track()), takes)

        val wav = SessionArchive.read(bytes.inputStream())!!.take("pad-1.wav")!!
        assertEquals(48_000, wav.sampleRate)
        assertEquals(2, wav.channels)
        assertTrue("samples changed", wav.samples.contentEquals(samples))
    }

    @Test
    fun `something that is not a zip is not a session`() {
        assertNull(SessionArchive.read("just some bytes".toByteArray().inputStream()))
    }

    @Test
    fun `a zip with no manifest is not a session`() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("readme.txt"))
            it.write("hello".toByteArray())
            it.closeEntry()
        }
        assertNull(SessionArchive.read(out.toByteArray().inputStream()))
    }

    @Test
    fun `a take whose name escapes its directory is refused`() {
        // Nothing here writes a take to disk by its archive name, so this is not
        // the last line of defence — but a name that climbs out of its directory
        // is malformed whatever it is used for, and refusing it here means no
        // future caller inherits the assumption that it is safe.
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(SessionDocument.MANIFEST))
            zip.write(documentOf(track()).encode().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("${SessionDocument.TAKES}/../../escaped.wav"))
            zip.write(ByteArray(16))
            zip.closeEntry()
        }
        val read = SessionArchive.read(out.toByteArray().inputStream())
        assertNotNull(read)
        assertTrue("an escaping entry was kept", read!!.takes.isEmpty())
    }

    // --- Finding the tracks again ---

    @Test
    fun `the same library matches on id`() {
        val library = listOf(track(id = "t1"))
        val restore = SessionResolver.resolve(documentOf(track(id = "t1")), library)
        assertEquals(mapOf("t1" to "t1"), restore.resolved)
        assertTrue(restore.isComplete)
    }

    @Test
    fun `another device matches on title, artist and length`() {
        // The case the whole format exists for: same song, different install, so
        // the id it was saved under means nothing here.
        val saved = documentOf(track(id = "old-id"))
        val library = listOf(track(id = "new-id", uri = "content://elsewhere/9"))

        val restore = SessionResolver.resolve(saved, library)
        assertEquals("new-id", restore.resolved["old-id"])
        assertTrue(restore.isComplete)
    }

    @Test
    fun `a different credit on the same recording still matches`() {
        val saved = documentOf(track(id = "old", artist = "Tester"))
        val library = listOf(track(id = "new", artist = "Tester feat. Someone"))
        assertEquals("new", SessionResolver.resolve(saved, library).resolved["old"])
    }

    @Test
    fun `a different recording of the same title does not match`() {
        // Same name, four minutes apart: an extended mix is not the radio edit,
        // and playing one where the other was planned is the failure this format
        // is most able to cause.
        val saved = documentOf(track(id = "old", durationMs = 247_000))
        val library = listOf(track(id = "new", durationMs = 494_000))

        val restore = SessionResolver.resolve(saved, library)
        assertTrue("matched the wrong recording", restore.resolved.isEmpty())
        assertEquals(1, restore.missing.size)
    }

    @Test
    fun `two unmeasured tracks do not match each other by having no length`() {
        // Duration zero means "never decoded", not "zero seconds long". Treating
        // it as evidence would match any unmeasured track to any other.
        val saved = documentOf(track(id = "old", durationMs = 0, title = "Unknown"))
        val library = listOf(track(id = "new", durationMs = 0, title = "Unknown", uri = null))
        assertTrue(SessionResolver.resolve(saved, library).resolved.isEmpty())
    }

    @Test
    fun `the source path is a last resort, not the first`() {
        // A path that has been reused for a different file must not shadow a real
        // match, so it is tried only when nothing else worked.
        val saved = documentOf(track(id = "old", title = "Nightdrive", uri = "content://media/1"))
        val library = listOf(
            track(id = "reused-path", title = "Something Else", durationMs = 100_000, uri = "content://media/1"),
            track(id = "real", title = "Nightdrive", durationMs = 247_000, uri = "content://media/77"),
        )
        assertEquals("real", SessionResolver.resolve(saved, library).resolved["old"])
    }

    @Test
    fun `a path match is used when nothing else identifies the track`() {
        // Renamed in the library and never decoded, so there is no length to
        // compare and nothing contradicts the path.
        val saved = documentOf(track(id = "old", title = "Renamed Since", uri = "content://media/1"))
        val library = listOf(track(id = "same-file", title = "Original Name", durationMs = 0))
        assertEquals("same-file", SessionResolver.resolve(saved, library).resolved["old"])
    }

    @Test
    fun `a path whose file has been replaced is not trusted`() {
        // Files get swapped in place and a re-scan hands the same URI to whatever
        // is there now. A length that disagrees means the hint is stale.
        val saved = documentOf(track(id = "old", durationMs = 247_000, uri = "content://media/1"))
        val library = listOf(
            track(id = "replaced", title = "Entirely Different", durationMs = 90_000, uri = "content://media/1"),
        )
        assertTrue(SessionResolver.resolve(saved, library).resolved.isEmpty())
    }

    @Test
    fun `one library track cannot satisfy two saved tracks`() {
        // Otherwise a session with a duplicate would open playing one file twice
        // and report itself complete.
        val saved = SessionDocument(
            deckA = listOf(
                SessionClip(track(id = "a", title = "Same", durationMs = 200_000).toSessionTrack()),
                SessionClip(track(id = "b", title = "Same", durationMs = 200_000).toSessionTrack()),
            ),
        )
        val library = listOf(track(id = "only", title = "Same", durationMs = 200_000))

        val restore = SessionResolver.resolve(saved, library)
        assertEquals(1, restore.resolved.size)
        assertEquals(1, restore.missing.size)
    }

    @Test
    fun `what could not be found is named`() {
        // A session that opens short and says nothing looks like the app failing.
        val saved = SessionDocument(
            name = "Friday",
            deckA = listOf(
                SessionClip(track(id = "here", title = "Here").toSessionTrack()),
                SessionClip(track(id = "gone", title = "Gone Forever").toSessionTrack()),
            ),
        )
        val restore = SessionResolver.resolve(saved, listOf(track(id = "here", title = "Here")))

        assertTrue(!restore.isComplete)
        assertEquals(listOf("Gone Forever"), restore.missing.map { it.title })
        assertTrue(restore.summary().contains("Gone Forever"))
    }

    @Test
    fun `an empty library loses everything and says so`() {
        val restore = SessionResolver.resolve(documentOf(track()), emptyList())
        assertEquals(1, restore.missing.size)
        assertTrue(restore.summary().contains("not in your library"))
    }

    @Test
    fun `a session with nothing in it says that rather than claiming success`() {
        val restore = SessionResolver.resolve(SessionDocument(name = "Empty"), emptyList())
        assertTrue(restore.summary().contains("no tracks"))
    }

    // --- WAV ---

    @Test
    fun `wav round-trips`() {
        val samples = ShortArray(1_000) { (it - 500).toShort() }
        val decoded = WavCodec.decode(WavCodec.encode(samples, 44_100, 2))!!

        assertEquals(44_100, decoded.sampleRate)
        assertEquals(2, decoded.channels)
        assertEquals(500, decoded.frameCount)
        assertTrue(decoded.samples.contentEquals(samples))
    }

    @Test
    fun `wav deinterleaves into the shape a pad wants`() {
        val samples = shortArrayOf(1, -1, 2, -2, 3, -3)
        val channels = WavCodec.decode(WavCodec.encode(samples, 8_000, 2))!!.channels()

        assertTrue(channels[0].contentEquals(shortArrayOf(1, 2, 3)))
        assertTrue(channels[1].contentEquals(shortArrayOf(-1, -2, -3)))
    }

    @Test
    fun `a wav with metadata chunks in the middle still reads`() {
        // Plenty of encoders put LIST between fmt and data. Assuming a minimal
        // layout means a take exported, edited elsewhere and brought back fails
        // to open for no reason the user can see.
        val audio = WavCodec.encode(shortArrayOf(7, 8, 9, 10), 22_050, 2)
        val withList = java.nio.ByteBuffer
            .allocate(audio.size + 12)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        // Everything up to the end of the fmt chunk, then a LIST, then the rest.
        withList.put(audio, 0, 36)
        withList.put("LIST".toByteArray(Charsets.US_ASCII))
        withList.putInt(4)
        withList.put("INFO".toByteArray(Charsets.US_ASCII))
        withList.put(audio, 36, audio.size - 36)
        // The RIFF size field is now wrong by twelve, which readers ignore.
        val decoded = WavCodec.decode(withList.array())

        assertNotNull("a LIST chunk broke it", decoded)
        assertTrue(decoded!!.samples.contentEquals(shortArrayOf(7, 8, 9, 10)))
    }

    @Test
    fun `things that are not wav are refused`() {
        assertNull(WavCodec.decode(ByteArray(0)))
        assertNull(WavCodec.decode("RIFFnope".toByteArray()))
        assertNull(WavCodec.decode(ByteArray(64) { 0 }))
    }

    @Test
    fun `an empty take is still a valid file`() {
        val decoded = WavCodec.decode(WavCodec.encode(ShortArray(0), 44_100, 2))
        assertNotNull(decoded)
        assertEquals(0, decoded!!.frameCount)
    }
}
