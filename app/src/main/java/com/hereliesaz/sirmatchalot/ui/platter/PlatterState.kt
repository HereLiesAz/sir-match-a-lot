package com.hereliesaz.sirmatchalot.ui.platter

import com.hereliesaz.sirmatchalot.dsp.EnergyCurve
import com.hereliesaz.sirmatchalot.dsp.PeakEnvelope

/**
 * One clip as the platter needs to draw it.
 *
 * @param startFraction where on the revolution the clip begins.
 * @param spanFraction how much of the revolution it covers; 1 for a lone clip,
 *   which is what makes a single sample circle the whole platter.
 * @param paletteIndex index into [ClipPalette], so colour identifies the song.
 */
data class PlatterClip(
    val id: String,
    val title: String,
    val startFraction: Float,
    val spanFraction: Float,
    val peaks: PeakEnvelope,
    val energy: EnergyCurve?,
    val paletteIndex: Int,
    val selected: Boolean,
)

/**
 * A mark on the ring at a moment in the revolution.
 *
 * Cue points had no visual at all: they could be set and jumped to, but nothing
 * on the platter said where they were, which makes a cue a thing you remember
 * rather than a thing you can see. Structural landmarks ride the same mechanism
 * because they are the same idea — a labelled instant on the circle.
 *
 * @param fraction where on the revolution, 0..1.
 * @param deck which ring it belongs to.
 */
data class PlatterMarker(
    val deck: PlatterGeometry.Deck,
    val fraction: Float,
    val kind: Kind,
    val label: String,
) {
    enum class Kind {
        /** A cue point the performer set. */
        CUE,

        /** The moment a track kicks back in. */
        DROP,

        /** Energy falls away and stays down. */
        BREAKDOWN,

        /** Energy climbing toward something. */
        BUILD,

        /** Something starts singing. */
        VOCAL,
    }
}

/**
 * A clip that has been asked for and is not ready yet.
 *
 * Dropping a track on the platter starts a decode, a rate conversion and then a
 * stretch and a shift — seconds to tens of seconds — and until this existed the
 * platter showed *nothing at all* during it. The ring you dropped onto looked
 * exactly as it had a moment before, so the only available reading was that the
 * drop had missed.
 *
 * A pending clip is drawn on the ring at the point it will land, so the answer to
 * "did that work?" is in the place the question was asked. The app bar's
 * indicator is for work you are not watching; this is for work you are.
 *
 * @param fraction where on the revolution it will start — the point dropped at.
 * @param stage which part of the load is happening now, in the same words the
 *   indicator uses.
 */
data class PendingClip(
    val id: String,
    val deck: PlatterGeometry.Deck,
    val fraction: Float,
    val title: String,
    val stage: String,
)

/**
 * The session's beat grid, as fractions of one revolution.
 *
 * Beats and bars are measured for every track and are what clip placement snaps
 * to — and nothing drew them, so the grid a drop lands on was invisible. A clip
 * would jump slightly on release, correctly, to a line nobody could see.
 *
 * Fractions rather than times, because the platter's whole model is that angle
 * *is* time: converting once here keeps the drawing free of tempo arithmetic and
 * lets the grid be tested without a canvas.
 *
 * @param beats every beat on the revolution.
 * @param downbeats the subset that starts a bar, drawn heavier — a grid of
 *   identical ticks says how fast, but not where the phrase begins.
 */
data class PlatterBeatGrid(
    val beats: List<Float> = emptyList(),
    val downbeats: List<Float> = emptyList(),
) {
    val isEmpty: Boolean get() = beats.isEmpty()

    companion object {
        val NONE = PlatterBeatGrid()

        /**
         * The grid for one revolution of [cycleSeconds] at [bpm].
         *
         * @param firstBeatSeconds the measured first beat, so the lines land on
         *   the music rather than on frame zero.
         * @param downbeatOffset which beat of the bar the first beat is.
         * @param maximumBeats a ceiling: a ten-minute revolution at 174 BPM is
         *   1,740 beats, which at any real screen size is a solid ring rather
         *   than a grid. Past this only bars are drawn, which is the information
         *   that survives at that density anyway.
         */
        fun of(
            bpm: Double?,
            firstBeatSeconds: Double?,
            cycleSeconds: Double,
            beatsPerBar: Int = 4,
            downbeatOffset: Int = 0,
            maximumBeats: Int = 512,
        ): PlatterBeatGrid {
            if (bpm == null || bpm <= 0.0 || cycleSeconds <= 0.0) return NONE
            val beatSeconds = 60.0 / bpm
            if (beatSeconds <= 0.0) return NONE

            val first = firstBeatSeconds ?: 0.0
            val count = ((cycleSeconds - first) / beatSeconds).toInt()
            if (count <= 0) return NONE

            val beats = ArrayList<Float>()
            val downbeats = ArrayList<Float>()
            // Beats are dropped rather than thinned when there are too many:
            // every fourth beat of a too-dense grid is the bar line, which is
            // exactly what the bar list already holds.
            val drawBeats = count <= maximumBeats

            for (index in 0..count) {
                val seconds = first + index * beatSeconds
                if (seconds < 0.0 || seconds > cycleSeconds) continue
                val fraction = (seconds / cycleSeconds).toFloat().coerceIn(0f, 1f)
                if (drawBeats) beats.add(fraction)
                if ((index + downbeatOffset) % beatsPerBar == 0) downbeats.add(fraction)
            }
            // With beats too dense to draw, the bars still are — and they are
            // what the grid is for at that scale.
            return PlatterBeatGrid(
                beats = if (drawBeats) beats else downbeats,
                downbeats = downbeats,
            )
        }
    }
}

