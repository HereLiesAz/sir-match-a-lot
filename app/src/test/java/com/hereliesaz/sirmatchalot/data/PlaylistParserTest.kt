package com.hereliesaz.sirmatchalot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing a playlist into its songs.
 *
 * The bug report these answer is exact and long-standing: *"I imported a
 * playlist, and it treated it as one single song."* So the assertion that
 * matters most in nearly every case below is the **count**.
 */
class PlaylistParserTest {

    // --- Format detection ---

    @Test
    fun `formats are detected from content, not from an extension`() {
        assertEquals(PlaylistParser.Format.M3U, PlaylistParser.detect("#EXTM3U\nfoo.mp3"))
        assertEquals(
            PlaylistParser.Format.XSPF,
            PlaylistParser.detect("""<?xml version="1.0"?><playlist xmlns="http://xspf.org/ns/0/"></playlist>"""),
        )
        assertEquals(
            PlaylistParser.Format.XML_FEED,
            PlaylistParser.detect("""<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"></feed>"""),
        )
        assertEquals(PlaylistParser.Format.PLAIN, PlaylistParser.detect("https://example.com/a.mp3"))
        assertEquals(PlaylistParser.Format.UNKNOWN, PlaylistParser.detect("   "))
    }

    @Test
    fun `a byte order mark does not defeat detection`() {
        // Servers serve BOM-prefixed M3U files constantly.
        assertEquals(PlaylistParser.Format.M3U, PlaylistParser.detect("\uFEFF#EXTM3U\nfoo.mp3"))
    }

    // --- M3U ---

    @Test
    fun `an M3U with three tracks yields three entries, not one`() {
        val document = """
            #EXTM3U
            #EXTINF:213,Daft Punk - Around the World
            https://example.com/around.mp3
            #EXTINF:242,Justice - Genesis
            https://example.com/genesis.mp3
            #EXTINF:190,Sebastian - Ross Ross Ross
            https://example.com/ross.mp3
        """.trimIndent()

        val entries = PlaylistParser.parse(document)
        assertEquals(3, entries.size)
        assertEquals(listOf("Around the World", "Genesis", "Ross Ross Ross"), entries.map { it.title })
        assertEquals(listOf("Daft Punk", "Justice", "Sebastian"), entries.map { it.artist })
        assertEquals(213.0, entries[0].durationSeconds!!, 1e-9)
        assertTrue(entries.all { it.hasAudio })
    }

    @Test
    fun `an M3U without EXTINF still yields one entry per location`() {
        val entries = PlaylistParser.parse(
            """
            #EXTM3U
            https://example.com/first.mp3
            https://example.com/second.mp3
            """.trimIndent(),
        )
        assertEquals(2, entries.size)
        assertEquals(listOf("first", "second"), entries.map { it.title })
        assertNull(entries[0].artist)
    }

    @Test
    fun `an unknown EXTINF duration of minus one is not reported as a duration`() {
        // Live streams use -1. Treating that as a length would put a track of
        // minus one second into the mix planner.
        val entries = PlaylistParser.parse("#EXTM3U\n#EXTINF:-1,Some Radio\nhttps://example.com/live")
        assertEquals(1, entries.size)
        assertNull(entries[0].durationSeconds)
    }

    @Test
    fun `comments other than EXTINF are ignored`() {
        val entries = PlaylistParser.parse(
            """
            #EXTM3U
            #PLAYLIST:My Set
            #EXTINF:100,A - B
            https://example.com/a.mp3
            """.trimIndent(),
        )
        assertEquals(1, entries.size)
        assertEquals("B", entries[0].title)
    }

    @Test
    fun `blank lines do not become tracks`() {
        val entries = PlaylistParser.parse("#EXTM3U\n\n\nhttps://example.com/a.mp3\n\n")
        assertEquals(1, entries.size)
    }

    // --- Artist and title splitting ---

    @Test
    fun `only the first separator splits artist from title`() {
        // "Röyksopp - Eple - Remix" is Röyksopp playing "Eple - Remix".
        assertEquals("Röyksopp" to "Eple - Remix", PlaylistParser.splitArtistTitle("Röyksopp - Eple - Remix"))
    }

    @Test
    fun `a label with no separator is all title`() {
        // Guessing an artist out of nothing is how the old analysis got its data.
        assertEquals(null to "Untitled Groove", PlaylistParser.splitArtistTitle("Untitled Groove"))
    }

    @Test
    fun `a hyphen without spaces does not split`() {
        assertEquals(null to "Jay-Z", PlaylistParser.splitArtistTitle("Jay-Z"))
    }

    @Test
    fun `an empty side falls back to the whole label`() {
        assertEquals(null to "- Title", PlaylistParser.splitArtistTitle("- Title"))
        assertEquals(null to "Artist -", PlaylistParser.splitArtistTitle("Artist -"))
    }

    // --- Titles derived from a location ---

    @Test
    fun `a title from a URL drops the path, query and extension`() {
        assertEquals(
            "Around the World",
            PlaylistParser.titleFromLocation("https://example.com/music/Around%20the%20World.mp3?token=abc"),
        )
    }

    @Test
    fun `underscores in a filename become spaces`() {
        assertEquals("Around the World", PlaylistParser.titleFromLocation("/music/Around_the_World.flac"))
    }

    @Test
    fun `a windows path is split on its own separator`() {
        assertEquals("Genesis", PlaylistParser.titleFromLocation("""C:\Music\Genesis.mp3"""))
    }

    // --- XSPF ---

