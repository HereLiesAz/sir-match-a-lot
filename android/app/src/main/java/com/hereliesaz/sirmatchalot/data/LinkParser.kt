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

    fun parseFileName(fileName: String): Pair<String, String> {
        val cleanName = fileName.substringBeforeLast(".")
            .replace("_", " ")
            .replace("-", " - ")
            .split(Pattern.compile("\\s+-\\s+"))

        return if (cleanName.size >= 2) {
            Pair(cleanName[1].trim(), cleanName[0].trim()) // Title, Artist
        } else {
            Pair(cleanName[0].trim(), "Unknown Artist") // Title, Unknown
        }
    }
}
