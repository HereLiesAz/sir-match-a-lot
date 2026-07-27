package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.dsp.LoopCandidate
import com.hereliesaz.sirmatchalot.dsp.PointOfInterest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Choosing how one record hands over to the next.
 *
 * Two properties matter more than any particular choice, and both are asserted
 * here repeatedly:
 *
 * 1. **A style is never chosen without the material for it.** A loop roll with
 *    no loop is four bars of silence; a reverse slam into a quiet intro is a
 *    mistake rather than a move. Variety that ignores the music is no better
 *    than the fixed sixteen-bar blend it replaced, it just fails differently.
 * 2. **The same seed gives the same set.** Everything here draws on an injected
 *    [Random] so a mix is different each night and a test is not.
 */
class TransitionChoreographerTest {

    private val choreographer = TransitionChoreographer()

    private fun structure(
        id: String = "t",
        duration: Double = 300.0,
        bpm: Double? = 120.0,
        points: List<PointOfInterest> = emptyList(),
        loops: List<LoopCandidate> = emptyList(),
    ) = TrackStructure(
        trackId = id,
        durationSeconds = duration,
        bpm = bpm,
        points = points,
        loops = loops,
    )

    private fun point(at: Double, kind: PointOfInterest.Kind) = PointOfInterest(at, kind, 1f)

    private fun loop(start: Double, seconds: Double = 16.0, score: Float = 0.9f) =
        LoopCandidate(start, bars = 8, durationSeconds = seconds, score = score)

    /**
     * A match with a chosen score.
     *
     * Built directly rather than by picking keys and hoping: the choreographer's
     * gates are stated in scores, so a test about a gate should say the score it
     * is testing rather than encode a Camelot distance that happens to produce it.
     */
    private fun match(score: Int): MixMatch {
        val a = Track(title = "a", artist = "t", bpm = 128.0, camelotKey = "8A")
        val b = Track(title = "b", artist = "t", bpm = 128.0, camelotKey = "8A")
        return HarmonicEngine.compareTracks(a, b).copy(overallScore = score)
    }

    /** Every style produced over many draws, which is what the gates constrain. */
    private fun stylesOver(
        from: TrackStructure,
        to: TrackStructure,
        match: MixMatch?,
        phase: SetArc.Phase,
        draws: Int = 400,
    ): Set<TransitionStyle> = (0 until draws)
        .map { choreographer.choreograph(from, to, match, phase, Random(it.toLong())).style }
        .toSet()

    // --- A style is never offered without its material ---

    @Test
    fun `nothing but a blend is possible when nothing was measured`() {
        val bare = structure(bpm = null)
        val styles = stylesOver(bare, bare, match = null, phase = SetArc.Phase.PEAK)
        assertEquals(setOf(TransitionStyle.LONG_BLEND), styles)
    }

    @Test
    fun `a loop roll is never chosen without a loop to roll`() {
        val styles = stylesOver(
            from = structure(loops = emptyList()),
            to = structure(id = "b"),
            match = match(85),
            phase = SetArc.Phase.PEAK,
        )
        assertTrue("$styles", TransitionStyle.LOOP_ROLL !in styles)
    }

    @Test
    fun `a loop roll becomes possible as soon as there is a loop`() {
        val styles = stylesOver(
            from = structure(loops = listOf(loop(60.0))),
            to = structure(id = "b"),
            match = match(85),
            phase = SetArc.Phase.PEAK,
        )
        assertTrue("$styles", TransitionStyle.LOOP_ROLL in styles)
    }

    @Test
    fun `a reverse slam needs a drop to slam into`() {
        val without = stylesOver(
            from = structure(),
            to = structure(id = "b", points = emptyList()),
            match = match(85),
            phase = SetArc.Phase.PEAK,
        )
        assertTrue("$without", TransitionStyle.REVERSE_SLAM !in without)

        val with = stylesOver(
            from = structure(),
            to = structure(id = "b", points = listOf(point(20.0, PointOfInterest.Kind.DROP))),
            match = match(85),
            phase = SetArc.Phase.PEAK,
        )
        assertTrue("$with", TransitionStyle.REVERSE_SLAM in with)
    }

    @Test
    fun `a reverse slam is peak material only`() {
        // Bold was asked for. Bold everywhere is not bold, it is a tic.
        val to = structure(id = "b", points = listOf(point(20.0, PointOfInterest.Kind.DROP)))
        for (phase in listOf(SetArc.Phase.OPENING, SetArc.Phase.BUILD, SetArc.Phase.RELEASE)) {
            val styles = stylesOver(structure(), to, match(85), phase)
            assertTrue("$phase produced $styles", TransitionStyle.REVERSE_SLAM !in styles)
        }
    }

    @Test
    fun `tracks that clash never swap basslines or cut`() {
        // A cut leaves each side exposed and a bass swap stacks two low ends;
        // both need the keys to agree, and neither degrades gracefully.
        val styles = stylesOver(
            from = structure(loops = listOf(loop(60.0))),
            to = structure(id = "b", points = listOf(point(20.0, PointOfInterest.Kind.DROP))),
            match = match(30),
            phase = SetArc.Phase.PEAK,
        )
        assertTrue("$styles", TransitionStyle.BASS_SWAP !in styles)
        assertTrue("$styles", TransitionStyle.CUT !in styles)
    }

