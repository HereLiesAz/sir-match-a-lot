package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.dsp.BeatGrid

/**
 * The one way to get a [BeatGrid] out of a [Track].
 *
 * Six places used to assemble one out of the same three columns by hand, and
 * that duplication is not a tidiness complaint — it is what let this ship:
 *
 * ```kotlin
 * BeatGrid(bpm, first, track.downbeatOffset)   // third parameter is beatsPerBar
 * ```
 *
 * A downbeat on the first beat — the column default, and a quarter of all
 * measured tracks — became `beatsPerBar = 0` and threw out of a coroutine that
 * only caught `OutOfMemoryError`, so opening an analysed track killed the app. An
 * offset of 1, 2 or 3 was worse in the way that matters: it built a grid in 1/4,
 * 2/4 or 3/4, no exception, and every landmark
 * [com.hereliesaz.sirmatchalot.dsp.StructureFinder] snapped to a bar line was
 * snapped to a bar line that is not in the music.
 *
 * One conversion means one place that can be wrong, and it has a test.
 *
 * ## Why this coerces where the constructor requires
 *
 * `BeatGrid` keeps its `require`s. They are real invariants and they did their
 * job — a loud crash is exactly the right answer to a programmer passing a
 * time signature where a beat index belongs.
 *
 * But a value read back out of Room is not a programmer error, it is data, and
 * data can be old, or written by a version that counted differently. The whole
 * reason the analysis columns are nullable is that "not measured" has to be
 * expressible; a row that survived a migration with an offset of 9 in it should
 * cost that track its downbeat phase, not cost the user their session. So this
 * sanitises on the way in, and is the only path stored values take.
 */
fun Track.beatGrid(beatsPerBar: Int = BeatGrid.DEFAULT_BEATS_PER_BAR): BeatGrid? {
    val tempo = bpm ?: return null
    if (tempo <= 0.0) return null
    return BeatGrid(
        bpm = tempo,
        firstBeatSeconds = firstBeatSeconds ?: 0.0,
        beatsPerBar = beatsPerBar,
        downbeatOffset = BeatGrid.sanitiseDownbeat(downbeatOffset, beatsPerBar),
    )
}

/**
 * The grid, or null when the tempo *or the phase* is missing.
 *
 * [beatGrid] falls back to a phase of zero, because most callers want a grid
 * whose bars are the right length even if it cannot say where bar one is.
 * Callers that are placing something on a specific bar need both, and should say
 * so rather than silently laying their bar lines on frame zero of the file.
 */
fun Track.measuredBeatGrid(): BeatGrid? {
    if (firstBeatSeconds == null) return null
    return beatGrid()
}
