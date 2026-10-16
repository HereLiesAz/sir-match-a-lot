package com.hereliesaz.sirmatchalot.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One song found in a playlist.
 *
 * @param sourceUri where the audio is, when the playlist said. Null for a
 *   playlist that names songs without pointing at files — a YouTube or Spotify
 *   listing, say — in which case the entry is still a real song the user asked
 *   for, and is carried into the library so it can be matched to audio later.
 * @param externalId the service's own identifier, kept so an entry can be
 *   re-resolved without re-parsing.
 */
data class PlaylistEntry(
    val title: String,
    val artist: String? = null,
    val sourceUri: String? = null,
    val durationSeconds: Double? = null,
    val externalId: String? = null,
) {
    /** True when this entry points at audio that can actually be decoded. */
    val hasAudio: Boolean get() = sourceUri != null
}

/**
 * Turns a playlist document into the songs it contains.
 *
 * The complaint this answers is old and exact: *"I imported a playlist, and it
 * treated it as one single song. Importing a playlist should import all the
 * songs in that playlist."* Nothing in the app had ever parsed a playlist —
 * [LinkParser] could classify a URL and was dead code, called from nowhere.
 *
 * Pure text in, entries out. No network, no Android, so every format below is
 * covered by ordinary unit tests, and the network layer above it can be replaced
 * without touching the parsing.
 *
 * Formats are detected from the content rather than trusted from a file
 * extension or a `Content-Type`, because servers routinely mislabel playlists
 * (`text/plain` for an M3U is the norm, not the exception).
 */
object PlaylistParser {

    /** Playlist formats understood. */
    enum class Format { M3U, XSPF, XML_FEED, PLAIN, UNKNOWN }

    /**
     * Parses [document], detecting its format.
     *
     * @param remote true when [document] was fetched from the network rather
     *   than pasted or picked on this device. Locations in it are then limited
     *   to `http`/`https`: a downloaded playlist naming `content://` or `file:`
     *   locations would have this app open whatever on-device URI a stranger
     *   chose. Locally supplied text may also name `content://` URIs. Relative
     *   paths and `file:` locations are never kept, from anywhere — they
     *   resolve against nothing meaningful and the app cannot read them anyway.
     *   An entry whose location is dropped is kept, by name, with no audio.
     * @return the songs, in playlist order. Empty when nothing recognisable is
     *   present — never a single entry standing for the whole playlist, which is
     *   the failure being fixed.
     */
    fun parse(document: String, remote: Boolean = false): List<PlaylistEntry> =
        parse(document, remote, DocumentBuilderFactory::newInstance)

    /** [parse] with the XML parser factory supplied, so its hardening can be tested. */
    internal fun parse(
        document: String,
        remote: Boolean,
        newFactory: () -> DocumentBuilderFactory,
    ): List<PlaylistEntry> {
        val entries = when (detect(document)) {
            Format.M3U -> parseM3u(document)
            Format.XSPF -> parseXspf(document, newFactory)
            Format.XML_FEED -> parseFeed(document, newFactory)
            Format.PLAIN -> parsePlain(document)
            Format.UNKNOWN -> emptyList()
        }
        return entries.map { entry ->
            val source = entry.sourceUri ?: return@map entry
            if (isAllowedSource(source, remote)) entry else entry.copy(sourceUri = null)
        }
    }

    /** Whether [location] may be kept as an entry's audio source; see [parse]. */
    internal fun isAllowedSource(location: String, remote: Boolean): Boolean {
        val scheme = location.substringBefore(':', missingDelimiterValue = "").lowercase()
        return when (scheme) {
            "http", "https" -> location.length > scheme.length + 3 &&
                location.regionMatches(scheme.length, "://", 0, 3)
            "content" -> !remote && location.startsWith("content://", ignoreCase = true)
            else -> false
        }
    }

