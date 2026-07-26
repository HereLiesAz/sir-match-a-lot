package com.hereliesaz.sirmatchalot.data

/**
 * What an "analyse" press should actually do, and what to say about it.
 *
 * Pressing analyse used to be silent in every case but one. It iterated the
 * pending tracks and reported only from inside a *successful* per-track
 * analysis, so an empty library, a fully analysed library, and a library of
 * entries with no audio file all looked identical to a dead button — which is
 * exactly how it was reported.
 *
 * Splitting the decision out here makes each of those outcomes a distinct,
 * testable value rather than a branch that falls through to nothing.
 */
object AnalysisQueue {

    /**
     * @param toAnalyse tracks that have audio and need measuring.
     * @param missingAudio tracks that have never been linked to a file — a
     *   playlist entry naming a song the library does not hold, typically.
     *   Reported rather than skipped in silence.
     * @param alreadyAnalysed tracks measured by the current analyser.
     */
    data class Plan(
        val toAnalyse: List<Track>,
        val missingAudio: List<Track>,
        val alreadyAnalysed: List<Track>,
    ) {
        val hasWork: Boolean get() = toAnalyse.isNotEmpty()

        val total: Int get() = toAnalyse.size + missingAudio.size + alreadyAnalysed.size

        /**
         * What to tell the user when [hasWork] is false — never an empty string,
         * because the whole failure being fixed is a button that says nothing.
         */
        fun idleMessage(): String = when {
            total == 0 ->
                "Library is empty — import a file or a playlist first"
            missingAudio.isNotEmpty() && alreadyAnalysed.isEmpty() ->
                "${missingAudio.size} ${trackWord(missingAudio.size)} have no audio file yet — " +
                    "link them to a file to analyse them"
            missingAudio.isNotEmpty() ->
                "Everything with audio is analysed; ${missingAudio.size} " +
                    "${trackWord(missingAudio.size)} still have no file"
            else ->
                "All ${alreadyAnalysed.size} ${trackWord(alreadyAnalysed.size)} are already analysed"
        }

        /** What to say when starting work. */
        fun startMessage(): String = buildString {
            append("Analysing ${toAnalyse.size} ${trackWord(toAnalyse.size)}...")
            if (missingAudio.isNotEmpty()) {
                append(" (${missingAudio.size} skipped for want of a file)")
            }
        }

        private fun trackWord(count: Int) = if (count == 1) "track" else "tracks"
    }

    /**
     * Sorts [tracks] into what can be analysed, what cannot, and what already has
     * been.
     *
     * A track counts as analysable only if it has a source to decode. Version 2
     * of the schema had no way to express "no audio", so this distinction did not
     * exist and the case fell through the old code silently.
     */
    fun plan(tracks: List<Track>): Plan {
        val toAnalyse = ArrayList<Track>()
        val missingAudio = ArrayList<Track>()
        val alreadyAnalysed = ArrayList<Track>()

        for (track in tracks) when {
            track.sourceUri.isNullOrBlank() -> missingAudio.add(track)
            track.isAnalysed -> alreadyAnalysed.add(track)
            else -> toAnalyse.add(track)
        }
        return Plan(toAnalyse, missingAudio, alreadyAnalysed)
    }

    /**
     * Re-analyse everything that has audio, regardless of whether it has been
     * measured before — what a "re-analyse all" action needs.
     */
    fun planFullRescan(tracks: List<Track>): Plan {
        val toAnalyse = tracks.filter { !it.sourceUri.isNullOrBlank() }
        val missingAudio = tracks.filter { it.sourceUri.isNullOrBlank() }
        return Plan(toAnalyse, missingAudio, alreadyAnalysed = emptyList())
    }
}