/**
 * How well the two decks complement each other, angle by angle.
 *
 * A mix is not one number. Two tracks with a 90% harmonic score still have a
 * breakdown that sits perfectly under the other's build and a chorus that fights
 * it — the whole craft is knowing *which minute* of one goes under *which
 * minute* of the other. A single score printed on a card cannot say that, and it
 * is the platter, not the library, that is looking at both timelines at once.
 *
 * So the waveform's exaggeration is driven from this: the ring grows where the
 * two tracks agree and settles where they do not, and the shape of the
 * agreement is visible around the circle before you commit to a transition.
 *
 * Sampled rather than continuous, because it is redrawn every frame and reading
 * two energy curves per ray is work the render loop does not need to repeat.
 */
data class PlatterAffinity(private val samples: List<Float> = emptyList()) {

    val isEmpty: Boolean get() = samples.isEmpty()

    /** Affinity at [fraction] of the revolution, 0..1. Zero when unknown. */
    fun at(fraction: Float): Float {
        if (samples.isEmpty()) return 0f
        var wrapped = fraction % 1f
        if (wrapped < 0f) wrapped += 1f
        val index = (wrapped * samples.size).toInt().coerceIn(0, samples.size - 1)
        return samples[index]
    }

    companion object {
        val NONE = PlatterAffinity()

        /** How finely the circle is sampled. */
        const val RESOLUTION = 256

        /**
         * Builds the curve from what each deck is doing around the revolution.
         *
         * @param energyA energy on Deck A at a fraction of the revolution, or
         *   null where the deck has nothing there.
         * @param compatibility the tracks' overall harmonic and tempo score,
         *   0..1 — the ceiling. Two tracks in unrelated keys do not become
         *   compatible because a quiet bar lines up with a quiet bar.
         */
        fun of(
            compatibility: Float,
            resolution: Int = RESOLUTION,
            energyA: (Float) -> Float?,
            energyB: (Float) -> Float?,
        ): PlatterAffinity {
            if (compatibility <= 0f || resolution <= 0) return NONE
            var any = false
            val samples = ArrayList<Float>(resolution)

            for (index in 0 until resolution) {
                val fraction = index.toFloat() / resolution
                val a = energyA(fraction)
                val b = energyB(fraction)
                if (a == null || b == null) {
                    // Only one deck is playing here. There is no agreement to
                    // measure, and inventing one would make a solo passage look
                    // like a perfect match.
                    samples.add(0f)
                    continue
                }
                any = true

                // Agreement: both climbing, or both holding back.
                val agreement = 1f - kotlin.math.abs(a - b)
                // Presence: two silences agree perfectly and are not a moment
                // worth marking, so the quieter deck sets a floor on how much
                // that agreement counts.
                val presence = minOf(a, b)
                val local = agreement * (PRESENCE_FLOOR + (1f - PRESENCE_FLOOR) * presence)
                samples.add((compatibility * local).coerceIn(0f, 1f))
            }
            return if (any) PlatterAffinity(samples) else NONE
        }

        /**
         * How much agreement counts where both decks are quiet.
         *
         * Not zero: two tracks breathing together through a breakdown is a real
         * match and one of the better places to bring a transition in.
         */
        private const val PRESENCE_FLOOR = 0.35f
    }
}

/**
 * Everything the platter draws, as one immutable snapshot.
 *
 * Rendering takes this rather than a ViewModel so the Canvas has no dependency
 * on where the data came from, and so a preview or a screenshot test can supply
 * a synthetic platter.
 *
 * @param playheadFraction position on the revolution, 0..1 — derived from the
 *   audio engine's playhead, not from a wall-clock animation.
 * @param outputLevel live metered level, 0..1. Drives waveform size and glow, so
 *   the ring reacts to the music rather than to a free-running oscillator.
 * @param isPlaying whether either deck is actually sounding — running, with
 *   material on it. Not merely whether the transport is marked as running: this
 *   is what decides whether the platter animates, and a deck left playing with
 *   an empty timeline would hold the frame clock open for a still image.
 */
