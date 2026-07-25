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
     * Orders [tracks] by Camelot proximity to [reference] — the library's
     * harmonic filter.
     *
     * Ties on key distance are broken by tempo closeness, so the top of the list
     * is genuinely the easiest to mix next. Unanalysed tracks sort last rather
     * than being given a fabricated position.
     */
    fun byHarmonicProximity(tracks: List<Track>, reference: Track?): List<Track> {
        val referenceKey = reference?.camelotKey
        val referenceBpm = reference?.bpm
        if (referenceKey == null) return tracks

        return tracks.sortedWith(
            compareBy(
                { track -> track.camelotKey?.let { HarmonicEngine.getCamelotDistance(it, referenceKey) } ?: 999 },
                { track ->
                    val bpm = track.bpm
                    if (bpm == null || referenceBpm == null) Double.MAX_VALUE
                    else abs(Math.log(bpm / referenceBpm))
                },
                { it.title },
            ),
        )
    }

    /**
     * The tracks that mix best after [reference], best first.
     *
     * @param limit how many to return.
     */
    fun suggestNext(tracks: List<Track>, reference: Track, limit: Int = 5): List<MixMatch> =
        tracks
            .asSequence()
            .filter { it.id != reference.id && it.bpm != null && it.camelotKey != null }
            .map { HarmonicEngine.compareTracks(reference, it) }
            .filter { it.overallScore >= MINIMUM_USABLE_SCORE }
            .sortedByDescending { it.overallScore }
            .take(limit)
            .toList()
}
