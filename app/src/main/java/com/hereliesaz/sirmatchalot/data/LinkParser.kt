package com.hereliesaz.sirmatchalot.data

import java.net.URLDecoder
import java.util.regex.Pattern

object LinkParser {
    private val youtubeVideoPattern = Pattern.compile(
        "^https?://(?:www\\.)?(?:youtube\\.com/watch\\?v=|youtu\\.be/)([a-zA-Z0-9_-]{11})"
    )
    private val youtubePlaylistPattern = Pattern.compile(
        "[?&]list=([a-zA-Z0-9_-]+)"
    )
    private val spotifyPlaylistPattern = Pattern.compile(
        "spotify\\.com/playlist/([a-zA-Z0-9]+)"
    )
    private val spotifyTrackPattern = Pattern.compile(
        "spotify\\.com/track/([a-zA-Z0-9]+)"
    )

    data class ParsedLink(
        val type: LinkType,
        val id: String?,
        val queryHint: String? = null
    )

    enum class LinkType {
        YOUTUBE_VIDEO,
        YOUTUBE_PLAYLIST,
        SPOTIFY_PLAYLIST,
        SPOTIFY_TRACK,
        RAW_TEXT,
        UNKNOWN_URL
    }

    fun parse(input: String): ParsedLink {
        val trimmed = input.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return ParsedLink(LinkType.RAW_TEXT, null, trimmed)
        }

        val ytVideoMatch = youtubeVideoPattern.matcher(trimmed)
        if (ytVideoMatch.find()) {
            return ParsedLink(LinkType.YOUTUBE_VIDEO, ytVideoMatch.group(1))
        }

        val ytPlaylistMatch = youtubePlaylistPattern.matcher(trimmed)
        if (ytPlaylistMatch.find()) {
            return ParsedLink(LinkType.YOUTUBE_PLAYLIST, ytPlaylistMatch.group(1))
        }

        val spotPlaylistMatch = spotifyPlaylistPattern.matcher(trimmed)
        if (spotPlaylistMatch.find()) {
            return ParsedLink(LinkType.SPOTIFY_PLAYLIST, spotPlaylistMatch.group(1))
        }

        val spotTrackMatch = spotifyTrackPattern.matcher(trimmed)
        if (spotTrackMatch.find()) {
            return ParsedLink(LinkType.SPOTIFY_TRACK, spotTrackMatch.group(1))
        }

        return ParsedLink(LinkType.UNKNOWN_URL, null, trimmed)
    }

    /**
     * Splits "Artist - Title" (or "Artist_-_Title", "Artist_Title") from a
     * bare file name, at the *first* separator only.
     *
     * This used to split on every hyphen and then always read positions 0
     * and 1 — so "01 - Artist - Song.mp3" (a numbered track, the ordinary
     * case for a ripped album) produced title "Artist" and artist "01", with
     * "Song" simply dropped, and any name with more than one hyphen lost
     * everything past the second segment the same way. Only the first
     * separator is meaningful; whatever comes after it — including more
     * hyphens — is part of the title, exactly as `PlaylistParser`'s own
     * "Artist - Title" rule already treats them.
     */
    fun parseFileName(fileName: String): Pair<String, String> {
        val cleaned = fileName.substringBeforeLast(".")
            .replace("_", " ")
            .replace(Regex("\\s*-\\s*"), " - ")
            .trim()

        val separator = cleaned.indexOf(" - ")
        if (separator <= 0) return cleaned.trim() to "Unknown Artist"

        val artist = cleaned.substring(0, separator).trim()
        val title = cleaned.substring(separator + 3).trim()
        if (artist.isEmpty() || title.isEmpty()) return cleaned.trim() to "Unknown Artist"
        return title to artist
    }
}
