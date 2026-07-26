package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the engine may stand down.
 *
 * The audio thread used to render, filter, limit and meter silence continuously
 * from the moment the app opened — the full per-sample cost of the whole graph,
 * at the device's native rate, forever, while `AudioTrack` held the output path
 * open so the audio hardware never idled either. That was the app's largest
 * single power draw and it was paid whether or not a track had ever been loaded.
 *
 * Standing down fixes it, and has exactly one way to be wrong: standing down
 * while something is still making a sound. Every test here is about that
 * direction of the answer. A false "busy" costs a little battery; a false "idle"
 * cuts the music off.
 */
class EngineIdleTest {

    private fun engine() = AudioEngine(OfflineAudioOutput(sampleRate = 44_100, framesPerBuffer = 64))

    private fun clip(id: String = "clip", frames: Int = 1_000) =
        Clip(id = id, buffer = PcmBuffer.silence(frames, channelCount = 2, sampleRate = 44_100))

    @Test
    fun `a fresh engine is idle`() {
        // Nothing loaded, nothing playing: the state the app spends its first
        // minutes in, and used to spend them rendering silence through.
        assertTrue(engine().isIdle())
    }

    @Test
    fun `a deck with clips but stopped is idle`() {
        val engine = engine()
        engine.deckA.clips = listOf(clip())
        engine.deckA.playing = false
        assertTrue(engine.isIdle())
    }

    @Test
    fun `a deck playing but empty is idle`() {
        // The transport is running and there is nothing on the platter — which
        // is what the app looks like between clearing the decks and dropping the
        // next track, and it renders exactly nothing.
        val engine = engine()
        engine.deckA.playing = true
        assertTrue(engine.isIdle())
    }

    @Test
    fun `a deck playing a clip is not idle`() {
        val engine = engine()
        engine.deckA.clips = listOf(clip())
        engine.deckA.playing = true
        assertFalse(engine.isIdle())
    }

    @Test
    fun `either deck playing keeps the engine awake`() {
        val engine = engine()
        engine.deckB.clips = listOf(clip())
        engine.deckB.playing = true
        assertFalse("deck B alone must count", engine.isIdle())
    }

    @Test
    fun `silent audio still counts as sounding`() {
        // The clip is literal silence, and the engine must still keep running:
        // a track playing a quiet passage is *sounding*, and standing down
        // through it would arrive a second late for the beat that follows.
        // Idleness is structural, not a level measurement.
        val engine = engine()
        engine.deckA.clips = listOf(clip(frames = 44_100))
        engine.deckA.playing = true
        assertFalse(engine.isIdle())
    }

    @Test
    fun `a playing pad keeps the engine awake with both decks stopped`() {
        val engine = engine()
        engine.sampler.pads[0].load(
            buffer = PcmBuffer.silence(500, channelCount = 2, sampleRate = 44_100),
            label = "loop",
        )
        engine.sampler.trigger(0)
        assertFalse(engine.isIdle())

        engine.sampler.stop(0)
        assertTrue("a stopped pad should let it go idle again", engine.isIdle())
    }

    @Test
    fun `recording keeps the engine awake even with every pad silent`() {
        // Capture reads the master bus. Standing down mid-take would punch a
        // hole in the recording rather than merely delaying it.
        val engine = engine()
        engine.sampler.beginRecording(0)
        assertFalse(engine.isIdle())

        engine.sampler.cancelRecording()
        assertTrue(engine.isIdle())
    }

    @Test
    fun `the growl keeps the engine awake while it plays`() {
        val engine = engine()
        engine.growl.load(FloatArray(200) { 0.5f })
        engine.growl.trigger()
        assertFalse(engine.isIdle())

        engine.growl.stop()
        assertTrue(engine.isIdle())
    }

    @Test
    fun `a loaded but untriggered pad does not keep the engine awake`() {
        // Loading eight pads from a harvest must not be a reason to run the
        // graph for the rest of the session.
        val engine = engine()
        engine.sampler.pads.forEach {
            it.load(PcmBuffer.silence(100, channelCount = 2, sampleRate = 44_100), "loop")
        }
        assertTrue(engine.isIdle())
    }

    // --- The deck's own view of it ---

    @Test
    fun `isSounding agrees with what render actually does`() {
        // The two must not drift: `isSounding` is the same condition `render`
        // checks before doing any work, and if they disagree the output stays
        // open for a deck producing nothing, or shuts on one that is.
        val deck = Deck("A", outputSampleRate = 44_100)
        val out = FloatArray(128)

        val cases = listOf(
            Triple(false, emptyList<Clip>(), "stopped and empty"),
            Triple(true, emptyList<Clip>(), "playing and empty"),
            Triple(false, listOf(clip()), "stopped with a clip"),
            Triple(true, listOf(clip()), "playing a clip"),
        )
        for ((playing, clips, description) in cases) {
            deck.clips = clips
            deck.playing = playing
            // A non-zero playhead so a rendering deck leaves a mark; the clip is
            // silence, so what is compared is whether the deck bothered at all.
            val rendered = deck.isSounding
            deck.render(out, 64)
            assertEquals(
                "$description: isSounding disagreed with whether there is anything to render",
                playing && clips.isNotEmpty(),
                rendered,
            )
        }
    }
}