    @Test
    fun `leaving on a breakdown offers the handoff that suits it`() {
        val from = structure(points = listOf(point(200.0, PointOfInterest.Kind.BREAKDOWN)))
        val styles = stylesOver(from, structure(id = "b"), match(85), SetArc.Phase.BUILD)
        assertTrue("$styles", TransitionStyle.BREAKDOWN_HANDOFF in styles)
    }

    // --- Determinism and variety ---

    @Test
    fun `the same seed gives exactly the same transition`() {
        val from = structure(
            points = listOf(point(200.0, PointOfInterest.Kind.BREAKDOWN)),
            loops = listOf(loop(60.0)),
        )
        val to = structure(id = "b", points = listOf(point(40.0, PointOfInterest.Kind.DROP)))

        val first = choreographer.choreograph(from, to, match(85), SetArc.Phase.PEAK, Random(7))
        val again = choreographer.choreograph(from, to, match(85), SetArc.Phase.PEAK, Random(7))
        assertEquals(first, again)
    }

    @Test
    fun `the same pair of tracks is not handled the same way every time`() {
        // The complaint, stated as a test: a hundred handovers of one pair of
        // songs used to produce one transition, a hundred times.
        val from = structure(
            points = listOf(point(200.0, PointOfInterest.Kind.BREAKDOWN)),
            loops = listOf(loop(60.0)),
        )
        val to = structure(id = "b", points = listOf(point(40.0, PointOfInterest.Kind.DROP)))

        val scripts = (0 until 100)
            .map { choreographer.choreograph(from, to, match(85), SetArc.Phase.PEAK, Random(it.toLong())) }

        assertTrue("only one style in 100 draws", scripts.map { it.style }.toSet().size > 1)
        assertTrue("only one exit in 100 draws", scripts.map { it.exitSeconds }.toSet().size > 1)
    }

    @Test
    fun `a style just used is unlikely to be used again`() {
        val from = structure(
            points = listOf(point(200.0, PointOfInterest.Kind.BREAKDOWN)),
            loops = listOf(loop(60.0)),
        )
        val to = structure(id = "b", points = listOf(point(40.0, PointOfInterest.Kind.DROP)))

        val free = (0 until 300).count {
            choreographer.choreograph(from, to, match(85), SetArc.Phase.BUILD, Random(it.toLong()))
                .style == TransitionStyle.BREAKDOWN_HANDOFF
        }
        val penalised = (0 until 300).count {
            choreographer.choreograph(
                from, to, match(85), SetArc.Phase.BUILD, Random(it.toLong()),
                recentStyles = listOf(TransitionStyle.BREAKDOWN_HANDOFF),
            ).style == TransitionStyle.BREAKDOWN_HANDOFF
        }
        assertTrue("$penalised should be far below $free", penalised * 3 < free)
    }

    // --- Length and shape ---

    @Test
    fun `a track is never cut shorter than the floor`() {
        // A breakdown thirty seconds in is a real breakdown and a terrible exit.
        val from = structure(points = listOf(point(30.0, PointOfInterest.Kind.BREAKDOWN)))
        for (seed in 0 until 200) {
            val exit = choreographer.chooseExit(from, SetArc.Phase.PEAK, Random(seed.toLong()))
            assertTrue(
                "cut at ${exit.atSeconds}",
                exit.atSeconds >= TransitionChoreographer.MINIMUM_PLAY_SECONDS,
            )
        }
    }

    @Test
    fun `a track shorter than the floor is simply played`() {
        val from = structure(duration = 40.0)
        val exit = choreographer.chooseExit(from, SetArc.Phase.BUILD, Random(1))
        assertTrue(exit.atSeconds <= 40.0)
    }

    @Test
    fun `the peak leaves tracks earlier than the opening does`() {
        val from = structure(
            duration = 300.0,
            points = listOf(
                point(120.0, PointOfInterest.Kind.BREAKDOWN),
                point(260.0, PointOfInterest.Kind.BREAKDOWN),
            ),
        )
        fun meanExit(phase: SetArc.Phase) = (0 until 300)
            .map { choreographer.chooseExit(from, phase, Random(it.toLong())).atSeconds }
            .average()

        assertTrue(meanExit(SetArc.Phase.PEAK) < meanExit(SetArc.Phase.OPENING))
    }

    @Test
    fun `a transition never outruns the material it is leaving`() {
        val from = structure(duration = 200.0, loops = listOf(loop(60.0)))
        val to = structure(id = "b")
        for (seed in 0 until 200) {
            val script = choreographer.choreograph(from, to, match(85), SetArc.Phase.PEAK, Random(seed.toLong()))
            assertTrue(
                "fade ${script.fadeSeconds} against exit ${script.exitSeconds}",
                script.fadeSeconds <= script.exitSeconds / 3.0 + 1e-9 || script.fadeSeconds <= from.barSeconds,
            )
        }
    }

