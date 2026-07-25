package com.hereliesaz.sirmatchalot.ui.platter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class ClipPaletteTest {

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return min(d, 360f - d)
    }

    @Test
    fun `each clip gets its own hue`() {
        // Colour identifies the song, so two clips must never share a hue.
        val hues = (0 until 12).map { ClipPalette.hueFor(PlatterGeometry.Deck.A, it) }
        for (i in hues.indices) {
            for (j in i + 1 until hues.size) {
                assertTrue(
                    "clips $i and $j share a hue (${hues[i]} vs ${hues[j]})",
                    hueDistance(hues[i], hues[j]) > 5f,
                )
            }
        }
    }

    @Test
    fun `hues stay distinguishable well past a fixed palette size`() {
        // A palette of N colours would repeat at index N, putting two identically
        // coloured tracks side by side. Golden-angle stepping must not.
        val hues = (0 until 24).map { ClipPalette.hueFor(PlatterGeometry.Deck.A, it) }
        for (i in hues.indices) {
            for (j in i + 1 until hues.size) {
                assertTrue(
                    "clips $i and $j collide at 24 clips",
                    hueDistance(hues[i], hues[j]) > 2f,
                )
            }
        }
    }

    @Test
    fun `hues are always valid degrees`() {
        for (index in 0 until 200) {
            for (deck in PlatterGeometry.Deck.entries) {
                val hue = ClipPalette.hueFor(deck, index)
                assertTrue("hue $hue out of range at $index", hue in 0f..360f)
            }
        }
    }

    @Test
    fun `hue is stable for the same clip`() {
        assertEquals(
            ClipPalette.hueFor(PlatterGeometry.Deck.A, 3),
            ClipPalette.hueFor(PlatterGeometry.Deck.A, 3),
            0f,
        )
    }

    @Test
    fun `the two decks start from different hues so the rings read apart`() {
        assertTrue(
            "deck A and B should not open on the same colour",
            hueDistance(
                ClipPalette.hueFor(PlatterGeometry.Deck.A, 0),
                ClipPalette.hueFor(PlatterGeometry.Deck.B, 0),
            ) > 60f,
        )
    }

    @Test
    fun `energy raises lightness without touching hue`() {
        val quiet = ClipPalette.lightnessFor(0f)
        val loud = ClipPalette.lightnessFor(1f)
        assertTrue("energy should brighten: $quiet vs $loud", loud > quiet)
        // Hue is independent of energy — identity and energy use separate channels.
        assertEquals(
            ClipPalette.hueFor(PlatterGeometry.Deck.A, 2),
            ClipPalette.hueFor(PlatterGeometry.Deck.A, 2),
            0f,
        )
    }

    @Test
    fun `lightness stays legible at both extremes`() {
        for (energy in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val lightness = ClipPalette.lightnessFor(energy)
            assertTrue("$lightness too dark at energy $energy", lightness > 0.3f)
            assertTrue("$lightness too washed out at energy $energy", lightness < 0.9f)
        }
    }

    @Test
    fun `out of range energy is clamped`() {
        assertEquals(ClipPalette.lightnessFor(0f), ClipPalette.lightnessFor(-1f), 0f)
        assertEquals(ClipPalette.lightnessFor(1f), ClipPalette.lightnessFor(5f), 0f)
    }

    @Test
    fun `a selected clip is brighter and less saturated but keeps its hue`() {
        assertTrue(ClipPalette.lightnessFor(0.5f, selected = true) > ClipPalette.lightnessFor(0.5f))
        assertTrue(ClipPalette.saturationFor(selected = true) < ClipPalette.saturationFor())
        assertEquals(
            ClipPalette.hueFor(PlatterGeometry.Deck.A, 1),
            ClipPalette.hueFor(PlatterGeometry.Deck.A, 1),
            0f,
        )
    }

    @Test
    fun `selected lightness never saturates to pure white`() {
        assertTrue(ClipPalette.lightnessFor(1f, selected = true) <= 0.95f)
    }
}
