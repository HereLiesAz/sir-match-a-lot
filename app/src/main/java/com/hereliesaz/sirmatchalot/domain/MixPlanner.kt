package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import kotlin.math.abs
import kotlin.random.Random

/**
 * A pairing chosen for the two decks.
 *
 * @param match the compatibility assessment that justified the choice, so the UI
 *   can say *why* rather than just presenting two tracks.
 * @param alignment what has to change for them to run in time.
 */
data class CratePick(
    val deckA: Track,
    val deckB: Track,
    val match: MixMatch,
    val alignment: BeatAlignment?,
)

/**
 * One track in a planned mix, with the corrections to apply on the way in.
 *
 * @param transition how this track relates to the one before it; null for the
 *   opening track.
 * @param alignment tempo, phase and pitch corrections relative to the previous
 *   track. Null when either lacks measured values.
 */
data class MixStep(
    val track: Track,
    val transition: MixMatch?,
    val alignment: BeatAlignment?,
)

/**
 * A planned mix.
 *
 * @param steps the running order.
 * @param skipped tracks left out because they have not been analysed. Reported
 *   rather than silently dropped, so "why isn't my track in the mix" has an
 *   answer.
 * @param averageScore mean transition score, as a rough quality figure.
 */
data class MixPlan(
    val steps: List<MixStep>,
    val skipped: List<Track>,
    val averageScore: Int,
)

/**
 * Chooses what to play and in what order, from measured tempo and key.
 *
 * Both features here were requested and neither existed. They are also the
 * clearest example of why the analysis rebuild had to come first: ordering a
 * playlist by harmonic compatibility is meaningless when the keys were assigned
 * by `keys[filenameCharSum % keys.size]`, and matching tempo is meaningless when
 * the BPM was `(90..150).random()`.
 *
 * Pure functions over [Track], so the planning logic is unit-tested without an
 * audio device.
 */
object MixPlanner {

    /** Transitions below this score are not treated as usable pairings. */
    const val MINIMUM_USABLE_SCORE = 55

    /**
     * Picks a pair of tracks to open a session with — the "Shuffle Crate".
     *
     * It has to be both *intelligent* and a *shuffle*: always returning the single
     * best-scoring pair would give the same answer every time. So candidate pairs
     * are filtered to those that actually work, then sampled with probability
     * weighted by score, which keeps it fresh while still favouring good matches.
     *
     * @param tracks the library. Unanalysed tracks are ignored.
     * @param random injected so the selection is deterministic under test.
     * @return null when fewer than two analysed tracks exist, or when no pair
     *   clears [MINIMUM_USABLE_SCORE].
     */
    fun shuffleCrate(
        tracks: List<Track>,
        random: Random = Random.Default,
        minimumScore: Int = MINIMUM_USABLE_SCORE,
    ): CratePick? {
        val usable = tracks.filter { it.bpm != null && it.camelotKey != null }
        if (usable.size < 2) return null

        val candidates = ArrayList<CratePick>()
        for (i in usable.indices) {
            for (j in usable.indices) {
                if (i == j) continue
                val match = HarmonicEngine.compareTracks(usable[i], usable[j])
                if (match.overallScore < minimumScore) continue
                candidates.add(
                    CratePick(
                        deckA = usable[i],
                        deckB = usable[j],
                        match = match,
                        alignment = BeatSync.align(usable[j], usable[i]),
                    ),
                )
            }
        }
        if (candidates.isEmpty()) return null

        // Weight by score above the threshold, so a 95 is far likelier than a 56
        // but the 56 is not impossible.
        val weights = candidates.map { (it.match.overallScore - minimumScore + 1).toDouble() }
        val total = weights.sum()
        var target = random.nextDouble() * total
        for (index in candidates.indices) {
            target -= weights[index]
            if (target <= 0.0) return candidates[index]
        }
        return candidates.last()
    }

