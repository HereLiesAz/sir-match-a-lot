package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.dsp.LoopCandidate
import com.hereliesaz.sirmatchalot.dsp.PointOfInterest
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
    fun `a tempo conformed track is scripted against its real, stretched playing time`() {
        // Track "b" is conformed from 100 to 128 BPM to follow "a" — it plays
        // correspondingly faster, and so finishes in correspondingly less
        // real time than its own recorded duration. durationOf/crossfadeSeconds
        // used to measure against the raw file duration and the raw,
        // unconformed BPM, so a track stretched to a faster reference tempo
        // was scripted transitions past the point it had actually finished
        // playing — the outgoing deck asked for audio it did not have, and a
        // lone clip on a deck loops.
        val trackA = track("a", bpm = 128.0, seconds = 240.0)
        val trackB = track("b", bpm = 100.0, seconds = 240.0)
        val plan = planOf(trackA, trackB)
        val (ratio, _) = BeatSync.tempoRatioTo(sourceBpm = 100.0, targetBpm = 128.0)
        val alignment = plan.steps[1].alignment
        assertNotNull("the second step must have a real computed alignment", alignment)
        assertEquals(ratio, alignment!!.tempoRatio, 1e-9)

        val director = MixDirector(plan, crossfadeBars = 16)
        val fade0 = director.crossfadeSeconds(0)
        val fade1 = director.crossfadeSeconds(1)
        // The fade out of the *conformed* track must be measured at the
        // tempo it actually sounds at (128 BPM, matching trackA — that is
        // the entire point of conforming it), not its own recorded 100 BPM.
        assertEquals(fade0, fade1, 1e-6)

        director.start()
        var elapsed = 0.0
        while (!director.finished && elapsed < 1_000.0) {
            director.advance(0.01)
            elapsed += 0.01
        }

        // trackA plays its full 240s; trackB, conformed to 128 BPM, actually
        // plays for 240/ratio seconds — not 240. There is exactly one
        // transition between two tracks, so the one overlapping fade is
        // subtracted once, not once per track.
        val expected = 240.0 + 240.0 / ratio - fade0
        assertTrue(
            "set ran ${"%.2f".format(elapsed)}s, expected about ${"%.2f".format(expected)}s " +
                "(would be ${"%.2f".format(480.0 - fade0)}s if the stretch were ignored)",
            abs(elapsed - expected) < 0.5,
        )
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
        //
        // True of *this* director, which has no structures and therefore plays
        // every track to its end. Given a TrackStructureProvider a set is shorter
        // again, because tracks are cut at musical exits — see
        // `a structured set leaves tracks at their musical exits` below. The two
        // do not contradict each other; they are the two halves of the same
        // design, and the plain blend is deliberately preserved intact.
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

    // --- Performing rather than sequencing ---
    //
    // Everything above runs without a TrackStructureProvider, and asserts the
    // plain sixteen-bar blend the director has always performed. That path is
    // untouched on purpose: it is what an unanalysed library still gets, and it
    // is the baseline these tests measure the new behaviour against.

    private fun structure(
        track: Track,
        points: List<PointOfInterest> = emptyList(),
        loops: List<LoopCandidate> = emptyList(),
    ) = TrackStructure.of(track, points = points, loops = loops)

    /** A provider over a fixed map, so a test states a track's shape in a line. */
    private fun provider(vararg entries: Pair<Track, TrackStructure>): TrackStructureProvider {
        val byId = entries.associate { it.first.id to it.second }
        return TrackStructureProvider { byId[it.id] }
    }

    @Test
    fun `the last track is retired, not left looping`() {
        // `Finished` used to be the only command at the end of a set, so the
        // final clip stayed playing on a deck it had to itself — and a clip
        // alone on a deck loops. The set announced that it was over and then
        // played the last track again, for ever.
        val a = track("one", seconds = 90.0)
        val b = track("two", seconds = 90.0)
        val commands = run(MixDirector(planOf(a, b)), seconds = 400.0)

        val finishedAt = commands.indexOfFirst { it is MixCommand.Finished }
        assertTrue("the set must finish", finishedAt >= 0)
        val retiredLast = commands.take(finishedAt + 1).any {
            it is MixCommand.Retire && it.track.id == b.id
        }
        assertTrue("the final track must be stopped when the set ends", retiredLast)
    }

    @Test
    fun `a scripted exit never runs the outgoing track past its own end`() {
        // `chooseExit` always offers the track's end as its floor candidate, and
        // with that chosen `leaveAt` was `duration + fade`: the outgoing deck
        // kept playing a whole fade past the end of its material, which for a
        // clip alone on a deck means looping back into its own intro underneath
        // the incoming track.
        val a = track("one", seconds = 200.0)
        val b = track("two", seconds = 200.0)
        // No points on `a`, so the only exit available is TRACK_END.
        val director = MixDirector(
            plan = planOf(a, b),
            structures = provider(a to structure(a), b to structure(b)),
            seed = 11,
        )

        director.start()
        var elapsed = 0.0
        var retiredAt = -1.0
        while (!director.finished && elapsed < 1_000.0) {
            val commands = director.advance(0.02)
            elapsed += 0.02
            if (commands.any { it is MixCommand.Retire && it.track.id == a.id }) {
                retiredAt = elapsed
                break
            }
        }

        assertTrue("the outgoing track must be retired", retiredAt > 0.0)
        assertTrue(
            "retired at $retiredAt, past its own 200 s of material",
            retiredAt <= 200.0 + 0.5,
        )
    }

    @Test
    fun `a track entered late is not skipped by an exit behind it`() {
        // `chooseExit` measured its floor from frame zero, so on a track entered
        // part way in, an exit earlier than the entry counted as valid — and
        // every stage of the handover was then already due on the tick the
        // script resolved. The track was announced and immediately replaced.
        val a = track("one", seconds = 300.0)
        val b = track("two", seconds = 300.0)
        val c = track("three", seconds = 300.0)

        // `b` has a drop at 150 s, so it is entered well after zero, and a
        // breakdown at 60 s that would be behind that entry.
        val bPoints = listOf(
            PointOfInterest(150.0, PointOfInterest.Kind.DROP, 1f),
            PointOfInterest(60.0, PointOfInterest.Kind.BREAKDOWN, 1f),
        )
        val director = MixDirector(
            plan = planOf(a, b, c),
            structures = provider(
                a to structure(a, points = listOf(PointOfInterest(120.0, PointOfInterest.Kind.BREAKDOWN, 1f))),
                b to structure(b, points = bPoints),
                c to structure(c),
            ),
            seed = 5,
        )

        director.start()
        var elapsed = 0.0
        var secondStarted = -1.0
        var secondRetired = -1.0
        while (!director.finished && elapsed < 2_000.0) {
            val commands = director.advance(0.02)
            elapsed += 0.02
            if (commands.any { it is MixCommand.Start && it.track.id == b.id }) secondStarted = elapsed
            if (commands.any { it is MixCommand.Retire && it.track.id == b.id }) {
                secondRetired = elapsed
                break
            }
        }

        assertTrue("the middle track must play", secondStarted > 0.0)
        assertTrue("the middle track must be handed over", secondRetired > 0.0)
        val played = secondRetired - secondStarted
        assertTrue(
            "played for only ${"%.1f".format(played)} s — that is a stab, not a track",
            played >= TransitionChoreographer.MINIMUM_PLAY_SECONDS * 0.5,
        )
    }

    @Test
    fun `a structured set leaves tracks at their musical exits`() {
        // The user asked for tracks to be cut at musical exits rather than played
        // to the end, so a set with structures is shorter than the same plan
        // without them. This is the assertion the older length test is the
        // no-provider half of.
        val a = track("one", seconds = 300.0)
        val b = track("two", seconds = 300.0)
        val plan = planOf(a, b)

        val breakdown = PointOfInterest(140.0, PointOfInterest.Kind.BREAKDOWN, 1f)
        val director = MixDirector(
            plan = plan,
            structures = provider(a to structure(a, points = listOf(breakdown)), b to structure(b)),
            seed = 3,
        )

        director.start()
        var elapsed = 0.0
        var handover = -1.0
        while (!director.finished && elapsed < 2_000.0) {
            val commands = director.advance(0.02)
            if (handover < 0 && commands.any { it is MixCommand.Retire }) handover = elapsed
            elapsed += 0.02
        }

        assertTrue("never handed over", handover > 0.0)
        assertTrue(
            "handed over at $handover, which is not the breakdown",
            handover < 250.0,
        )
    }

    @Test
    fun `an entry offset is carried to whoever starts the deck`() {
        // A track whose first ninety seconds are a filter sweep must not be heard
        // from frame zero every single time.
        val a = track("one", seconds = 300.0)
        val b = track("two", seconds = 300.0)
        val plan = planOf(a, b)

        val drop = PointOfInterest(120.0, PointOfInterest.Kind.DROP, 1f)
        var seen = false
        for (seed in 0L until 40L) {
            val director = MixDirector(
                plan = plan,
                structures = provider(a to structure(a), b to structure(b, points = listOf(drop))),
                seed = seed,
            )
            val entries = run(director, seconds = 900.0)
                .filterIsInstance<MixCommand.Start>()
                .map { it.entrySeconds }
            if (entries.any { it > 0.0 }) { seen = true; break }
        }
        assertTrue("every entry was still from the top of the track", seen)
    }

    @Test
    fun `nothing a transition does to a deck outlives the deck`() {
        // A set stopped mid-slam must not leave a deck playing backwards, and the
        // deck about to be reused must not inherit an eighteen dB shelf.
        val a = track("one", seconds = 300.0)
        val b = track("two", seconds = 300.0)
        val c = track("three", seconds = 300.0)
        val plan = planOf(a, b, c)

        val drop = PointOfInterest(30.0, PointOfInterest.Kind.DROP, 1f)
        val loops = listOf(LoopCandidate(80.0, 8, 16.0, 0.9f))
        val director = MixDirector(
            plan = plan,
            structures = provider(
                a to structure(a, loops = loops),
                b to structure(b, points = listOf(drop), loops = loops),
                c to structure(c, points = listOf(drop)),
            ),
            seed = 11,
        )

        val commands = run(director, seconds = 2_000.0)
        // Every retire is preceded by the deck being handed back as it was found.
        for ((index, command) in commands.withIndex()) {
            if (command !is MixCommand.Retire) continue
            val before = commands.subList(maxOf(0, index - 6), index)
            assertTrue(
                "deck ${command.deck} retired without its rate restored",
                before.any { it is MixCommand.SetDeckRate && it.deck == command.deck && it.rate == 1.0 },
            )
            assertTrue(
                "deck ${command.deck} retired still shelved",
                before.any {
                    it is MixCommand.SetDeckEq && it.deck == command.deck &&
                        it.bassDb == 0.0 && it.trebleDb == 0.0
                },
            )
            assertTrue(
                "deck ${command.deck} retired at the wrong gain",
                before.any { it is MixCommand.SetDeckGain && it.deck == command.deck && it.gain == 1f },
            )
        }
    }

    @Test
    fun `a self-quote never collides with the transition it precedes`() {
        // The flourish is the track answering its own subject; a loop still
        // ringing when a loop roll begins would read as one botched effect
        // rather than two deliberate ones.
        val a = track("one", seconds = 400.0)
        val b = track("two", seconds = 400.0)
        val plan = planOf(a, b)

        val shape = structure(
            a,
            points = listOf(PointOfInterest(200.0, PointOfInterest.Kind.BREAKDOWN, 1f)),
            loops = listOf(LoopCandidate(80.0, 8, 15.0, 0.9f)),
        )

        for (seed in 0L until 60L) {
            val director = MixDirector(
                plan = plan,
                structures = provider(a to shape, b to structure(b)),
                seed = seed,
            )
            director.start()
            var elapsed = 0.0
            var transitionAt = -1.0
            var lastLoop = -1.0
            while (!director.finished && elapsed < 1_500.0) {
                val commands = director.advance(0.02)
                if (transitionAt < 0 && commands.any { it is MixCommand.Performing }) {
                    transitionAt = elapsed
                }
                if (transitionAt < 0 && commands.any { it is MixCommand.TriggerLoop }) {
                    lastLoop = elapsed
                }
                elapsed += 0.02
            }
            if (lastLoop > 0 && transitionAt > 0) {
                assertTrue("quote at $lastLoop ran into the handover at $transitionAt", lastLoop < transitionAt)
            }
        }
    }

    @Test
    fun `an unanalysed track falls back to the plain blend rather than guessing`() {
        // The provider answering null is a first-class answer: a track nothing
        // was measured on has no landmarks, and inventing some would be worse
        // than the blend it replaced.
        val a = track("one", seconds = 60.0)
        val b = track("two", seconds = 60.0)
        val plan = planOf(a, b)

        val blind = run(MixDirector(plan), seconds = 200.0)
        val alsoBlind = run(
            MixDirector(plan, structures = { null }, seed = 99),
            seconds = 200.0,
        )
        assertEquals(blind, alsoBlind)
    }

    @Test
    fun `the same seed performs the same set twice`() {
        val a = track("one", seconds = 300.0)
        val b = track("two", seconds = 300.0)
        val plan = planOf(a, b)
        val shapes = provider(
            a to structure(
                a,
                points = listOf(PointOfInterest(150.0, PointOfInterest.Kind.BREAKDOWN, 1f)),
                loops = listOf(LoopCandidate(80.0, 8, 16.0, 0.9f)),
            ),
            b to structure(b, points = listOf(PointOfInterest(60.0, PointOfInterest.Kind.DROP, 1f))),
        )

        fun perform(seed: Long) =
            run(MixDirector(plan, structures = shapes, seed = seed), seconds = 900.0)

        assertEquals(perform(5), perform(5))
    }

    @Test
    fun `two runs of the same library are not the same set`() {
        // The complaint, as a test. Same plan, same structures, different night.
        val a = track("one", seconds = 300.0)
        val b = track("two", seconds = 300.0)
        val plan = planOf(a, b)
        val shapes = provider(
            a to structure(
                a,
                points = listOf(
                    PointOfInterest(120.0, PointOfInterest.Kind.BREAKDOWN, 1f),
                    PointOfInterest(220.0, PointOfInterest.Kind.BREAKDOWN, 1f),
                ),
                loops = listOf(LoopCandidate(80.0, 8, 16.0, 0.9f)),
            ),
            b to structure(b, points = listOf(PointOfInterest(60.0, PointOfInterest.Kind.DROP, 1f))),
        )

        val performances = (0L until 12L).map { seed ->
            run(MixDirector(plan, structures = shapes, seed = seed), seconds = 900.0)
                .filterIsInstance<MixCommand.Performing>()
                .map { it.style }
        }
        assertTrue("every run performed identically: $performances", performances.toSet().size > 1)
    }

    @Test
    fun `the crossfader still ends hard over on the incoming deck`() {
        // Whatever curve a style travels on, a transition that does not finish
        // leaves both tracks half audible for the rest of the set.
        val a = track("one", seconds = 300.0)
        val b = track("two", seconds = 300.0)
        val plan = planOf(a, b)
        val shapes = provider(
            a to structure(a, points = listOf(PointOfInterest(150.0, PointOfInterest.Kind.BREAKDOWN, 1f))),
            b to structure(b, points = listOf(PointOfInterest(60.0, PointOfInterest.Kind.DROP, 1f))),
        )

        for (seed in 0L until 30L) {
            val positions = run(MixDirector(plan, structures = shapes, seed = seed), seconds = 900.0)
                .filterIsInstance<MixCommand.Crossfade>()
                .map { it.position }
            assertTrue("seed $seed never reached deck B: ${positions.lastOrNull()}", positions.last() == 1f)
            assertTrue("seed $seed left the fader out of range", positions.all { it in 0f..1f })
        }
    }
}