data class PlatterState(
    val deckA: List<PlatterClip> = emptyList(),
    val deckB: List<PlatterClip> = emptyList(),
    val playheadFraction: Float = 0f,
    val outputLevel: Float = 0f,
    val isPlaying: Boolean = false,
    val markers: List<PlatterMarker> = emptyList(),
    /**
     * Band levels off the master bus, for the background light show. Measured
     * from the same signal the speakers get, so the room goes dark when the
     * audio does.
     */
    val bands: SpectrumBands = SpectrumBands.DARK,

    /**
     * Clips that have been asked for and are still being prepared.
     *
     * Drawn on the ring where they will land, so a drop that starts twenty
     * seconds of decoding does not look like a drop that missed.
     */
    val pending: List<PendingClip> = emptyList(),

    /**
     * Beat and bar lines for each deck's revolution.
     *
     * Drawn under the waveform, so the grid that placement snaps to is visible
     * rather than implied by a clip jumping on release.
     */
    val beatGridA: PlatterBeatGrid = PlatterBeatGrid.NONE,
    val beatGridB: PlatterBeatGrid = PlatterBeatGrid.NONE,

    /**
     * How well the decks complement each other around the revolution.
     *
     * Drives how far the rays are exaggerated, so the ring swells where the two
     * tracks agree. The baseline is deliberately lower than it was, to leave the
     * agreement somewhere to grow into.
     */
    val affinity: PlatterAffinity = PlatterAffinity.NONE,
) {
    fun beatGridFor(deck: PlatterGeometry.Deck): PlatterBeatGrid =
        if (deck == PlatterGeometry.Deck.A) beatGridA else beatGridB

    /** True while anything is still being prepared for the platter. */
    val isPreparing: Boolean get() = pending.isNotEmpty()

    fun pendingFor(deck: PlatterGeometry.Deck): List<PendingClip> =
        pending.filter { it.deck == deck }

    val isEmpty: Boolean get() = deckA.isEmpty() && deckB.isEmpty()

    fun clipsFor(deck: PlatterGeometry.Deck): List<PlatterClip> =
        if (deck == PlatterGeometry.Deck.A) deckA else deckB

    fun markersFor(deck: PlatterGeometry.Deck): List<PlatterMarker> =
        markers.filter { it.deck == deck }

    /**
     * The clip covering [fraction] on [deck], or null.
     *
     * Delegates to [PlatterGeometry.clipAt] rather than repeating the wrap
     * arithmetic. It used to repeat it, and differently: this one took the
     * *first* match with an inclusive boundary, that one takes the *last* with
     * an exclusive boundary. So "which clip is under my finger" had two answers
     * that disagreed wherever clips overlap or meet — and the two were called
     * from different halves of the same interaction, tap-to-select through here
     * and drag-a-clip through there.
     *
     * The geometry version wins because it is the one with the tests, and
     * because last-wins matches the draw order: the clip you can see on top is
     * the one you are pointing at.
     */
    fun clipAt(deck: PlatterGeometry.Deck, fraction: Float): PlatterClip? =
        PlatterGeometry.clipAt(clipsFor(deck), fraction)

    companion object {
        /**
         * Lays clips out around the revolution in proportion to their duration.
         *
         * A single clip receives the whole circle. Zero-length input yields an
         * empty layout rather than dividing by zero.
         */
        fun layout(
            clips: List<ClipLayoutInput>,
            selectedIds: Set<String>,
            deck: PlatterGeometry.Deck,
            cycleSeconds: Double,
        ): List<PlatterClip> {
            if (clips.isEmpty()) return emptyList()

            // Angle is time. A clip is drawn where it *is* on the deck's
            // timeline — its own start, divided by one revolution.
            //
            // This used to lay clips out end to end, each taking a share of the
            // circle proportional to its duration, and never looked at a clip's
            // start at all. Every consequence of that was invisible until you
            // tried to move something: dragging a clip changed its start frame
            // in the engine, so the *audio* moved, and the platter redrew it in
            // exactly the same place. Nothing on screen acknowledged the drag.
            // Two clips placed at the same point overlapped in sound and sat
            // side by side on the ring. A clip dropped at nine o'clock appeared
            // wherever the cursor had got to.
            //
            // The engine has always been the truth here — clips at frame
            // offsets on a circular timeline of `cycleFrames` — and the platter
            // was drawing a different picture from the same data.
            val revolution = cycleSeconds.takeIf { it > 0.0 }
                ?: clips.sumOf { it.durationSeconds }.takeIf { it > 0.0 }
                ?: return emptyList()

            return clips.mapIndexed { index, input ->
                PlatterClip(
                    id = input.id,
                    title = input.title,
                    startFraction = wrap((input.startSeconds / revolution).toFloat()),
                    // A clip longer than the revolution covers all of it; the
                    // ring cannot show more than one turn.
                    spanFraction = (input.durationSeconds / revolution).toFloat()
                        .coerceIn(0f, 1f),
                    peaks = input.peaks,
                    energy = input.energy,
                    paletteIndex = index,
                    selected = input.id in selectedIds,
                )
            }
        }

        private fun wrap(fraction: Float): Float {
            var wrapped = fraction % 1f
            if (wrapped < 0f) wrapped += 1f
            return wrapped
        }
    }

    /**
     * What [layout] needs to know about a clip.
     *
     * @param startSeconds where the clip begins on the deck's timeline. The
     *   thing the layout previously did not have and therefore could not draw.
     */
    data class ClipLayoutInput(
        val id: String,
        val title: String,
        val startSeconds: Double,
        val durationSeconds: Double,
        val peaks: PeakEnvelope,
        val energy: EnergyCurve? = null,
    )
}
