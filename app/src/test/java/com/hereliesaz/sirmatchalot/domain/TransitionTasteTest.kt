package com.hereliesaz.sirmatchalot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Learning what one person likes.
 *
 * The properties worth pinning are not "does it converge" — a logistic
 * regression on seven features converges — but the ones that decide whether it
 * is safe to let this touch a live set:
 *
 * - it is **exactly** a no-op until trained, so a fresh install is the rules;
 * - it never silences a style the material calls for, however unpopular;
 * - it needs evidence before it commits, and more before it says so out loud;
 * - a corrupt saved model degrades to an untrained one rather than to NaN.
 */
class TransitionTasteTest {

    private fun record(
        style: TransitionStyle = TransitionStyle.CUT,
        exit: TrackStructure.Exit.Reason = TrackStructure.Exit.Reason.BREAKDOWN,
        phase: SetArc.Phase = SetArc.Phase.BUILD,
        score: Int? = 80,
        energyDelta: Int = 1,
        fadeBars: Double = 8.0,
        entered: Boolean = false,
    ) = TransitionRecord(style, exit, phase, score, energyDelta, fadeBars, entered).features()

    // --- Doing nothing, correctly ---

    @Test
    fun `an untrained model has no opinion at all`() {
        val taste = TransitionTaste()
        assertEquals(0.5, taste.approvalOf(record()), 1e-12)
        assertEquals(1.0, taste.influence(record()), 1e-12)
        assertEquals(0, taste.observations)
    }

    @Test
    fun `an untrained model leaves the choreographer exactly as it was`() {
        // The guarantee that makes this safe to ship on by default: with nothing
        // learned, every style weight is multiplied by one, so the set is the
        // set the rules produce.
        val from = TrackStructure(
            "a", 300.0, 120.0,
            points = listOf(
                com.hereliesaz.sirmatchalot.dsp.PointOfInterest(
                    150.0, com.hereliesaz.sirmatchalot.dsp.PointOfInterest.Kind.BREAKDOWN, 1f,
                ),
            ),
        )
        val to = TrackStructure("b", 300.0, 120.0)
        val choreographer = TransitionChoreographer()

        for (seed in 0L until 50L) {
            val without = choreographer.choreograph(from, to, null, SetArc.Phase.BUILD, Random(seed))
            val with = choreographer.choreograph(
                from, to, null, SetArc.Phase.BUILD, Random(seed), taste = TransitionTaste(),
            )
            assertEquals("seed $seed diverged", without, with)
        }
    }

    // --- Learning ---

    @Test
    fun `approval rises for what was kept and falls for what was cut`() {
        val taste = TransitionTaste()
        val cut = record(style = TransitionStyle.CUT)
        val blend = record(style = TransitionStyle.LONG_BLEND)

        repeat(60) {
            taste.learn(cut, 0.0)
            taste.learn(blend, 1.0)
        }

        assertTrue("cuts should be unpopular", taste.approvalOf(cut) < 0.4)
        assertTrue("blends should be popular", taste.approvalOf(blend) > 0.6)
        assertEquals(120, taste.observations)
    }

    @Test
    fun `an unpopular style is discouraged but never silenced`() {
        // The most important property here. Someone who has skipped every cut
        // for a month should hear fewer cuts — not zero, because a cut the
        // music genuinely calls for is still the right move, and a set that
        // stops responding to the music has stopped being a mix.
        val taste = TransitionTaste()
        val cut = record(style = TransitionStyle.CUT)
        repeat(500) { taste.learn(cut, 0.0) }

        val influence = taste.influence(cut)
        assertTrue("$influence should discourage", influence < 1.0)
        assertTrue("$influence must stay positive", influence > 0.0)
    }

    @Test
    fun `influence is bounded however lopsided the evidence`() {
        val taste = TransitionTaste()
        val loved = record(style = TransitionStyle.BASS_SWAP)
        repeat(2_000) { taste.learn(loved, 1.0) }

        val influence = taste.influence(loved)
        assertTrue("$influence exceeded the ceiling", influence <= 1.0 + TransitionTaste.MAXIMUM_SWING)
        assertTrue("$influence should encourage", influence > 1.0)
    }

    @Test
    fun `one evening does not become a rule`() {
        // Confidence scales with evidence, so a handful of skips nudges where a
        // season of them decides.
        val fresh = TransitionTaste()
        val seasoned = TransitionTaste()
        val cut = record(style = TransitionStyle.CUT)

        repeat(3) { fresh.learn(cut, 0.0) }
        repeat(400) { seasoned.learn(cut, 0.0) }

        val nudge = 1.0 - fresh.influence(cut)
        val verdict = 1.0 - seasoned.influence(cut)
        assertTrue("$nudge should be far smaller than $verdict", nudge * 3 < verdict)
    }

    @Test
    fun `later evidence refines rather than overwrites`() {
        // The step decays with what has been seen, so the thousandth example
        // cannot undo everything the first nine hundred established.
        val taste = TransitionTaste()
        val cut = record(style = TransitionStyle.CUT)
        repeat(300) { taste.learn(cut, 0.0) }
        val settled = taste.approvalOf(cut)

        taste.learn(cut, 1.0)
        assertTrue("one dissent moved it too far", kotlin.math.abs(taste.approvalOf(cut) - settled) < 0.05)
    }