    /** Best guess at [document]'s format, from its content. */
    fun detect(document: String): Format {
        val trimmed = document.trimStart().trimStart('\uFEFF')
        if (trimmed.isEmpty()) return Format.UNKNOWN
        if (trimmed.startsWith("#EXTM3U", ignoreCase = true)) return Format.M3U
        if (trimmed.startsWith("<")) {
            val head = trimmed.take(2048)
            return when {
                head.contains("<playlist", ignoreCase = true) &&
                    head.contains("xspf", ignoreCase = true) -> Format.XSPF
                head.contains("<playlist", ignoreCase = true) -> Format.XSPF
                head.contains("<feed", ignoreCase = true) ||
                    head.contains("<rss", ignoreCase = true) -> Format.XML_FEED
                else -> Format.UNKNOWN
            }
        }
        // An extension-less M3U is just a list of paths, which is also what a
        // pasted list of links looks like. Both parse the same way.
        return Format.PLAIN
    }

    // --- M3U / M3U8 ---

    private val extinf = Regex("""^#EXTINF\s*:\s*(-?[\d.]+)\s*(?:,\s*(.*))?$""", RegexOption.IGNORE_CASE)

    private fun parseM3u(document: String): List<PlaylistEntry> {
        val entries = ArrayList<PlaylistEntry>()
        var pendingTitle: String? = null
        var pendingArtist: String? = null
        var pendingDuration: Double? = null

        for (raw in document.lineSequence()) {
            val line = raw.trim().trimStart('\uFEFF')
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                val match = extinf.find(line) ?: continue
                pendingDuration = match.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 }
                val label = match.groupValues.getOrNull(2)?.trim().orEmpty()
                if (label.isNotEmpty()) {
                    val (artist, title) = splitArtistTitle(label)
                    pendingArtist = artist
                    pendingTitle = title
                }
                continue
            }

