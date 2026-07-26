package com.hereliesaz.sirmatchalot.domain

/**
 * How much material a deck's circle may hold, and what to drop when it is full.
 *
 * The platter is one revolution, and a revolution is a fixed number of beats.
 * Without a bound the circle grows every time a track is added, so each clip
 * occupies a smaller and smaller arc until nothing on it can be aimed at with a
 * finger — and every clip is a fully decoded track in memory, so an unbounded
 * circle is also an unbounded heap.
 *
 * The bound is expressed in **beats**, not in songs, because that is what the
 * circle actually measures. Three songs is what it is sized for; a deck holding
 * one long track and two short ones should not be told it is full.
 *
 * Pure arithmetic over clip descriptions, so the eviction policy is tested
 * without an audio engine.
 */
object DeckCapacity {

    /**
     * Enough for roughly three songs.
     *
     * A four-minute track at 128 BPM is 512 beats, so three of them is about
     * 1536. The figure is generous rather than exact — the point is a ceiling,
     * not a quota.
     */
    const val DEFAULT_MAX_BEATS = 1_600

    /**
     * What the deck knows about a clip for capacity purposes.
     *
     * @param id the clip.
     * @param beats how long it is, in beats of the deck's grid.
     * @param addedOrder lower is older; ties broken by this so eviction is
     *   deterministic.
     */
    data class Entry(val id: String, val beats: Int, val addedOrder: Int)

    /**
     * Which clips to remove so that [incoming] fits within [maxBeats].
     *
     * Oldest first, because a deck is a queue in practice: the track that has
     * been on longest is the one already mixed out of. [protectedIds] are never
     * dropped — the clip currently sounding under the playhead, above all, since
     * silencing what is playing to make room is never what was wanted.
     *
     * @return ids to evict, in the order they should go. Empty when it fits.
     */
    fun evictionsFor(
        existing: List<Entry>,
        incoming: Int,
        maxBeats: Int = DEFAULT_MAX_BEATS,
        protectedIds: Set<String> = emptySet(),
    ): List<String> {
        if (incoming <= 0) return emptyList()
        var total = existing.sumOf { it.beats } + incoming
        if (total <= maxBeats) return emptyList()

        val evictions = ArrayList<String>()
        val candidates = existing
            .filterNot { it.id in protectedIds }
            .sortedBy { it.addedOrder }

        for (candidate in candidates) {
            if (total <= maxBeats) break
            evictions.add(candidate.id)
            total -= candidate.beats
        }
        return evictions
    }

    /**
     * True when [incoming] cannot be made to fit even after evicting everything
     * evictable — a single clip longer than the whole circle.
     *
     * Reported rather than silently truncated, because a track that will not fit
     * is a thing the user needs to know about, not something to quietly refuse.
     */
    fun cannotFit(
        existing: List<Entry>,
        incoming: Int,
        maxBeats: Int = DEFAULT_MAX_BEATS,
        protectedIds: Set<String> = emptySet(),
    ): Boolean {
        val protectedBeats = existing.filter { it.id in protectedIds }.sumOf { it.beats }
        return protectedBeats + incoming > maxBeats
    }
}
