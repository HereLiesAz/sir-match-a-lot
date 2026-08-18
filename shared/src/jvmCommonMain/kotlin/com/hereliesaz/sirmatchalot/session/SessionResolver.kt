package com.hereliesaz.sirmatchalot.session

import com.hereliesaz.sirmatchalot.data.Track
import kotlin.math.abs

/**
 * Finds the tracks a saved session refers to in whatever library is here now.
 *
 * A session opened on the device that wrote it matches on id and this is
 * uninteresting. The case worth designing for is the other one — a reinstall, a
 * second device, a file someone was sent — where the ids mean nothing and the
 * question becomes "is this the same song".
 *
 * Four passes, most certain first, because a wrong match is worse than a miss:
 * a missing track is reported and can be fixed, while a session that quietly
 * loads the wrong recording is a mix that goes out sounding wrong for reasons
 * nobody can see.
 *
 * 1. **The same id.** The same library; nothing to think about.
 * 2. **Title, artist and duration.** Duration to a second, which is the strongest
 *    evidence available — titles get edited and artists get spelled several ways,
 *    but a recording is the length it is.
 * 3. **Title and duration.** Same recording, credited differently. Common enough
 *    with remixes and compilations to be worth having.
 * 4. **The source hint.** Only if nothing else matched, because a path that has
 *    been reused for a different file would otherwise shadow a real match.
 *
 * Deliberately no fuzzy title matching. "Closest string" would find something for
 * every track including the ones that genuinely are not here, which converts a
 * reportable miss into a silent substitution.
 */
object SessionResolver {

    /** Durations within this of each other are the same recording. */
    const val DURATION_TOLERANCE_MS = 1_000L

    /**
     * Matches [document]'s tracks against [library].
     *
     * @return the resolution, including everything that could not be found. Each
     *   library track is used at most once, so two saved tracks cannot both
     *   resolve to the same file and produce a session that plays it twice.
     */
    fun resolve(document: SessionDocument, library: List<Track>): SessionRestore {
        val wanted = document.tracks()
        val available = library.toMutableList()
        val resolved = LinkedHashMap<String, String>()

        // Each pass runs to completion across *every* saved track before the
        // next, weaker pass runs at all — "most certain first" only holds
        // globally if it is applied globally. Running all four passes for
        // one saved track before moving to the next let an early track's
        // weak match (pass 4) claim a library row that a later track's exact
        // id (pass 1) needed, and reported the later, better-evidenced track
        // as missing instead.
        var remaining = wanted
        val passes: List<(Track, SessionTrack) -> Boolean> = listOf(
            { track, saved -> track.id == saved.id },
            { track, saved -> same(track, saved) && sameArtist(track, saved) },
            { track, saved -> same(track, saved) },
            ::hinted,
        )
        for (pass in passes) {
            if (remaining.isEmpty()) break
            val stillMissing = ArrayList<SessionTrack>()
            for (saved in remaining) {
                val match = take(available) { pass(it, saved) }
                if (match == null) stillMissing.add(saved) else resolved[saved.id] = match.id
            }
            remaining = stillMissing
        }

        return SessionRestore(document, resolved, remaining)
    }

    /** Removes and returns the first match, so nothing is claimed twice. */
    private fun take(from: MutableList<Track>, predicate: (Track) -> Boolean): Track? {
        val index = from.indexOfFirst(predicate)
        if (index < 0) return null
        return from.removeAt(index)
    }

    private fun same(track: Track, saved: SessionTrack): Boolean =
        track.title.equals(saved.title, ignoreCase = true) && sameLength(track, saved)

    private fun sameArtist(track: Track, saved: SessionTrack): Boolean =
        track.artist.equals(saved.artist, ignoreCase = true)

    /**
     * Length is only evidence when both sides have one.
     *
     * A track that has never been decoded has a duration of zero, and treating
     * that as "0 ms, close enough to nothing" would match it to any other
     * unmeasured track in the library.
     */
    private fun sameLength(track: Track, saved: SessionTrack): Boolean {
        if (track.durationMs <= 0L || saved.durationMs <= 0L) return false
        return abs(track.durationMs - saved.durationMs) <= DURATION_TOLERANCE_MS
    }

    /**
     * The saved path, trusted only where it is not contradicted.
     *
     * A path is the weakest evidence here and the only kind that goes stale
     * without anything changing in the session: files get replaced in place, and
     * a library re-scan hands the same URI to whatever is there now. So when both
     * sides know their length and the lengths disagree, the file at that path is
     * a different recording and the hint is simply out of date — matching on it
     * anyway is how a set ends up playing the seven-minute extended mix where the
     * radio edit was planned.
     */
    private fun hinted(track: Track, saved: SessionTrack): Boolean {
        val hint = saved.sourceHint ?: return false
        if (track.sourceUri != hint) return false
        val bothMeasured = track.durationMs > 0L && saved.durationMs > 0L
        return !bothMeasured || sameLength(track, saved)
    }
}

/** How a [Track] is written into a session. */
fun Track.toSessionTrack(): SessionTrack = SessionTrack(
    id = id,
    title = title,
    artist = artist,
    durationMs = durationMs,
    bpm = bpm,
    camelotKey = camelotKey,
    firstBeatSeconds = firstBeatSeconds,
    downbeatOffset = downbeatOffset,
    energyLevel = energyLevel,
    cuePoints = cuePoints,
    sourceHint = sourceUri,
)
