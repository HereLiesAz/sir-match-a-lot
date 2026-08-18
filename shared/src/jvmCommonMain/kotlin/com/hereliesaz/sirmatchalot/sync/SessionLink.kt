package com.hereliesaz.sirmatchalot.sync

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * A loaded session, as a link.
 *
 * Asked for directly: *"exports the currently loaded 'training session' (two
 * tracks, their cue points, and loop settings) as a shareable link using query
 * parameters."*
 *
 * Query parameters rather than an opaque blob, because the request said so and
 * because it is the right call: a link whose contents are legible can be edited,
 * diffed, and read by anything — including the web page in `web/`, and anything
 * built against the public API. An encoded blob would make the format private to
 * whichever version wrote it.
 *
 * Tracks are identified by title and artist rather than by database id. An id is
 * meaningless on the device receiving the link, which has its own library and
 * has never seen the sender's row. Title and artist are what a person can match
 * by hand when the automatic match fails.
 */
data class SessionLink(
    val deckA: List<TrackRef> = emptyList(),
    val deckB: List<TrackRef> = emptyList(),
    /**
     * The four cue slots, in order — `null` for a slot that was never set.
     *
     * Kept positional rather than compacted: cue button 1 through 4 on a
     * deck are fixed, addressable slots, not "the first N cues that happen
     * to be set". Exporting only the non-null ones and re-assigning them to
     * slots 1..N on import used to mean a track with only its third cue set
     * came back with that time in slot 1 and every other slot empty.
     */
    val cuesA: List<Double?> = emptyList(),
    val cuesB: List<Double?> = emptyList(),
    val crossfade: Int = 0,
    val referenceBpm: Double? = null,
    val referenceKey: String? = null,
    val roomCode: String? = null,
) {
    /** One track, named the way another library can look it up. */
    data class TrackRef(val title: String, val artist: String) {
        override fun toString(): String = if (artist.isBlank()) title else "$artist - $title"
    }

    /** True when there is nothing worth sharing. */
    val isEmpty: Boolean get() = deckA.isEmpty() && deckB.isEmpty()

    /**
     * This session as a URL.
     *
     * @param base where the link points. The default is the project's own page,
     *   which can read these parameters; any host that understands them works.
     */
    fun toUrl(base: String = DEFAULT_BASE): String {
        val parameters = ArrayList<Pair<String, String>>()
        deckA.forEach { parameters.add("a" to it.toString()) }
        deckB.forEach { parameters.add("b" to it.toString()) }
        // An unset slot is an empty segment, not omitted — "60,,,90" says
        // slots 1 and 4 are set and 2 and 3 are not, where dropping the
        // empty slots would read back as "the first two slots are set".
        if (cuesA.any { it != null }) parameters.add("ca" to cuesA.joinToString(",") { it?.let(::trim) ?: "" })
        if (cuesB.any { it != null }) parameters.add("cb" to cuesB.joinToString(",") { it?.let(::trim) ?: "" })
        if (crossfade != 0) parameters.add("x" to crossfade.toString())
        referenceBpm?.let { parameters.add("bpm" to trim(it)) }
        referenceKey?.let { parameters.add("key" to it) }
        roomCode?.let { parameters.add("room" to it) }

        if (parameters.isEmpty()) return base
        return base + "?" + parameters.joinToString("&") { (name, value) ->
            "$name=${encode(value)}"
        }
    }

    // Locale.ROOT: this is a machine-readable field parsed back with
    // toDoubleOrNull and split on ',' — a comma-decimal locale (de, fr,
    // pt-BR, ...) would emit "4,500" for a cue at 4.5s and have it read back
    // as two cues, or drop the reference BPM outright when it parses as
    // 128,500 and toDoubleOrNull rejects the comma.
    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(Locale.ROOT, "%.3f", value)

    companion object {
        const val DEFAULT_BASE = "https://hereliesaz.github.io/sir-match-a-lot/"

        /** Far more than a deck's own [com.hereliesaz.sirmatchalot.domain.DeckCapacity] admits. */
        private const val MAX_TRACKS_PER_DECK = 200

        /** A generous bound on a query string nobody types by hand. */
        private const val MAX_QUERY_PARAMETERS = 1000

        /**
         * Reads a link back.
         *
         * Unknown parameters are ignored rather than rejected, so a link written
         * by a newer version still opens on an older one with whatever it does
         * understand. A malformed number is skipped rather than failing the
         * whole link — losing one cue point is better than losing the session.
         */
        fun fromUrl(url: String): SessionLink {
            val query = url.substringAfter('?', "")
            if (query.isBlank()) return SessionLink()

            val deckA = ArrayList<TrackRef>()
            val deckB = ArrayList<TrackRef>()
            var cuesA = emptyList<Double?>()
            var cuesB = emptyList<Double?>()
            var crossfade = 0
            var bpm: Double? = null
            var key: String? = null
            var room: String? = null

            // Every other field here is bounded by construction — a
            // crossfade position, a BPM, four cue slots — except a deck's
            // track list, which had no cap at all: a pasted link with
            // thousands of `a=` parameters decoded that many clips onto one
            // deck. `openSessionLink` resolves and loads each one against
            // the library, so this is also a bound on how much work one
            // link can trigger.
            for (pair in query.split('&').take(MAX_QUERY_PARAMETERS)) {
                if (pair.isBlank()) continue
                val separator = pair.indexOf('=')
                if (separator <= 0) continue
                val name = pair.substring(0, separator)
                val value = decode(pair.substring(separator + 1))
                if (value.isBlank()) continue

                when (name) {
                    "a" -> if (deckA.size < MAX_TRACKS_PER_DECK) deckA.add(parseTrack(value))
                    "b" -> if (deckB.size < MAX_TRACKS_PER_DECK) deckB.add(parseTrack(value))
                    "ca" -> cuesA = parseCues(value)
                    "cb" -> cuesB = parseCues(value)
                    "x" -> crossfade = value.toIntOrNull()?.coerceIn(-100, 100) ?: 0
                    "bpm" -> bpm = value.toDoubleOrNull()?.takeIf { it > 0 }
                    "key" -> key = value
                    "room" -> room = value.uppercase()
                }
            }
            return SessionLink(deckA, deckB, cuesA, cuesB, crossfade, bpm, key, room)
        }

        /**
         * Splits "Artist - Title", on the first separator only.
         *
         * Same rule as the playlist parser, for the same reason: "Röyksopp -
         * Eple - Remix" is Röyksopp playing "Eple - Remix".
         */
        internal fun parseTrack(value: String): TrackRef {
            val separator = value.indexOf(" - ")
            if (separator <= 0) return TrackRef(value.trim(), "")
            val artist = value.substring(0, separator).trim()
            val title = value.substring(separator + 3).trim()
            if (artist.isEmpty() || title.isEmpty()) return TrackRef(value.trim(), "")
            return TrackRef(title, artist)
        }

        /**
         * Parses cue slots positionally: an empty segment (`""`, from
         * `"60,,,90"`) is a slot that was never set, kept as `null` rather
         * than dropped — dropping it would shift every slot after it down
         * by one.
         */
        private fun parseCues(value: String): List<Double?> =
            value.split(',').map { it.trim().toDoubleOrNull()?.takeIf { cue -> cue >= 0 } }

        /**
         * Percent-encodes a value, leaving commas alone.
         *
         * A comma is a legal sub-delimiter in a query string, and these links
         * are meant to be read: `ca=4,8` says what it means where `ca=4%2C8`
         * does not. `+` is rewritten to `%20` for the same reason — a literal
         * plus in a title would otherwise come back as a space.
         */
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20")
                .replace("%2C", ",")

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