            // A non-comment line is the location of the previous #EXTINF's track.
            entries.add(
                PlaylistEntry(
                    title = pendingTitle ?: titleFromLocation(line),
                    artist = pendingArtist,
                    sourceUri = line,
                    durationSeconds = pendingDuration,
                ),
            )
            pendingTitle = null
            pendingArtist = null
            pendingDuration = null
        }
        return entries
    }

    // --- XSPF ---

    private fun parseXspf(document: String, newFactory: () -> DocumentBuilderFactory): List<PlaylistEntry> {
        val root = parseXml(document, newFactory) ?: return emptyList()
        return root.elementsNamed("track").map { track ->
            val creator = track.childText("creator")
            val title = track.childText("title") ?: track.childText("location")?.let(::titleFromLocation)
            PlaylistEntry(
                title = title ?: "Untitled",
                artist = creator,
                sourceUri = track.childText("location"),
                // XSPF durations are milliseconds.
                durationSeconds = track.childText("duration")?.toDoubleOrNull()?.takeIf { it > 0 }?.div(1000.0),
            )
        }
    }

    // --- Atom and RSS ---

    /**
     * Parses an Atom or RSS feed.
     *
     * This covers YouTube playlists without an API key: YouTube publishes every
     * public playlist at
     * `youtube.com/feeds/videos.xml?playlist_id=…` as Atom, which is a
     * documented, keyless, terms-respecting way to read what is *in* a playlist.
     * It also covers podcast feeds, where the enclosure is real audio and the
     * entry therefore arrives with a playable [PlaylistEntry.sourceUri].
     */
    private fun parseFeed(document: String, newFactory: () -> DocumentBuilderFactory): List<PlaylistEntry> {
        val root = parseXml(document, newFactory) ?: return emptyList()
        val items = root.elementsNamed("entry") + root.elementsNamed("item")
        return items.map { item ->
            // A podcast enclosure points at real audio; a YouTube entry does not.
            val enclosure = item.elementsNamed("enclosure").firstOrNull()?.getAttribute("url")
                ?.takeIf { it.isNotBlank() }
            val author = item.elementsNamed("author").firstOrNull()?.childText("name")
                ?: item.childText("author")
                ?: item.childText("creator")
            PlaylistEntry(
                title = item.childText("title") ?: "Untitled",
                artist = author,
                sourceUri = enclosure,
                externalId = item.childText("videoId") ?: item.childText("guid"),
            )
        }
    }

    // --- A plain list of links or names ---

    private fun parsePlain(document: String): List<PlaylistEntry> =
        document.lineSequence()
            .map { it.trim().trimStart('\uFEFF') }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                if (looksLikeLocation(line)) {
                    PlaylistEntry(title = titleFromLocation(line), sourceUri = line)
                } else {
                    val (artist, title) = splitArtistTitle(line)
                    PlaylistEntry(title = title, artist = artist)
                }
            }
            .toList()

    // --- Shared helpers ---

    /**
     * Splits "Artist - Title" without mangling titles that contain a hyphen.
     *
     * Only the *first* " - " separates, so "Röyksopp - Eple - Remix" is Röyksopp
     * playing "Eple - Remix", not a track called "Eple". A label with no
     * separator is all title, because guessing an artist from nothing is how the
     * old analysis got its data.
     */
    internal fun splitArtistTitle(label: String): Pair<String?, String> {
        val separator = label.indexOf(" - ")
        if (separator <= 0) return null to label.trim()
        val artist = label.substring(0, separator).trim()
        val title = label.substring(separator + 3).trim()
        if (artist.isEmpty() || title.isEmpty()) return null to label.trim()
        return artist to title
    }

    private fun looksLikeLocation(line: String): Boolean =
        line.startsWith("http://", ignoreCase = true) ||
            line.startsWith("https://", ignoreCase = true) ||
            line.startsWith("file:", ignoreCase = true) ||
            line.startsWith("content:", ignoreCase = true) ||
            line.startsWith("/") ||
            line.contains('\\')

    /** A readable name for a track the playlist gave only a location for. */
    internal fun titleFromLocation(location: String): String {
        val withoutQuery = location.substringBefore('?').substringBefore('#')
        val name = withoutQuery.trimEnd('/').substringAfterLast('/').substringAfterLast('\\')
        val decoded = runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
        val stem = decoded.substringBeforeLast('.').replace('_', ' ').trim()
        return stem.ifEmpty { decoded.ifEmpty { location } }
    }

    /**
     * Parses untrusted XML with entity expansion and external access shut off.
     *
     * A playlist is untrusted input from the network; an external entity in it
     * could read local files, and an internal one can expand into gigabytes.
     * Each hardening feature is set on its own, because parsers differ in what
     * they support — Android's does not recognise every Xerces feature — and
     * setting them all inside the same `runCatching` as the parse meant one
     * unsupported feature failed every XML playlist, not just hostile ones.
     * The one that matters most is refusing DOCTYPE outright; where the parser
     * cannot be told to, any document that declares one is refused here
     * instead, before the parser sees it.
     */
    private fun parseXml(document: String, newFactory: () -> DocumentBuilderFactory): Element? {
        val factory = runCatching { newFactory() }.getOrNull() ?: return null
        factory.isNamespaceAware = false
        val doctypeRefused = runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.isSuccess
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.isExpandEntityReferences = false }

        if (!doctypeRefused && document.contains("<!DOCTYPE", ignoreCase = true)) return null

        return runCatching {
            factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(document.toByteArray(Charsets.UTF_8)))
                .documentElement
        }.getOrNull()
    }

    /**
     * Descendant elements whose local name is [name], namespace prefixes
     * ignored — feeds mix `yt:videoId`, `media:title` and bare tags freely, and
     * a namespace-aware match would miss most of them.
     */
    private fun Element.elementsNamed(name: String): List<Element> {
        val found = ArrayList<Element>()
        fun walk(node: Node) {
            if (node is Element && node.localOrTagName.equals(name, ignoreCase = true)) found.add(node)
            val children = node.childNodes
            for (i in 0 until children.length) walk(children.item(i))
        }
        walk(this)
        return found
    }

    /** Text of the nearest descendant element named [name]. */
    private fun Element.childText(name: String): String? =
        elementsNamed(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private val Node.localOrTagName: String
        get() = nodeName.substringAfterLast(':')
}