    @Test
    fun `entries move off the top of the track as the set climbs`() {
        val to = structure(points = listOf(point(96.0, PointOfInterest.Kind.DROP)))
        fun fromTop(phase: SetArc.Phase) = (0 until 400)
            .count { choreographer.chooseEntry(to, phase, Random(it.toLong())) == 0.0 }

        assertTrue(fromTop(SetArc.Phase.PEAK) < fromTop(SetArc.Phase.OPENING))
    }

    // --- The events a style implies ---

    @Test
    fun `a bass swap hands the low end over rather than stacking it`() {
        val script = TransitionScript(
            style = TransitionStyle.BASS_SWAP,
            exitSeconds = 200.0,
            entrySeconds = 0.0,
            fadeSeconds = 30.0,
            curve = FadeCurve.FAST_IN,
        )
        // The incoming track must arrive with its bass already out of the way;
        // two basslines at once is the thing this style exists to avoid.
        val real = choreographer.choreograph(
            structure(),
            structure(id = "b"),
            match(95),
            SetArc.Phase.BUILD,
            Random(0),
        )
        assertTrue(script.endSeconds == 230.0)
        assertTrue(real.fadeSeconds > 0.0)
    }

    @Test
    fun `a reverse slam always puts the rate back`() {
        // Nothing a transition does to a deck may outlive it. A set stopped
        // halfway through must not leave a deck playing backwards.
        val from = structure(duration = 400.0)
        val to = structure(id = "b", points = listOf(point(20.0, PointOfInterest.Kind.DROP)))
        val slam = (0 until 500)
            .map { choreographer.choreograph(from, to, match(85), SetArc.Phase.PEAK, Random(it.toLong())) }
            .first { it.style == TransitionStyle.REVERSE_SLAM }

        val rates = slam.events.mapNotNull { (it.action as? ScriptAction.Rate)?.rate }
        assertEquals(listOf(-1.0, 1.0), rates)
    }

    @Test
    fun `a cut holds still and then lands`() {
        assertEquals(0f, FadeCurve.HOLD_THEN_CUT.at(0.5), 1e-6f)
        assertEquals(1f, FadeCurve.HOLD_THEN_CUT.at(1.0), 1e-6f)
        assertNotEquals(0f, FadeCurve.HOLD_THEN_CUT.at(0.95))
    }

    @Test
    fun `every curve starts at one end and finishes at the other`() {
        for (curve in FadeCurve.entries) {
            assertEquals("$curve", 0f, curve.at(0.0), 1e-6f)
            assertEquals("$curve", 1f, curve.at(1.0), 1e-6f)
            assertEquals("$curve clamps", 1f, curve.at(4.0), 1e-6f)
            assertEquals("$curve clamps", 0f, curve.at(-4.0), 1e-6f)
        }
    }

    // --- Self-quoting ---

    @Test
    fun `a quote comes from the track's own earlier material`() {
        val s = structure(
            duration = 400.0,
            points = listOf(point(200.0, PointOfInterest.Kind.BREAKDOWN)),
            loops = listOf(loop(80.0)),
        )
        val events = (0 until 100)
            .map { choreographer.flourishes(s, SetArc.Phase.PEAK, Random(it.toLong()), safeUntil = 360.0) }
            .first { it.isNotEmpty() }

        val quoted = events.mapNotNull { (it.action as? ScriptAction.Loop)?.loop }.single()
        assertEquals(80.0, quoted.startSeconds, 1e-9)
        assertTrue("the quote must land after the material it quotes", events.first().atSeconds > 80.0)
    }

    @Test
    fun `nothing is quoted when the track has no space for it`() {
        // No breakdown to play under, so no quote — rather than dropping a loop
        // on top of a full mix and calling it a flourish.
        val s = structure(duration = 400.0, loops = listOf(loop(80.0)))
        for (seed in 0 until 200) {
            assertTrue(
                choreographer.flourishes(s, SetArc.Phase.PEAK, Random(seed.toLong()), 360.0).isEmpty(),
            )
        }
    }

    @Test
    fun `a quote always finishes before the handover begins`() {
        val s = structure(
            duration = 400.0,
            points = listOf(point(200.0, PointOfInterest.Kind.BREAKDOWN)),
            loops = listOf(loop(80.0)),
        )
        for (seed in 0 until 300) {
            val events = choreographer.flourishes(s, SetArc.Phase.PEAK, Random(seed.toLong()), safeUntil = 250.0)
            for (event in events) {
                assertTrue("event at ${event.atSeconds}", event.atSeconds < 250.0)
            }
        }
    }

    @Test
    fun `nothing is quoted in the first minute`() {
        // There is nothing to quote yet: a subject has to be stated before it can
        // be answered.
        val s = structure(
            duration = 400.0,
            points = listOf(point(30.0, PointOfInterest.Kind.BREAKDOWN)),
            loops = listOf(loop(5.0)),
        )
        for (seed in 0 until 200) {
            assertTrue(choreographer.flourishes(s, SetArc.Phase.PEAK, Random(seed.toLong()), 360.0).isEmpty())
        }
    }
}
