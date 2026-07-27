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
) {
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
     * Used by tap-to-select, so the tap hits whatever waveform is actually at
     * that angle rather than an index computed from an even division.
     */
    fun clipAt(deck: PlatterGeometry.Deck, fraction: Float): PlatterClip? =
        clipsFor(deck).firstOrNull { clip ->
            var offset = (fraction - clip.startFraction) % 1f
            if (offset < 0f) offset += 1f
            offset <= clip.spanFraction
        }

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
        ): List<PlatterClip> {
            if (clips.isEmpty()) return emptyList()
            val total = clips.sumOf { it.durationSeconds }
            if (total <= 0.0) return emptyList()

            var cursor = 0f
            return clips.mapIndexed { index, input ->
                val span = (input.durationSeconds / total).toFloat()
                val clip = PlatterClip(
                    id = input.id,
                    title = input.title,
                    startFraction = cursor,
                    spanFraction = span,
                    peaks = input.peaks,
                    energy = input.energy,
                    paletteIndex = index,
                    selected = input.id in selectedIds,
                )
                cursor += span
                clip
            }
        }
    }

    /** What [layout] needs to know about a clip. */
    data class ClipLayoutInput(
        val id: String,
        val title: String,
        val durationSeconds: Double,
        val peaks: PeakEnvelope,
        val energy: EnergyCurve? = null,
    )
}
