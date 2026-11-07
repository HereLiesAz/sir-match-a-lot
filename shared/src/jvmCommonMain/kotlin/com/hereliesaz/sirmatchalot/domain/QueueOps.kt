package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import kotlin.random.Random

/**
 * Editing a play queue: the operations Spotify and YouTube Music put on "Up next".
 *
 * Plain functions over an ordered list of tracks, each returning a new list, so
 * the screen and the ViewModel share one set of rules and the rules are tested
 * without either. Out-of-range indices are ignored rather than thrown on: they
 * come from a UI that was drawn a frame ago.
 */
object QueueOps {

    /** [order] with the track at [from] moved to position [to]. */
    fun moved(order: List<Track>, from: Int, to: Int): List<Track> {
        if (from !in order.indices || to !in order.indices || from == to) return order
        val list = order.toMutableList()
        list.add(to, list.removeAt(from))
        return list
    }

    /** [order] without the track at [index]. */
    fun removed(order: List<Track>, index: Int): List<Track> =
        if (index in order.indices) order.filterIndexed { i, _ -> i != index } else order

    /**
     * [order] with [track] added at [index], or at the end past it. A track
     * already queued is moved there rather than duplicated.
     */
    fun inserted(order: List<Track>, track: Track, index: Int = order.size): List<Track> {
        val without = order.filter { it.id != track.id }
        val at = index.coerceIn(0, without.size)
        return without.take(at) + track + without.drop(at)
    }

    /** [order] with [track] straight after position [current] — "play next". */
    fun playNext(order: List<Track>, track: Track, current: Int): List<Track> {
        val currentId = order.getOrNull(current)?.id
        val without = order.filter { it.id != track.id }
        val after = if (currentId == null) -1 else without.indexOfFirst { it.id == currentId }
        return inserted(without, track, after + 1)
    }

    /**
     * The queue with everything after position [current] shuffled; what has been
     * played and what is playing stay where they are.
     */
    fun shuffledAfter(order: List<Track>, current: Int, random: Random = Random.Default): List<Track> {
        val keep = (current + 1).coerceIn(0, order.size)
        return order.take(keep) + order.drop(keep).shuffled(random)
    }

    /**
     * A queue that keeps going from [seed]: the planner's best next track, then
     * the one after, for up to [length] tracks.
     */
    fun radio(seed: Track, library: List<Track>, length: Int = DEFAULT_RADIO_LENGTH): List<Track> {
        val plan = MixPlanner.automatchicMix(library, startWith = seed)
        val tracks = plan.steps.map { it.track }
        // A seed with no measured tempo or key cannot be planned from; it is
        // still what was asked for, so it leads and the rest follow it.
        return if (tracks.firstOrNull()?.id == seed.id) tracks.take(length)
        else (listOf(seed) + tracks.filter { it.id != seed.id }).take(length)
    }

    const val DEFAULT_RADIO_LENGTH = 25
}
