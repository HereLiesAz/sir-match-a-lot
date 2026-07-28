package com.hereliesaz.sirmatchalot.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A saved session: the running order, what is on the decks, the pads, and the
 * takes.
 *
 * ## Why a track is not a file path
 *
 * The obvious design writes down where each track came from and reads it back.
 * It fails the first time the file is opened anywhere other than where it was
 * saved, and this app has already been through that: a `content://` URI is a
 * grant to one app on one device, revocable, and pointing at a file the user may
 * move. A session emailed to a friend, or restored after a reinstall, would open
 * to a set of empty decks and no explanation.
 *
 * So a track is written down as **identity plus enough description to find it
 * again**: the library id first, then title, artist, duration and the measured
 * tempo and key. Opening resolves in that order, and anything it cannot resolve
 * is *named* in the result rather than dropped. "Three tracks are missing, here
 * they are" is a session that can be repaired. Silence is not.
 *
 * ## Why it is a zip
 *
 * Because takes are audio. A session with a recorded pad in it and no way to
 * carry the recording is a session that loses the one thing in it that exists
 * nowhere else — every track can be found again, and a take cannot.
 */
@Serializable
data class SessionDocument(
    /**
     * Format version, checked on open.
     *
     * Present from the first release rather than added at the first breaking
     * change, because by then every file in the wild is unversioned.
     */
    val version: Int = FORMAT_VERSION,

    /** What the user called it. */
    val name: String = "Session",

    /** Epoch millis, supplied by the caller so this stays free of the clock. */
    val savedAtMillis: Long = 0L,

    /** App version that wrote it, for diagnosing a file that will not open. */
    val writtenBy: String = "",

    /** The tempo and key everything on the decks was conformed to. */
    val reference: SessionReference? = null,

    /** The planned running order, if one was built. */
    val lineup: List<SessionStep> = emptyList(),

    /** What is on each deck, and where. */
    val deckA: List<SessionClip> = emptyList(),
    val deckB: List<SessionClip> = emptyList(),

    /** Pad assignments. Takes carry [SessionPad.takeFile]; loops carry a source. */
    val pads: List<SessionPad> = emptyList(),

    /** Crossfader position, 0..1, as the mixer holds it. */
    val crossfade: Float = 0.5f,
) {
    /** Every track this session refers to, deduplicated. */
    fun tracks(): List<SessionTrack> =
        (lineup.map { it.track } + deckA.map { it.track } + deckB.map { it.track })
            .distinctBy { it.id }

    fun encode(): String = JSON.encodeToString(this)

    companion object {
        /** Bumped only when an older reader could misread a newer file. */
        const val FORMAT_VERSION = 1

        const val EXTENSION = "sir"

        /** Name of the manifest inside the archive. */
        const val MANIFEST = "session.json"

        /** Directory inside the archive holding recorded takes. */
        const val TAKES = "takes"

        val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Reads a manifest.
         *
         * @return the document, or null when it is not one — unparseable, or
         *   written by a version whose layout this reader would misread. A
         *   confidently wrong session is worse than a refused one.
         */
        fun decode(text: String): SessionDocument? {
            val document = runCatching { JSON.decodeFromString<SessionDocument>(text) }.getOrNull()
                ?: return null
            return document.takeIf { it.version in 1..FORMAT_VERSION }
        }
    }
}

/**
 * A track, written down so it can be found again somewhere else.
 *
 * Every field beyond [id] exists to answer "is this the same song" on a device
 * where the id means nothing. Duration is the strongest of them — titles are
 * edited and artists are spelled several ways, but a file that is 4:07 long is
 * either the same recording or it is not.
 */
@Serializable
data class SessionTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    @SerialName("durationMs") val durationMs: Long = 0L,
    val bpm: Double? = null,
    val camelotKey: String? = null,
    val firstBeatSeconds: Double? = null,
    val downbeatOffset: Int = 0,
    val energyLevel: Int? = null,
    /** Cue points in seconds, so a user's own marks travel with the session. */
    val cuePoints: List<Double> = emptyList(),
    /**
     * The file it came from, as a hint and never as the primary key.
     *
     * Recorded because on the same device it usually still works and saves the
     * user a search. Tried last, so a stale one cannot shadow a real match.
     */
    val sourceHint: String? = null,
)

/** Tempo and key the session was built around. */
@Serializable
data class SessionReference(
    val trackId: String? = null,
    val bpm: Double? = null,
    val camelotKey: String? = null,
)

/**
 * One track on a deck, at the position it was placed.
 *
 * [startSeconds] rather than a frame index: frames are meaningless at a
 * different engine sample rate, and the rate is a setting the user can change
 * between saving and opening.
 */
@Serializable
data class SessionClip(
    val track: SessionTrack,
    val startSeconds: Double = 0.0,
    val loop: Boolean = false,
    val gain: Float = 1f,
)

/** One step of a planned running order, with why it follows the one before. */
@Serializable
data class SessionStep(
    val track: SessionTrack,
    val matchScore: Int? = null,
    val transitionStyle: String? = null,
)

/**
 * One sampler pad.
 *
 * @param takeFile name of a WAV inside the archive's [SessionDocument.TAKES]
 *   directory, for a pad the user recorded. Null for a pad sliced from a track,
 *   which is described by [sourceTrackId] and the times instead — a loop that
 *   can be recut from the source does not need to be carried as audio.
 */
@Serializable
data class SessionPad(
    val index: Int,
    val label: String? = null,
    val loop: Boolean = true,
    val gain: Float = 1f,
    val takeFile: String? = null,
    val sourceTrackId: String? = null,
    val startSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
)

/**
 * What opening a session produced, including what it could not.
 *
 * The missing list is the point. A session that opens with two of its nine
 * tracks silently absent looks like a bug in the app; the same session that says
 * which seven it could not find is a job the user can finish.
 */
data class SessionRestore(
    val document: SessionDocument,
    val resolved: Map<String, String>,
    val missing: List<SessionTrack>,
) {
    val isComplete: Boolean get() = missing.isEmpty()

    /** One sentence about how it went. */
    fun summary(): String = when {
        document.tracks().isEmpty() -> "Opened ${document.name} — it has no tracks in it"
        missing.isEmpty() -> "Opened ${document.name} — all ${resolved.size} tracks found"
        else -> "Opened ${document.name} — ${missing.size} of " +
            "${document.tracks().size} tracks not in your library: " +
            missing.take(3).joinToString { it.title } +
            if (missing.size > 3) " and ${missing.size - 3} more" else ""
    }
}
