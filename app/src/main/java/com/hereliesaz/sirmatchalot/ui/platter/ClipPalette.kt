package com.hereliesaz.sirmatchalot.ui.platter

/**
 * Assigns each clip on the platter its own colour.
 *
 * In the rainbow reference each distinct colour is a **different track**, so the
 * ring is readable as "which song am I looking at" at a glance. Hue therefore
 * carries identity, and the measured energy curve modulates that hue's
 * brightness along the ring — which reconciles the earlier request for waveforms
 * "colour coded for energy" with per-song colouring, rather than making them
 * compete for the same channel.
 *
 * Hues are spaced by the golden angle so that any number of clips stays
 * distinguishable. A fixed palette of N colours repeats once there are more than
 * N clips, putting two identically-coloured tracks next to each other; stepping
 * by an irrational fraction of the circle never does.
 *
 * Returned as HSL components so the caller can build a platform colour without
 * this file depending on Compose — which is what keeps it unit-testable.
 */
object ClipPalette {

    /** Golden angle in degrees; successive hues never coincide. */
    private const val GOLDEN_ANGLE = 137.507764f

    /** Deck A starts at cyan, Deck B at amber, so the two rings read apart. */
    private const val DECK_A_START_HUE = 185f
    private const val DECK_B_START_HUE = 38f

    /**
     * Hue in degrees, 0..360, for the clip at [index] on [deck].
     */
    fun hueFor(deck: PlatterGeometry.Deck, index: Int): Float {
        val start = if (deck == PlatterGeometry.Deck.A) DECK_A_START_HUE else DECK_B_START_HUE
        var hue = (start + index * GOLDEN_ANGLE) % 360f
        if (hue < 0f) hue += 360f
        return hue
    }

    /**
     * Lightness, 0..1, for a point on the ring.
     *
     * Energy raises it, so busy passages read brighter without changing which
     * track a colour identifies. Bounded away from black and white so the hue
     * remains legible at both extremes.
     *
     * @param energy measured energy at this point, 0..1.
     * @param selected whether the clip is currently targeted by gestures.
     */
    fun lightnessFor(energy: Float, selected: Boolean = false): Float {
        val base = 0.42f + energy.coerceIn(0f, 1f) * 0.33f
        return if (selected) (base + 0.18f).coerceAtMost(0.95f) else base
    }

    /**
     * Saturation, 0..1. Selected clips desaturate toward white so the targeted
     * waveform stands out without losing its identifying hue entirely.
     */
    fun saturationFor(selected: Boolean = false): Float = if (selected) 0.35f else 0.95f
}