    /**
     * Builds a full running order — the "Automatchic Mix".
     *
     * Greedy nearest-neighbour over the compatibility graph: from the current
     * track, take the unplayed track with the best combined score, then repeat.
     * Greedy rather than exhaustive because this is a Hamiltonian-path problem and
     * an exact solution is intractable past a couple of dozen tracks, while a
     * greedy walk over a dense, well-behaved similarity graph gives good
     * transitions throughout.
     *
     * Scoring blends harmonic and tempo compatibility with an **energy
     * preference**: among otherwise similar candidates it favours the one exactly
     * one step up, which is what makes a set build steadily rather than lurch.
     * Note that every track still gets played, so a full plan necessarily comes
     * back down once it has run out of higher-energy material.
     *
     * @param startWith the opening track; defaults to the lowest-energy analysed
     *   track, so there is somewhere to build to.
     * @param energyWeight how strongly to prefer rising energy, 0 disables it.
     */
    fun automatchicMix(
        tracks: List<Track>,
        startWith: Track? = null,
        energyWeight: Double = 12.0,
    ): MixPlan {
        val usable = tracks.filter { it.bpm != null && it.camelotKey != null }
        val skipped = tracks.filter { it.bpm == null || it.camelotKey == null }

        if (usable.isEmpty()) return MixPlan(emptyList(), skipped, 0)

        val opening = startWith?.takeIf { it in usable }
            ?: usable.minByOrNull { it.energyLevel ?: 5 }
            ?: usable.first()

        val remaining = usable.toMutableList().apply { remove(opening) }
        val steps = ArrayList<MixStep>()
        steps.add(MixStep(opening, transition = null, alignment = null))

        var current = opening
        while (remaining.isNotEmpty()) {
            val best = remaining.maxByOrNull { candidate ->
                val match = HarmonicEngine.compareTracks(current, candidate)
                val currentEnergy = current.energyLevel ?: 5
                val candidateEnergy = candidate.energyLevel ?: 5
                // Prefer a *gentle* rise: the ideal next track is one step up.
                // Scoring the raw difference instead rewarded the largest allowed
                // jump, so a set of energies 1..9 was walked as 1,3,5,7,9 and then
                // back down the evens — climbing by skipping, rather than building.
                val energyPenalty = abs(candidateEnergy - currentEnergy - 1)
                match.overallScore - energyPenalty * energyWeight
            } ?: break

            remaining.remove(best)
            steps.add(
                MixStep(
                    track = best,
                    transition = HarmonicEngine.compareTracks(current, best),
                    alignment = BeatSync.align(best, current),
                ),
            )
            current = best
        }

        val scores = steps.mapNotNull { it.transition?.overallScore }
        return MixPlan(
            steps = steps,
            skipped = skipped,
            averageScore = if (scores.isEmpty()) 0 else scores.average().toInt(),
        )
    }

    /**
     * Every track, each carrying its own match against [reference].
     *
     * This exists because a *filtered* list of matches is the wrong shape for
     * choosing what to play. `suggestNext` returns the best five and discards
     * the rest, so a track scoring 54 vanishes rather than appearing with a 54
     * on it — and a DJ who wants that track anyway is told nothing about what it
     * will take to bring it in. Every track keeps its score here, and the score
     * travels *with* the track so the list can be ordered any way at all and the
     * numbers still mean something.
     *
     * @return one entry per track, in the order given. [RankedTrack.match] is
     *   null where there is no reference or where either side is unmeasured —
     *   never a fabricated score.
     */
    fun rank(tracks: List<Track>, reference: Track?): List<RankedTrack> {
        if (reference == null) return tracks.map { RankedTrack(it, null) }
        return tracks.map { track ->
            if (track.id == reference.id) {
                RankedTrack(track, null, isReference = true)
            } else {
                // compareTracks reports isComplete = false rather than guessing
                // when either side lacks a tempo or key, so an unmeasured track
                // is carried with no match instead of a provisional number the
                // UI would have to caveat.
                val match = HarmonicEngine.compareTracks(reference, track)
                RankedTrack(track, match.takeIf { it.isComplete })
            }
        }
    }

    /**
     * [tracks] ordered by how well each mixes after [reference], best first.
     *
     * Ordered by the same number the UI puts on the card. `byHarmonicProximity`
     * sorts by Camelot distance and breaks ties on tempo, which is a *different*
     * ordering from the overall score — so a list sorted by one and labelled
     * with the other reads as broken: 82% above 91% above 78%, for reasons the
     * screen never states. Sorting by the score shown is not a refinement, it is
     * the difference between a ranking and a list of numbers.
     *
     * Tracks with no match sort last, in their original order, rather than being
     * given a position they have not earned.
     */
    fun byMixScore(tracks: List<Track>, reference: Track?): List<RankedTrack> {
        val ranked = rank(tracks, reference)
        if (reference == null) return ranked
        return ranked.sortedWith(
            compareByDescending<RankedTrack> { it.match?.overallScore ?: -1 }
                .thenBy { it.track.title.lowercase() },
        )
    }
}

/**
 * A track with what it would take to mix it after whatever is loaded.
 *
 * The pairing is the point. Scores computed into a separate list have to be
 * matched back up by id at the call site, which is how a library screen ends up
 * showing a sort order derived from compatibility while displaying no
 * compatibility at all.
 *
 * @param match null when nothing is loaded to match against, when this *is* the
 *   reference, or when either track lacks a measured tempo or key. Absent rather
 *   than zero: "not measured" and "will not mix" are different answers and the
 *   UI has to be able to tell them apart.
 * @param isReference true when this is the track everything else is matched
 *   against — the one already setting the session's tempo and key.
 */
data class RankedTrack(
    val track: Track,
    val match: MixMatch?,
    val isReference: Boolean = false,
) {
    /** 0..100, or null when unmeasured. */
    val score: Int? get() = match?.overallScore

    /**
     * The shortest true thing that can be said about mixing this next.
     *
     * Key advice first, because key is what a listener notices when it is wrong;
     * tempo is corrected automatically on load and only worth mentioning when
     * the correction is large enough to hear as a stretch.
     */
    fun advice(): String? {
        val match = match ?: return null
        return when {
            match.isHalfTimeDoubleTime -> "${match.keyAdvice} · half/double time"
            !match.canMixWithNudge -> "${match.keyAdvice} · ${"%.0f".format(match.tempoDiffPercent)}% tempo"
            else -> match.keyAdvice
        }
    }
}
