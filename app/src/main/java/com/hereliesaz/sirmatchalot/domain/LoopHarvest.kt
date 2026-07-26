package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.dsp.LoopCandidate

/**
 * Which loops, from which songs, go on which pads.
 *
 * The request was *"an automatic loop maker, which samples loops from the active
 * playlist's songs"* — plural. What existed took the best loops from whichever
 * track happened to be on Deck A, which is a loop maker for **a** song.
 *
 * The interesting part of doing it across a playlist is not finding the loops;
 * `StructureFinder` already does that. It is deciding what to keep. Taking the
 * globally highest-scoring candidates fills every pad from one song, because
 * self-similarity scores are a property of the material — a four-on-the-floor
 * house track will out-score an entire folk album, and eight house loops from
 * one track is a worse pad bank than eight loops from eight songs.
 *
 * So allocation is **round-robin by track, best-first within a track**: every
 * song gets its best loop before any song gets its second. That is the rule this
 * file exists to express, and it is pure arithmetic over candidate lists, so it
 * is decided and tested without decoding anything.
 */
object LoopHarvest {

    /**
     * One track's candidates, as the planner needs them.
     *
     * @param trackId the library row, so the caller can find the audio again.
     * @param title what to label a pad taken from it.
     * @param candidates what `StructureFinder` found, in any order.
     */
    data class Source(
        val trackId: String,
        val title: String,
        val candidates: List<LoopCandidate>,
    )

    /**
     * One decided pad assignment.
     *
     * @param padIndex which pad it goes on.
     * @param rankWithinTrack 0 for a track's best loop, 1 for its second, and so
     *   on. Carried so a caller can report *why* a loop was chosen, and so the
     *   round-robin property is assertable without re-deriving it.
     */
    data class Assignment(
        val padIndex: Int,
        val trackId: String,
        val title: String,
        val candidate: LoopCandidate,
        val rankWithinTrack: Int,
    ) {
        /** What to write on the pad. */
        val label: String get() = "$title ${candidate.bars}bar"
    }

    /**
     * Decides what fills [freePads].
     *
     * @param sources one entry per track that yielded candidates. Order is the
     *   playlist's order, and it is preserved: with one pad free and two songs,
     *   the pad goes to the first song, not to whichever scored higher. A
     *   playlist is an ordered thing and the person who ordered it meant it.
     * @param freePads the pad indices available, in the order they should fill.
     * @param minimumScore candidates below this are not worth a pad.
     * @param maxPerTrack ceiling on how many pads one song may take, whatever
     *   the round-robin would otherwise allow.
     * @return assignments, in fill order. Never longer than [freePads].
     */
    fun plan(
        sources: List<Source>,
        freePads: List<Int>,
        minimumScore: Float = 0.5f,
        maxPerTrack: Int = Int.MAX_VALUE,
    ): List<Assignment> {
        if (freePads.isEmpty() || sources.isEmpty() || maxPerTrack <= 0) return emptyList()

        // Best-first within each track, and non-overlapping: two loops that
        // cover the same seconds of the same song are the same loop twice.
        val ranked = sources.map { source ->
            source to nonOverlapping(
                source.candidates
                    .filter { it.score >= minimumScore }
                    .sortedByDescending { it.score },
            )
        }

        val assignments = ArrayList<Assignment>(freePads.size)
        var rank = 0
        while (assignments.size < freePads.size) {
            var placedThisRound = false
            for ((source, candidates) in ranked) {
                if (assignments.size >= freePads.size) break
                if (rank >= maxPerTrack) continue
                val candidate = candidates.getOrNull(rank) ?: continue
                assignments.add(
                    Assignment(
                        padIndex = freePads[assignments.size],
                        trackId = source.trackId,
                        title = source.title,
                        candidate = candidate,
                        rankWithinTrack = rank,
                    ),
                )
                placedThisRound = true
            }
            // Nothing placed means every track is exhausted at this rank and
            // every deeper one; without this the loop never ends.
            if (!placedThisRound) break
            rank++
        }
        return assignments
    }

    /**
     * The order tracks should be decoded in to satisfy [plan].
     *
     * Decoding is the expensive part and a playlist will not fit in memory all
     * at once, so a caller decodes one track, takes everything the plan wants
     * from it, and drops it before the next. This returns each track once, in
     * playlist order, skipping tracks the plan took nothing from.
     */
    fun decodeOrder(assignments: List<Assignment>): List<String> =
        assignments.map { it.trackId }.distinct()

    /**
     * Drops candidates that overlap one already kept.
     *
     * Two loops covering the same seconds are the same loop at two lengths, and
     * a pad bank of the same bar twice is not a pad bank.
     */
    private fun nonOverlapping(sorted: List<LoopCandidate>): List<LoopCandidate> {
        val kept = ArrayList<LoopCandidate>(sorted.size)
        for (candidate in sorted) {
            val clashes = kept.any { existing ->
                candidate.startSeconds < existing.endSeconds &&
                    existing.startSeconds < candidate.endSeconds
            }
            if (!clashes) kept.add(candidate)
        }
        return kept
    }
}
