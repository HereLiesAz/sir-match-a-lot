package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** Shared track fixtures with measured-looking values. */
internal fun track(
    id: String,
    bpm: Double? = 128.0,
    camelot: String? = "8A",
    energy: Int? = 5,
    firstBeat: Double? = 0.0,
): Track = Track(
    id = id,
    title = id,
    artist = "artist",
    sourceUri = "file:///$id",
    bpm = bpm,
    camelotKey = camelot,
    energyLevel = energy,
    firstBeatSeconds = firstBeat,
    analysisVersion = Track.CURRENT_ANALYSIS_VERSION,
)

class MixPlannerTest {

    // --- Shuffle Crate ---

    @Test
    fun `shuffle crate needs two analysed tracks`() {
        assertNull(MixPlanner.shuffleCrate(emptyList()))
        assertNull(MixPlanner.shuffleCrate(listOf(track("only"))))
    }

    @Test
    fun `shuffle crate ignores unanalysed tracks`() {
        // Two tracks, but neither measured — there is nothing to be intelligent
        // about, so it must decline rather than pair them arbitrarily.
        val unmeasured = listOf(
            track("a", bpm = null, camelot = null),
            track("b", bpm = null, camelot = null),
        )
        assertNull(MixPlanner.shuffleCrate(unmeasured))
    }

    @Test
    fun `shuffle crate picks a compatible pair`() {
        val pick = requireNotNull(
            MixPlanner.shuffleCrate(
                listOf(
                    track("a", bpm = 128.0, camelot = "8A"),
                    track("b", bpm = 129.0, camelot = "9A"),
                ),
                Random(1),
            ),
        )
        assertTrue("score was ${pick.match.overallScore}", pick.match.overallScore >= MixPlanner.MINIMUM_USABLE_SCORE)
        assertTrue(pick.deckA.id != pick.deckB.id)
    }

    @Test
    fun `shuffle crate declines when nothing is compatible`() {
        // Deliberately far from every metrical relationship: 100 is not near
        // 70, nor its double (140) or half (35). An earlier version of this
        // fixture used 143, which is within 2% of double 70 and so is genuinely
        // mixable — the planner was right and the test was wrong.
        val incompatible = listOf(
            track("slow", bpm = 70.0, camelot = "8A"),
            track("fast", bpm = 100.0, camelot = "2B"),
        )
        assertNull(MixPlanner.shuffleCrate(incompatible))
    }

    @Test
    fun `shuffle crate favours better matches but does vary`() {
        // A shuffle that always returns the same pair is not a shuffle; one that
        // ignores score is not intelligent. It must do both.
        val library = listOf(
            track("perfect1", bpm = 128.0, camelot = "8A"),
            track("perfect2", bpm = 128.0, camelot = "8A"),
            track("okay", bpm = 132.0, camelot = "10A"),
        )
        val picks = (1..40).mapNotNull { seed ->
            MixPlanner.shuffleCrate(library, Random(seed))?.let { it.deckA.id to it.deckB.id }
        }
        assertTrue("should have produced picks", picks.isNotEmpty())
        assertTrue("should vary across seeds", picks.toSet().size > 1)
        // The great matches should dominate.
        val greatShare = picks.count { "perfect1" in it.toList() && "perfect2" in it.toList() }
        assertTrue("good pairs should be favoured, got $greatShare of ${picks.size}", greatShare > picks.size / 4)
    }

    @Test
    fun `shuffle crate reports the alignment for the incoming deck`() {
        val pick = requireNotNull(
            MixPlanner.shuffleCrate(
                listOf(track("a", bpm = 128.0, camelot = "8A"), track("b", bpm = 130.0, camelot = "8A")),
                Random(3),
            ),
        )
        assertNotNull("callers need to know what to correct", pick.alignment)
    }

    // --- Automatchic Mix ---

    @Test
    fun `automatchic mix orders every analysed track exactly once`() {
        val library = (1..8).map { track("t$it", bpm = 120.0 + it, camelot = "8A", energy = it) }
        val plan = MixPlanner.automatchicMix(library)
        assertEquals(library.size, plan.steps.size)
        assertEquals(library.map { it.id }.toSet(), plan.steps.map { it.track.id }.toSet())
    }

    @Test
    fun `automatchic mix reports what it skipped rather than dropping it silently`() {
        val library = listOf(
            track("measured", bpm = 128.0, camelot = "8A"),
            track("unmeasured", bpm = null, camelot = null),
        )
        val plan = MixPlanner.automatchicMix(library)
        assertEquals(listOf("measured"), plan.steps.map { it.track.id })
        assertEquals(listOf("unmeasured"), plan.skipped.map { it.id })
    }

    @Test
    fun `automatchic mix opens on the lowest energy track so the set can build`() {
        val library = listOf(
            track("hot", energy = 9),
            track("cool", energy = 2),
            track("mid", energy = 6),
        )
        assertEquals("cool", MixPlanner.automatchicMix(library).steps.first().track.id)
    }