    @Test
    fun `a label out of range is treated as the nearest verdict`() {
        val taste = TransitionTaste()
        repeat(30) { taste.learn(record(), 7.0) }
        assertTrue(taste.approvalOf(record()) > 0.5)
        assertTrue(taste.approvalOf(record()).isFinite())
    }

    // --- Saying what it learned ---

    @Test
    fun `nothing is claimed before there is evidence for it`() {
        val taste = TransitionTaste()
        repeat(TransitionTaste.MINIMUM_TO_REPORT - 1) {
            taste.learn(record(style = TransitionStyle.CUT), 0.0)
        }
        assertTrue("claimed a preference from nothing", taste.strongestOpinions().isEmpty())
    }

    @Test
    fun `a learned preference can be said in English`() {
        val taste = TransitionTaste()
        repeat(120) { taste.learn(record(style = TransitionStyle.CUT), 0.0) }

        val opinions = taste.strongestOpinions()
        assertTrue("said nothing: $opinions", opinions.isNotEmpty())
        assertTrue("expected a word about cuts: $opinions", opinions.any { it.contains("cut") })
    }

    @Test
    fun `every feature has something to say about itself`() {
        // A weight with no sentence attached is a weight nobody can argue with.
        for (index in 0 until TransitionFeatures.COUNT) {
            for (weight in listOf(-1.0, 1.0)) {
                val said = TransitionFeatures.describe(index, weight)
                assertTrue("feature $index is unnamed", said.isNotBlank())
                assertNotEquals("feature $index is unnamed", "unnamed preference", said)
            }
        }
    }

    // --- Surviving storage ---

    @Test
    fun `a model round-trips through the settings store`() {
        val taste = TransitionTaste()
        repeat(50) {
            taste.learn(record(style = TransitionStyle.CUT), 0.0)
            taste.learn(record(style = TransitionStyle.BASS_SWAP), 1.0)
        }

        val restored = TransitionTaste.decode(taste.encode())
        assertEquals(taste.observations, restored.observations)
        assertEquals(
            taste.approvalOf(record(style = TransitionStyle.CUT)),
            restored.approvalOf(record(style = TransitionStyle.CUT)),
            1e-5,
        )
    }

    @Test
    fun `an unreadable model degrades to an untrained one`() {
        // Never to NaN: a saved model full of NaN poisons every prediction
        // afterwards and nothing ever says why.
        for (bad in listOf(null, "", "nonsense", "1,2,3", "4,NaN,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0")) {
            val decoded = TransitionTaste.decode(bad)
            assertEquals("'$bad' survived", 0, decoded.observations)
            assertEquals(0.5, decoded.approvalOf(record()), 1e-12)
        }
    }

    @Test
    fun `a model saved by a version with different features is discarded`() {
        // The encoding is positional, so a vector of the wrong length would
        // silently reinterpret every weight as belonging to another feature.
        val wrongLength = (0..TransitionFeatures.COUNT + 4).joinToString(",") { "0.1" }
        assertEquals(0, TransitionTaste.decode(wrongLength).observations)
    }

    @Test
    fun `forgetting really forgets`() {
        val taste = TransitionTaste()
        repeat(200) { taste.learn(record(style = TransitionStyle.CUT), 0.0) }
        taste.reset()

        assertEquals(0, taste.observations)
        assertEquals(0.5, taste.approvalOf(record()), 1e-12)
        assertEquals(1.0, taste.influence(record()), 1e-12)
        assertTrue(taste.strongestOpinions().isEmpty())
    }

    // --- Features ---

    @Test
    fun `a record encodes to the fixed layout`() {
        val features = record()
        assertEquals(TransitionFeatures.COUNT, features.size)
        assertTrue("nothing is one-hot", features.any { it == 1.0 })
        assertTrue("values should be roughly bounded", features.all { kotlin.math.abs(it) <= 2.0 })
    }

    @Test
    fun `an unmeasured match reads as neutral rather than as zero`() {
        // Zero is a real score meaning "these clash". Absent is not that.
        val unmeasured = TransitionRecord(
            TransitionStyle.LONG_BLEND, TrackStructure.Exit.Reason.TRACK_END,
            SetArc.Phase.BUILD, null, 0, 8.0, false,
        ).features()
        val neutral = TransitionRecord(
            TransitionStyle.LONG_BLEND, TrackStructure.Exit.Reason.TRACK_END,
            SetArc.Phase.BUILD, 50, 0, 8.0, false,
        ).features()
        assertTrue(unmeasured.contentEquals(neutral))
    }

    @Test
    fun `different choices encode differently`() {
        assertTrue(
            !record(style = TransitionStyle.CUT).contentEquals(record(style = TransitionStyle.LOOP_ROLL)),
        )
        assertTrue(
            !record(exit = TrackStructure.Exit.Reason.BREAKDOWN)
                .contentEquals(record(exit = TrackStructure.Exit.Reason.TRACK_END)),
        )
        assertTrue(!record(entered = false).contentEquals(record(entered = true)))
    }
}
