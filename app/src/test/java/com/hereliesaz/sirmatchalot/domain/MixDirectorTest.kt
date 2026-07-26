package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Performing a planned mix.
 *
 * The director is a pure function of elapsed time, so these run the whole set in
 * milliseconds and can assert on exactly where the crossfader is at any instant —
 * which is the part that was missing, not the running order.
 */
class MixDirectorTest {

    private fun track(
        title: String,
        bpm: Double = 128.0,
        seconds: Double = 240.0,
        key: String = "8A",
        energy: Int = 5,
    ) = Track(
        title = title,
        artist = "Test",
        bpm = bpm,
        camelotKey = key,
        energyLevel = energy,
        durationMs = (seconds * 1000).toLong(),
    )

    private fun planOf(vararg tracks: Track): MixPlan =
        MixPlan(
            steps = tracks.mapIndexed { i, t ->
                MixStep(
                    track = t,
                    transition = if (i == 0) null else HarmonicEngine.compareTracks(tracks[i - 1], t),
                    alignment = if (i == 0) null else BeatSync.align(t, tracks[i - 1]),
                )
            },
            skipped = emptyList(),
            averageScore = 80,
        )

    /** Runs the director forward, collecting every command it issues. */
    private fun run(
        director: MixDirector,
        seconds: Double,
        step: Double = 0.05,
    ): List<MixCommand> {
        val all = ArrayList<MixCommand>()
        all += director.start()
        var t = 0.0
        while (t < seconds && !director.finished) {
            all += director.advance(step)
            t += step
        }
        return all
    }

    // --- Degenerate plans ---

    @Test
    fun `an empty plan finishes immediately`() {
        val director = MixDirector(MixPlan(emptyList(), emptyList(), 0))
        assertEquals(listOf(MixCommand.Finished), director.start())
        assertTrue(director.finished)
    }

    @Test
    fun `a plan whose opening track has no duration finishes rather than hanging`() {
        val plan = planOf(track("no length", seconds = 0.0))
        val director = MixDirector(plan)
        assertEquals(listOf(MixCommand.Finished), director.start())
    }

    @Test
    fun `a single track plays to its end and then finishes`() {
        val plan = planOf(track("only", seconds = 10.0))
        val director = MixDirector(plan)
        val commands = run(director, seconds = 12.0)

        assertTrue(director.finished)
        assertTrue(commands.any { it is MixCommand.Start })
        assertTrue(commands.last() is MixCommand.Finished)
        // Nothing to mix into, so the crossfader never moves off its deck.
        val positions = commands.filterIsInstance<MixCommand.Crossfade>().map { it.position }
        assertTrue("crossfader moved with nothing to move to: $positions", positions.all { it == 0f })
    }

    // --- Opening ---

    @Test
    fun `the opening track starts with the crossfader hard over to its deck`() {
        val plan = planOf(track("one"), track("two"))
        val commands = MixDirector(plan).start()

        assertEquals(MixCommand.Preload(plan.steps[0].track, MixDeck.A), commands[0])
        assertEquals(MixCommand.Start(plan.steps[0].track, MixDeck.A, null), commands[1])
        assertEquals(MixCommand.Crossfade(0f), commands[2])
    }

    @Test
    fun `the opening track has no alignment because there is nothing to align to`() {
        val plan = planOf(track("one"), track("two"))
        val start = MixDirector(plan).start().filterIsInstance<MixCommand.Start>().first()
        assertNull(start.alignment)
    }

    // --- Transition timing ---

    @Test
    fun `a crossfade lasts the requested number of bars`() {
        // 16 bars of 4 beats at 128 BPM = 64 beats = 30 seconds.
        val director = MixDirector(planOf(track("a", bpm = 128.0), track("b")), crossfadeBars = 16)
        assertEquals(30.0, director.crossfadeSeconds(0), 1e-9)
    }

    @Test
    fun `crossfade length follows tempo, so it stays the same musical length`() {
        val fast = MixDirector(planOf(track("a", bpm = 160.0), track("b")))
        val slow = MixDirector(planOf(track("a", bpm = 80.0), track("b")))
        // Half the tempo, twice the wall-clock time, same number of bars.
        assertEquals(slow.crossfadeSeconds(0), fast.crossfadeSeconds(0) * 2, 1e-9)
    }

    @Test
    fun `a transition is never longer than a third of the track it leaves`() {
        // 16 bars at 60 BPM would be 64 seconds, but the track is only 30.
        val director = MixDirector(planOf(track("short", bpm = 60.0, seconds = 30.0), track("b")))
        assertEquals(10.0, director.crossfadeSeconds(0), 1e-9)
    }