    @Test
    fun `automatchic mix honours an explicit opening track`() {
        val library = listOf(track("a", energy = 2), track("b", energy = 8))
        val plan = MixPlanner.automatchicMix(library, startWith = library[1])
        assertEquals("b", plan.steps.first().track.id)
    }

    @Test
    fun `automatchic mix builds energy one step at a time`() {
        // All harmonically identical, so energy is the only thing left to order by.
        // The plan must climb steadily rather than leap: an earlier scoring rule
        // rewarded the largest allowed jump and produced 1,3,5,7,9 then back down
        // the evens, which climbs by skipping material.
        val library = (1..9).map { track("e$it", bpm = 128.0, camelot = "8A", energy = it) }
        val energies = MixPlanner.automatchicMix(library).steps.map { it.track.energyLevel ?: 0 }
        assertEquals("should walk energy in order, was $energies", (1..9).toList(), energies)
    }

    @Test
    fun `automatchic mix gives every step after the first a transition`() {
        val library = (1..5).map { track("t$it", bpm = 126.0 + it, camelot = "8A", energy = it) }
        val plan = MixPlanner.automatchicMix(library)
        assertNull("the opener has nothing to transition from", plan.steps.first().transition)
        for (step in plan.steps.drop(1)) {
            assertNotNull("step ${step.track.id} needs a transition", step.transition)
            assertNotNull("step ${step.track.id} needs an alignment", step.alignment)
        }
    }

    @Test
    fun `automatchic mix prefers harmonically compatible neighbours`() {
        // From 8A, 9A is a perfect fifth and 2A is across the wheel.
        val library = listOf(
            track("start", bpm = 128.0, camelot = "8A", energy = 5),
            track("fifth", bpm = 128.0, camelot = "9A", energy = 5),
            track("distant", bpm = 128.0, camelot = "2A", energy = 5),
        )
        val plan = MixPlanner.automatchicMix(library, startWith = library[0])
        assertEquals("fifth", plan.steps[1].track.id)
    }

    @Test
    fun `automatchic mix handles an empty and a single-track library`() {
        assertTrue(MixPlanner.automatchicMix(emptyList()).steps.isEmpty())
        val single = MixPlanner.automatchicMix(listOf(track("one")))
        assertEquals(1, single.steps.size)
        assertEquals(0, single.averageScore)
    }

    @Test
    fun `automatchic mix reports an average transition score`() {
        val library = (1..4).map { track("t$it", bpm = 128.0, camelot = "8A", energy = it) }
        val plan = MixPlanner.automatchicMix(library)
        assertTrue("identical keys and tempi should score high", plan.averageScore > 80)
    }

    @Test
    fun `automatchic mix is deterministic`() {
        val library = (1..6).map { track("t$it", bpm = 120.0 + it * 2, camelot = "8A", energy = it) }
        assertEquals(
            MixPlanner.automatchicMix(library).steps.map { it.track.id },
            MixPlanner.automatchicMix(library).steps.map { it.track.id },
        )
    }

}

class BeatSyncTest {

    @Test
    fun `no alignment without a measured tempo`() {
        assertNull(BeatSync.align(track("a", bpm = null), track("b")))
        assertNull(BeatSync.align(track("a"), track("b", bpm = null)))
        assertNull(BeatSync.align(track("a", bpm = 0.0), track("b")))
    }

    @Test
    fun `identical tracks need no correction`() {
        val alignment = requireNotNull(BeatSync.align(track("a"), track("b")))
        assertEquals(1.0, alignment.tempoRatio, 1e-9)
        assertEquals(0.0, alignment.phaseOffsetSeconds, 1e-9)
        assertEquals(0, alignment.semitoneShift)
        assertTrue(alignment.isAligned)
    }

    @Test
    fun `tempo ratio brings the source to the target`() {
        val alignment = requireNotNull(
            BeatSync.align(track("src", bpm = 120.0), track("dst", bpm = 126.0)),
        )
        assertEquals(126.0 / 120.0, alignment.tempoRatio, 1e-9)
        assertTrue(!alignment.isHalfOrDoubleTime)
    }

    @Test
    fun `half time is chosen when it is the closer relationship`() {
        // 174 over 87 must play at its own tempo, not be warped 2x down.
        val alignment = requireNotNull(
            BeatSync.align(track("dnb", bpm = 174.0), track("hiphop", bpm = 87.0)),
        )
        assertEquals(1.0, alignment.tempoRatio, 1e-6)
        assertTrue("should be recognised as a half-time pairing", alignment.isHalfOrDoubleTime)
    }

    @Test
    fun `double time is chosen when it is the closer relationship`() {
        val alignment = requireNotNull(
            BeatSync.align(track("slow", bpm = 70.0), track("fast", bpm = 140.0)),
        )
        assertEquals(1.0, alignment.tempoRatio, 1e-6)
        assertTrue(alignment.isHalfOrDoubleTime)
    }

