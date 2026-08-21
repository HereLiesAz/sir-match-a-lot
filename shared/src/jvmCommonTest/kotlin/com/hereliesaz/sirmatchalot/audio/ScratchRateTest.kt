package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A scratch is deliberately a whole-mix "smart scratch" — both decks move
 * through the same shaped rate curve while the gesture is active — but
 * ending it has to hand each deck *back* the rate it actually had before,
 * not force both decks to whichever one the curve was computed from.
 */
class ScratchRateTest {

    private fun engine() = AudioEngine(OfflineAudioOutput(sampleRate = 44_100, framesPerBuffer = 64))

    @Test
    fun `ending a scratch restores each deck to its own resting rate`() {
        val engine = engine()
        // Deck B beat-matched to a different tempo than deck A before any
        // scratch begins — the ordinary state of a mix mid-transition.
        engine.deckA.rate = 1.0
        engine.deckB.rate = 1.048

        engine.beginScratch()
        engine.updateScratch(250f)
        engine.updateScratch(-400f)
        engine.endScratch()

        assertEquals(1.0, engine.deckA.rate, 1e-9)
        assertEquals(
            "deck B's beat-matched rate must survive a scratch, not reset to deck A's",
            1.048,
            engine.deckB.rate,
            1e-9,
        )
    }

    @Test
    fun `both decks move together while a scratch is active`() {
        val engine = engine()
        engine.deckA.rate = 1.0
        engine.deckB.rate = 1.048

        engine.beginScratch()
        engine.updateScratch(300f)

        assertEquals(
            "both decks share the one scratch curve while the gesture is active",
            engine.deckA.rate,
            engine.deckB.rate,
            1e-9,
        )
    }
}
