package com.hereliesaz.sirmatchalot.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sharing a loaded session as a link.
 *
 * Query parameters rather than an opaque blob, as asked for — and because a
 * legible link can be edited, diffed and read by anything, including the web
 * page and anything built against the public API.
 */
class SessionLinkTest {

    private val session = SessionLink(
        deckA = listOf(SessionLink.TrackRef("Around the World", "Daft Punk")),
        deckB = listOf(SessionLink.TrackRef("Genesis", "Justice")),
        cuesA = listOf(0.0, 32.5),
        cuesB = listOf(16.25),
        crossfade = -40,
        referenceBpm = 123.5,
        referenceKey = "8A",
        roomCode = "QRTP",
    )

    @Test
    fun `a session round trips through a link`() {
        val restored = SessionLink.fromUrl(session.toUrl())
        assertEquals(session.deckA, restored.deckA)
        assertEquals(session.deckB, restored.deckB)
        assertEquals(session.cuesA, restored.cuesA)
        assertEquals(session.cuesB, restored.cuesB)
        assertEquals(session.crossfade, restored.crossfade)
        assertEquals(session.referenceBpm!!, restored.referenceBpm!!, 1e-6)
        assertEquals(session.referenceKey, restored.referenceKey)
        assertEquals(session.roomCode, restored.roomCode)
    }

    @Test
    fun `the link is readable query parameters, not a blob`() {
        val url = session.toUrl()
        assertTrue(url, url.contains("?"))
        assertTrue(url, url.contains("bpm=123.5"))
        assertTrue(url, url.contains("key=8A"))
        assertTrue(url, url.contains("x=-40"))
    }

    @Test
    fun `several tracks on one deck each get their own parameter`() {
        val many = SessionLink(
            deckA = listOf(
                SessionLink.TrackRef("One", "A"),
                SessionLink.TrackRef("Two", "B"),
                SessionLink.TrackRef("Three", "C"),
            ),
        )
        val restored = SessionLink.fromUrl(many.toUrl())
        assertEquals(3, restored.deckA.size)
        assertEquals(listOf("One", "Two", "Three"), restored.deckA.map { it.title })
    }

    @Test
    fun `order on a deck is preserved`() {
        val ordered = SessionLink(deckB = listOf(
            SessionLink.TrackRef("First", "X"),
            SessionLink.TrackRef("Second", "Y"),
        ))
        assertEquals(
            listOf("First", "Second"),
            SessionLink.fromUrl(ordered.toUrl()).deckB.map { it.title },
        )
    }

    @Test
    fun `titles containing separators and spaces survive`() {
        // "&", "=" and "?" in a title would otherwise cut the link in half.
        val awkward = SessionLink(
            deckA = listOf(SessionLink.TrackRef("Q&A = ?", "Someone")),
        )
        val restored = SessionLink.fromUrl(awkward.toUrl())
        assertEquals(1, restored.deckA.size)
        assertEquals("Q&A = ?", restored.deckA[0].title)
        assertEquals("Someone", restored.deckA[0].artist)
    }

    @Test
    fun `non-ascii titles survive`() {
        val restored = SessionLink.fromUrl(
            SessionLink(deckA = listOf(SessionLink.TrackRef("Eple", "Röyksopp"))).toUrl(),
        )
        assertEquals("Röyksopp", restored.deckA[0].artist)
    }

    @Test
    fun `only the first separator splits artist from title`() {
        // Same rule as the playlist parser: "Röyksopp - Eple - Remix" is
        // Röyksopp playing "Eple - Remix", not a track called "Eple".
        val parsed = SessionLink.parseTrack("Röyksopp - Eple - Remix")
        assertEquals("Röyksopp", parsed.artist)
        assertEquals("Eple - Remix", parsed.title)
    }

    @Test
    fun `a track with no artist is all title`() {
        val parsed = SessionLink.parseTrack("Untitled Groove")
        assertEquals("", parsed.artist)
        assertEquals("Untitled Groove", parsed.title)
    }

    @Test
    fun `an empty session produces a bare link`() {
        val url = SessionLink().toUrl()
        assertTrue(url, !url.contains("?"))
        assertTrue(SessionLink().isEmpty)
    }

    @Test
    fun `a link with no query yields an empty session`() {
        assertTrue(SessionLink.fromUrl("https://example.com/").isEmpty)
        assertTrue(SessionLink.fromUrl("").isEmpty)
    }

    @Test
    fun `unknown parameters are ignored rather than rejected`() {
        // A link written by a newer version must still open on an older one.
        val restored = SessionLink.fromUrl(
            "https://example.com/?a=A%20-%20T&somethingNew=42&anotherThing=x",
        )
        assertEquals(1, restored.deckA.size)
        assertEquals("T", restored.deckA[0].title)
    }

    @Test
    fun `a malformed cue is skipped rather than losing the session`() {
        val restored = SessionLink.fromUrl("https://example.com/?a=A%20-%20T&ca=1,notanumber,3")
        assertEquals(listOf(1.0, 3.0), restored.cuesA)
        assertEquals(1, restored.deckA.size)
    }

    @Test
    fun `a negative cue is dropped`() {
        assertEquals(listOf(4.0), SessionLink.fromUrl("https://example.com/?ca=-2,4").cuesA)
    }

    @Test
    fun `a crossfade beyond the ends is clamped`() {
        assertEquals(100, SessionLink.fromUrl("https://example.com/?x=5000").crossfade)
        assertEquals(-100, SessionLink.fromUrl("https://example.com/?x=-5000").crossfade)
    }

    @Test
    fun `a nonsensical tempo is dropped rather than believed`() {
        assertNull(SessionLink.fromUrl("https://example.com/?bpm=0").referenceBpm)
        assertNull(SessionLink.fromUrl("https://example.com/?bpm=-120").referenceBpm)
        assertNull(SessionLink.fromUrl("https://example.com/?bpm=abc").referenceBpm)
    }

    @Test
    fun `a room code is normalised to upper case`() {
        assertEquals("QRTP", SessionLink.fromUrl("https://example.com/?room=qrtp").roomCode)
    }

    @Test
    fun `whole-number values are written without a decimal tail`() {
        val url = SessionLink(cuesA = listOf(4.0, 8.0), referenceBpm = 120.0).toUrl()
        assertTrue(url, url.contains("ca=4,8"))
        assertTrue(url, url.contains("bpm=120"))
    }
}