    @Test
    fun `the tempo ratio never warps by more than about a factor of the square root of two`() {
        // Because half and double are always available, the worst case is landing
        // midway between two metrical levels.
        for (sourceBpm in listOf(70.0, 90.0, 128.0, 150.0, 174.0, 200.0)) {
            for (targetBpm in listOf(70.0, 90.0, 128.0, 150.0, 174.0, 200.0)) {
                val (ratio, _) = BeatSync.tempoRatioTo(sourceBpm, targetBpm)
                assertTrue(
                    "$sourceBpm -> $targetBpm warped by $ratio",
                    ratio in 0.70..1.42,
                )
            }
        }
    }

    @Test
    fun `phase offset aligns the grids and is within half a beat`() {
        val source = track("src", bpm = 120.0, firstBeat = 0.0)
        val target = track("dst", bpm = 120.0, firstBeat = 0.1)
        val alignment = requireNotNull(BeatSync.align(source, target, atSeconds = 8.0))
        assertEquals(0.1, alignment.phaseOffsetSeconds, 1e-6)
        assertTrue(abs(alignment.phaseOffsetSeconds) <= 0.25 + 1e-9)
    }

    @Test
    fun `phase correction takes the short way round`() {
        // 0.45 s out at 120 BPM (0.5 s per beat) is better corrected as -0.05.
        val source = track("src", bpm = 120.0, firstBeat = 0.0)
        val target = track("dst", bpm = 120.0, firstBeat = 0.45)
        val alignment = requireNotNull(BeatSync.align(source, target, atSeconds = 4.0))
        assertTrue(
            "should correct backwards, was ${alignment.phaseOffsetSeconds}",
            abs(alignment.phaseOffsetSeconds) <= 0.25 + 1e-9,
        )
    }

    @Test
    fun `no phase correction without a measured beat phase`() {
        val alignment = requireNotNull(
            BeatSync.align(track("src", firstBeat = null), track("dst", firstBeat = 0.2)),
        )
        assertEquals(0.0, alignment.phaseOffsetSeconds, 0.0)
    }

    @Test
    fun `semitone shift moves toward the target key by the short way`() {
        // 8A is A minor, 9A is E minor: a perfect fifth, seven semitones up or
        // five down, so the short way is -5.
        val shift = BeatSync.semitoneShift(track("src", camelot = "8A"), track("dst", camelot = "9A"))
        assertTrue("shift $shift should be the short way round", shift in -6..6)
        assertEquals("9A", HarmonicEngine.transposeCamelotKey("8A", shift))
    }

    @Test
    fun `no pitch shift when either key is unmeasured`() {
        assertEquals(0, BeatSync.semitoneShift(track("a", camelot = null), track("b", camelot = "8A")))
        assertEquals(0, BeatSync.semitoneShift(track("a", camelot = "8A"), track("b", camelot = null)))
    }

    @Test
    fun `grid helpers snap to beats and bars`() {
        val subject = track("t", bpm = 120.0, firstBeat = 0.0)
        assertEquals(0.5, requireNotNull(BeatSync.nearestBeat(subject, 0.6)), 1e-9)
        assertEquals(2.0, requireNotNull(BeatSync.nearestBar(subject, 1.4)), 1e-9)
        assertEquals(2.0, requireNotNull(BeatSync.barDurationSeconds(subject)), 1e-9)
        assertEquals(8.0, requireNotNull(BeatSync.barDurationSeconds(subject, bars = 4)), 1e-9)
    }

    @Test
    fun `grid helpers decline without a measured grid`() {
        val unmeasured = track("t", bpm = null, firstBeat = null)
        assertNull(BeatSync.nearestBeat(unmeasured, 1.0))
        assertNull(BeatSync.nearestBar(unmeasured, 1.0))
        assertNull(BeatSync.barDurationSeconds(unmeasured))
        assertNull(BeatSync.barDurationSeconds(track("t", bpm = 120.0), bars = 0))
    }

    @Test
    fun `syncability follows the six percent convention`() {
        assertTrue(BeatSync.canSyncWithoutArtefacts(track("a", bpm = 128.0), track("b", bpm = 131.0)))
        assertTrue(!BeatSync.canSyncWithoutArtefacts(track("a", bpm = 128.0), track("b", bpm = 145.0)))
        assertTrue(!BeatSync.canSyncWithoutArtefacts(track("a", bpm = null), track("b", bpm = 128.0)))
    }

    @Test
    fun `tempo change is reported as a readable percentage`() {
        val alignment = requireNotNull(
            BeatSync.align(track("src", bpm = 100.0), track("dst", bpm = 104.0)),
        )
        assertEquals(4.0, BeatSync.tempoChangePercent(alignment), 1e-9)
        assertEquals(4, BeatSync.roundedPercent(alignment))
    }
}