    @Test
    fun `the incoming track starts exactly when the fade starts`() {
        val plan = planOf(track("a", seconds = 100.0), track("b"))
        val director = MixDirector(plan, crossfadeBars = 16)
        val fade = director.crossfadeSeconds(0)

        director.start()
        // Just before the transition point, nothing has started.
        director.advance(100.0 - fade - 0.1)
        assertEquals(0.0, director.transitionProgress, 1e-9)

        val commands = director.advance(0.2)
        val start = commands.filterIsInstance<MixCommand.Start>().firstOrNull()
        assertNotNull("deck B should enter as the fade begins", start)
        assertEquals(MixDeck.B, start!!.deck)
        assertEquals(plan.steps[1].track, start.track)
    }

    @Test
    fun `the incoming track enters with the alignment the planner computed`() {
        // 128 against 100 BPM needs a real ratio, so the alignment is not trivial.
        val plan = planOf(track("a", bpm = 100.0, seconds = 100.0), track("b", bpm = 128.0))
        val director = MixDirector(plan)
        val commands = run(director, seconds = 100.0)

        val entry = commands.filterIsInstance<MixCommand.Start>().last()
        assertEquals(plan.steps[1].alignment, entry.alignment)
        assertNotNull("the planner produced an alignment; the director must pass it", entry.alignment)
    }

    @Test
    fun `the next track is preloaded before it is needed`() {
        val plan = planOf(track("a", seconds = 200.0), track("b"))
        val director = MixDirector(plan, preloadLeadSeconds = 45.0)
        val fade = director.crossfadeSeconds(0)

        director.start()
        // Up to one second before the preload point, deck B must not be touched:
        // decoding early would hold memory for a track minutes away.
        val early = director.advance(200.0 - fade - 45.0 - 1.0)
        assertTrue(
            "preloaded too early: $early",
            early.none { it is MixCommand.Preload },
        )

        val commands = director.advance(2.0)
        val preload = commands.filterIsInstance<MixCommand.Preload>().firstOrNull()
        assertNotNull("deck B should be preloaded ahead of its entry", preload)
        assertEquals(MixDeck.B, preload!!.deck)

        // And it must land before the fade begins, with the lead time intact.
        assertEquals(0.0, director.transitionProgress, 1e-9)
    }

    @Test
    fun `a track shorter than the preload lead still gets its next track loaded`() {
        // The preload point falls before the track even starts, so the transition
        // must issue the load itself rather than starting an empty deck.
        val plan = planOf(track("brief", seconds = 20.0), track("b"))
        val director = MixDirector(plan, preloadLeadSeconds = 45.0)
        val commands = run(director, seconds = 25.0)

        val preloadIndex = commands.indexOfFirst {
            it is MixCommand.Preload && it.deck == MixDeck.B
        }
        val startIndex = commands.indexOfFirst {
            it is MixCommand.Start && it.deck == MixDeck.B
        }
        assertTrue("deck B was never preloaded", preloadIndex >= 0)
        assertTrue("deck B started before it was loaded", preloadIndex < startIndex)
    }

    // --- The crossfade itself ---

    @Test
    fun `the crossfader sweeps monotonically from one deck to the other`() {
        val plan = planOf(track("a", seconds = 60.0), track("b"))
        val director = MixDirector(plan)
        val commands = run(director, seconds = 60.0)

        val positions = commands.filterIsInstance<MixCommand.Crossfade>().map { it.position }
        assertEquals(0f, positions.first(), 0f)
        assertTrue("never reached deck B: ${positions.last()}", positions.last() >= 0.999f)
        for (i in 1 until positions.size) {
            assertTrue(
                "crossfader went backwards at $i: ${positions[i - 1]} -> ${positions[i]}",
                positions[i] >= positions[i - 1] - 1e-6f,
            )
        }
    }

    @Test
    fun `the crossfade is half done halfway through the transition`() {
        val plan = planOf(track("a", seconds = 100.0), track("b"))
        val director = MixDirector(plan)
        val fade = director.crossfadeSeconds(0)

        director.start()
        director.advance(100.0 - fade)
        val half = director.advance(fade / 2)
        val position = half.filterIsInstance<MixCommand.Crossfade>().last().position
        assertEquals(0.5f, position, 0.02f)
    }