    @Test
    fun `an XSPF playlist yields every track`() {
        val document = """
            <?xml version="1.0" encoding="UTF-8"?>
            <playlist version="1" xmlns="http://xspf.org/ns/0/">
              <trackList>
                <track>
                  <location>https://example.com/one.mp3</location>
                  <creator>Orbital</creator>
                  <title>Halcyon</title>
                  <duration>561000</duration>
                </track>
                <track>
                  <location>https://example.com/two.mp3</location>
                  <creator>Underworld</creator>
                  <title>Born Slippy</title>
                </track>
              </trackList>
            </playlist>
        """.trimIndent()

        val entries = PlaylistParser.parse(document)
        assertEquals(2, entries.size)
        assertEquals("Halcyon", entries[0].title)
        assertEquals("Orbital", entries[0].artist)
        assertEquals("https://example.com/one.mp3", entries[0].sourceUri)
        // XSPF durations are milliseconds.
        assertEquals(561.0, entries[0].durationSeconds!!, 1e-9)
        assertNull(entries[1].durationSeconds)
    }

    @Test
    fun `an XSPF track with only a location still gets a title`() {
        val entries = PlaylistParser.parse(
            """
            <playlist xmlns="http://xspf.org/ns/0/"><trackList>
              <track><location>https://example.com/Blue_Monday.mp3</location></track>
            </trackList></playlist>
            """.trimIndent(),
        )
        assertEquals(1, entries.size)
        assertEquals("Blue Monday", entries[0].title)
    }

    // --- Atom feeds, which is how a YouTube playlist reads without an API key ---

    @Test
    fun `a YouTube playlist feed yields one entry per video`() {
        // The shape YouTube serves at
        // youtube.com/feeds/videos.xml?playlist_id=… — keyless and documented.
        val document = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015"
                  xmlns="http://www.w3.org/2005/Atom">
              <title>My Mix</title>
              <entry>
                <yt:videoId>dQw4w9WgXcQ</yt:videoId>
                <title>Rick Astley - Never Gonna Give You Up</title>
                <author><name>Rick Astley</name></author>
              </entry>
              <entry>
                <yt:videoId>abcdefghijk</yt:videoId>
                <title>Another Song</title>
                <author><name>Someone Else</name></author>
              </entry>
            </feed>
        """.trimIndent()

        val entries = PlaylistParser.parse(document)
        assertEquals(2, entries.size)
        assertEquals("Rick Astley - Never Gonna Give You Up", entries[0].title)
        assertEquals("Rick Astley", entries[0].artist)
        assertEquals("dQw4w9WgXcQ", entries[0].externalId)
        // A YouTube entry names a song; it does not hand over audio.
        assertFalse(entries[0].hasAudio)
    }

    @Test
    fun `a podcast RSS enclosure is real audio and comes through playable`() {
        val document = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel>
              <item>
                <title>Episode One</title>
                <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg"/>
              </item>
              <item>
                <title>Episode Two</title>
                <enclosure url="https://example.com/ep2.mp3" type="audio/mpeg"/>
              </item>
            </channel></rss>
        """.trimIndent()

        val entries = PlaylistParser.parse(document)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.hasAudio })
        assertEquals("https://example.com/ep1.mp3", entries[0].sourceUri)
    }

    // --- Plain text ---

    @Test
    fun `a pasted list of links becomes one entry per line`() {
        val entries = PlaylistParser.parse(
            """
            https://example.com/a.mp3
            https://example.com/b.mp3
            """.trimIndent(),
        )
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.hasAudio })
    }

    @Test
    fun `a pasted tracklist becomes named songs with no audio yet`() {
        val entries = PlaylistParser.parse(
            """
            Daft Punk - Around the World
            Justice - Genesis
            """.trimIndent(),
        )
        assertEquals(2, entries.size)
        assertEquals("Daft Punk", entries[0].artist)
        assertEquals("Around the World", entries[0].title)
        assertFalse(entries[0].hasAudio)
    }

    // --- Degenerate input ---

    @Test
    fun `empty and unrecognisable input yields no entries rather than one`() {
        // Returning a single entry standing for the whole document is precisely
        // the bug being fixed, so this must be empty, not size 1.
        assertEquals(emptyList<PlaylistEntry>(), PlaylistParser.parse(""))
        assertEquals(emptyList<PlaylistEntry>(), PlaylistParser.parse("   \n  \n"))
        assertEquals(emptyList<PlaylistEntry>(), PlaylistParser.parse("<html><body>Not a playlist</body></html>"))
    }

    @Test
    fun `malformed XML yields nothing rather than throwing`() {
        assertEquals(emptyList<PlaylistEntry>(), PlaylistParser.parse("<feed><entry><title>Unclosed"))
    }

    @Test
    fun `a playlist cannot read local files through an entity reference`() {
        // A playlist is untrusted input fetched over the network. Without
        // doctype declarations disabled, this is a file disclosure.
        val attack = """
            <?xml version="1.0"?>
            <!DOCTYPE feed [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><title>&xxe;</title></entry>
            </feed>
        """.trimIndent()
        val entries = PlaylistParser.parse(attack)
        assertTrue(
            "an entity reference was expanded: ${entries.map { it.title }}",
            entries.none { it.title.contains("root:") },
        )
    }

    @Test
    fun `a large playlist is parsed in full`() {
        val document = buildString {
            appendLine("#EXTM3U")
            repeat(500) {
                appendLine("#EXTINF:200,Artist $it - Song $it")
                appendLine("https://example.com/$it.mp3")
            }
        }
        val entries = PlaylistParser.parse(document)
        assertEquals(500, entries.size)
        assertEquals("Song 499", entries.last().title)
    }
}