    @Test
    fun `the outgoing deck is retired only once the fade completes`() {
        val plan = planOf(track("a", seconds = 60.0), track("b"))
        val director = MixDirector(plan)
        val commands = run(director, seconds = 60.0)

        val retireIndex = commands.indexOfFirst { it is MixCommand.Retire }
        assertTrue("deck A was never retired", retireIndex >= 0)

        // Everything before the retire that touches the crossfader is mid-fade;
        // the position at the retire must be all the way over.
        val lastFadeBefore = commands.take(retireIndex)
            .filterIsInstance<MixCommand.Crossfade>()
            .last()
        assertEquals(1f, lastFadeBefore.position, 1e-6f)
        assertEquals(MixDeck.A, (commands[retireIndex] as MixCommand.Retire).deck)
    }

    // --- Running a whole set ---

    @Test
    fun `decks alternate across a set`() {
        val plan = planOf(
            track("one", seconds = 40.0),
            track("two", seconds = 40.0),
            track("three", seconds = 40.0),
            track("four", seconds = 40.0),
        )
        val director = MixDirector(plan)
        val commands = run(director, seconds = 200.0)

        val order = commands.filterIsInstance<MixCommand.Start>().map { it.deck }
        assertEquals(listOf(MixDeck.A, MixDeck.B, MixDeck.A, MixDeck.B), order)
    }

    @Test
    fun `every track in the plan is played exactly once, in order`() {
        val plan = planOf(
            track("one", seconds = 40.0),
            track("two", seconds = 40.0),
            track("three", seconds = 40.0),
        )
        val director = MixDirector(plan)
        val commands = run(director, seconds = 200.0)

        val played = commands.filterIsInstance<MixCommand.Start>().map { it.track.title }
        assertEquals(listOf("one", "two", "three"), played)
        assertTrue(director.finished)
    }

    @Test
    fun `a set runs for the sum of its tracks minus the overlaps`() {
        // Each transition overlaps two tracks, so the set is shorter than the sum
        // of the durations by one crossfade per transition. Getting this wrong is
        // how a "60 minute set" turns out to be 75.
        val plan = planOf(
            track("one", seconds = 60.0),
            track("two", seconds = 60.0),
            track("three", seconds = 60.0),
        )
        val director = MixDirector(plan)
        val fade = director.crossfadeSeconds(0)

        director.start()
        var elapsed = 0.0
        while (!director.finished && elapsed < 1_000.0) {
            director.advance(0.01)
            elapsed += 0.01
        }

        val expected = 180.0 - 2 * fade
        assertTrue(
            "set ran ${"%.2f".format(elapsed)}s, expected about ${"%.2f".format(expected)}s",
            abs(elapsed - expected) < 0.5,
        )
    }

    @Test
    fun `NowPlaying reports each track as it takes the foreground`() {
        val plan = planOf(
            track("one", seconds = 40.0),
            track("two", seconds = 40.0),
            track("three", seconds = 40.0),
        )
        val commands = run(MixDirector(plan), seconds = 200.0)

        val announced = commands.filterIsInstance<MixCommand.NowPlaying>()
        assertEquals(listOf(0, 1, 2), announced.map { it.index })
        assertEquals(listOf("one", "two", "three"), announced.map { it.step.track.title })
        assertTrue(announced.all { it.total == 3 })
    }

    @Test
    fun `advancing after the plan has finished does nothing`() {
        val director = MixDirector(planOf(track("only", seconds = 5.0)))
        run(director, seconds = 10.0)
        assertTrue(director.finished)
        assertEquals(emptyList<MixCommand>(), director.advance(1.0))
    }

    @Test
    fun `advancing before start does nothing`() {
        val director = MixDirector(planOf(track("one"), track("two")))
        assertEquals(emptyList<MixCommand>(), director.advance(10.0))
    }

    @Test
    fun `starting twice does not restart the mix`() {
        val director = MixDirector(planOf(track("one"), track("two")))
        assertTrue(director.start().isNotEmpty())
        assertEquals(emptyList<MixCommand>(), director.start())
    }

    @Test
    fun `the result does not depend on the tick rate`() {
        // The caller ticks this from a UI loop whose interval is not guaranteed,
        // so a coarse tick must produce the same running order as a fine one.
        fun titles(step: Double): List<String> {
            val plan = planOf(
                track("one", seconds = 40.0),
                track("two", seconds = 40.0),
                track("three", seconds = 40.0),
            )
            return run(MixDirector(plan), seconds = 200.0, step = step)
                .filterIsInstance<MixCommand.Start>()
                .map { it.track.title }
        }
        assertEquals(titles(0.016), titles(0.5))
    }
}
