package com.hereliesaz.sirmatchalot.domain

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * What the app has learned about how *this* person likes their transitions.
 *
 * [TransitionChoreographer] weights its styles by rules — a breakdown handoff is
 * worth 2.5, a reverse slam is peak-only, and so on. Those numbers are a guess at
 * a general listener, and there is no general listener. Someone who never wants
 * to hear a record cut has no way to say so except by reaching for the fader
 * every time one happens, and until now nothing was listening when they did.
 *
 * ## Why this is a logistic regression and not a network
 *
 * Because the problem is small and the data is expensive. A set produces a few
 * dozen transitions a night, each labelled by one bit — did they let it run, or
 * did they intervene. Against that, [TransitionFeatures] is a handful of numbers.
 * Anything with hidden layers would memorise the first evening and be confidently
 * wrong for the rest of them.
 *
 * What a linear model buys, beyond fitting the data it actually has:
 *
 * - **It trains in microseconds** on the device, in the audio app's own process,
 *   with no export, no upload and no model file. The update below is four lines.
 * - **It stays legible.** Every weight is attached to a named feature, so
 *   [strongestOpinions] can say "you skip cuts" in English rather than showing a
 *   confidence score nobody can act on. A recommendation you cannot interrogate
 *   is indistinguishable from a bug.
 * - **It cannot run away.** Predictions are folded into the existing rule weights
 *   as a bounded multiplier, so a model trained on four transitions nudges, and
 *   one trained on four hundred decides. The rules remain the floor.
 *
 * Nothing here leaves the device, and there is nothing to leave: a few dozen
 * doubles in the app's own preferences.
 */
class TransitionTaste(
    weights: DoubleArray = DoubleArray(TransitionFeatures.COUNT),
    bias: Double = 0.0,
    observations: Int = 0,
) {
    private val weights: DoubleArray = weights.copyOf(TransitionFeatures.COUNT)

    var bias: Double = bias
        private set

    /** How many transitions have been learned from. */
    var observations: Int = observations
        private set

    /** A copy of the current weights, for display and for saving. */
    fun weights(): DoubleArray = weights.copyOf()

    /**
     * How likely this person is to let a transition like this one run, 0..1.
     *
     * Exactly 0.5 for an untrained model, which is what makes [influence] a no-op
     * until there is something to say.
     */
    fun approvalOf(features: DoubleArray): Double {
        var sum = bias
        for (i in 0 until minOf(features.size, weights.size)) sum += weights[i] * features[i]
        return 1.0 / (1.0 + exp(-sum))
    }

    /**
     * A multiplier on a style's rule weight, given what has been learned.
     *
     * Deliberately not the raw probability. Three properties matter more than
     * fidelity here:
     *
     * - it is **1.0 while untrained**, so a fresh install behaves exactly as the
     *   rules alone did, and the rules stay the thing being adjusted rather than
     *   replaced;
     * - it **grows with evidence** — [CONFIDENCE_AT] transitions in, the model is
     *   trusted about half as far as it eventually will be — so one unlucky
     *   evening cannot teach it a lesson it keeps;
     * - it is **bounded** either side of 1, so a strong opinion re-orders the
     *   candidates without ever driving a style's weight to zero. A style the
     *   material genuinely calls for must stay reachable, or the set stops
     *   responding to the music and starts only responding to habit.
     */
    fun influence(features: DoubleArray): Double {
        if (observations <= 0) return 1.0
        val confidence = observations.toDouble() / (observations + CONFIDENCE_AT)
        val opinion = (approvalOf(features) - 0.5) * 2.0
        return (1.0 + opinion * confidence * MAXIMUM_SWING).coerceIn(
            1.0 / (1.0 + MAXIMUM_SWING),
            1.0 + MAXIMUM_SWING,
        )
    }

    /**
     * Learns from one transition.
     *
     * Plain stochastic gradient descent on the log-loss, with a decaying rate so
     * later evenings refine rather than overwrite. `label` is 1.0 when the
     * transition was allowed to play out and 0.0 when it was interrupted.
     */
    fun learn(features: DoubleArray, label: Double, rate: Double = LEARNING_RATE) {
        val target = label.coerceIn(0.0, 1.0)
        val error = target - approvalOf(features)
        // Decayed by how much has been seen, so the hundredth example moves the
        // model less than the first. Without this the model tracks the last few
        // transitions and calls it taste.
        val step = rate / (1.0 + observations.toDouble() / CONFIDENCE_AT)
        for (i in 0 until minOf(features.size, weights.size)) {
            // L2 pull toward zero, so a feature that stops mattering fades out
            // instead of keeping whatever it learned in the first week.
            weights[i] += step * (error * features[i] - DECAY * weights[i])
        }
        bias += step * error
        observations++
    }

    /**
     * The clearest things that can be said about this person's taste, in English.
     *
     * The point of a linear model is that this method can exist. Each weight
     * belongs to one named feature, so a preference is reportable rather than
     * merely operative — and a claim the user can read is a claim they can
     * disagree with.
     *
     * @return at most [limit] statements, strongest first, or empty when there is
     *   not yet enough to say anything honest.
     */
    fun strongestOpinions(limit: Int = 3): List<String> {
        if (observations < MINIMUM_TO_REPORT) return emptyList()
        return weights.indices
            .filter { abs(weights[it]) >= REPORTABLE_WEIGHT }
            .sortedByDescending { abs(weights[it]) }
            .take(limit)
            .map { TransitionFeatures.describe(it, weights[it]) }
    }

    /**
     * Forgets everything, in place.
     *
     * In place rather than by handing back a new instance, because a set that is
     * already running holds a reference to this one. Someone who clears the model
     * mid-mix means it now, not at the next track.
     */
    fun reset() {
        weights.fill(0.0)
        bias = 0.0
        observations = 0
    }

    /** The weights as a string, for the settings store. */
    fun encode(): String =
        (listOf(observations.toString(), format(bias)) + weights.map(::format)).joinToString(",")

    companion object {
        /**
         * Observations at which the model is trusted half as far as it ever will be.
         *
         * A night's worth. Low enough that a regular user sees the app adapt
         * inside a couple of sessions; high enough that three unlucky skips in
         * one evening do not become a rule.
         */
        const val CONFIDENCE_AT = 40.0

        /** How far a fully-trained opinion may move a rule weight, either way. */
        const val MAXIMUM_SWING = 1.5

        const val LEARNING_RATE = 0.35

        /** L2 pull toward zero per step. */
        const val DECAY = 0.002

        /** Below this, any claim about someone's taste is noise with a font. */
        const val MINIMUM_TO_REPORT = 12

        const val REPORTABLE_WEIGHT = 0.25

        // Locale.ROOT, not the default: String.format with no locale renders
        // the decimal point as a comma on plenty of real devices, which broke
        // the field count decode() expects and reset this model to zero on
        // every launch for anyone on such a locale.
        private fun format(value: Double) = String.format(Locale.ROOT, "%.6f", value)

        /** Reads back [encode], returning a fresh model on anything unparseable. */
        fun decode(encoded: String?): TransitionTaste {
            val parts = encoded?.split(',') ?: return TransitionTaste()
            if (parts.size != TransitionFeatures.COUNT + 2) return TransitionTaste()
            val numbers = parts.drop(1).map { it.trim().toDoubleOrNull() ?: return TransitionTaste() }
            val count = parts[0].trim().toIntOrNull()?.takeIf { it >= 0 } ?: return TransitionTaste()
            // A saved model full of NaN would poison every prediction silently.
            if (numbers.any { !it.isFinite() }) return TransitionTaste()
            return TransitionTaste(
                weights = numbers.drop(1).toDoubleArray(),
                bias = numbers.first(),
                observations = count,
            )
        }
    }
}

/**
 * What was chosen for one handover, kept so it can be learned from later.
 *
 * The label arrives well after the decision — a transition is approved by being
 * allowed to finish, and disapproved by the user reaching for the fader — so the
 * decision has to be carried until the verdict is in.
 *
 * A value with named fields rather than the raw feature vector, for two reasons:
 * it survives `assertEquals` where a `DoubleArray` would compare by identity, and
 * it can be read. A record of what happened that a person can read is worth more
 * than one only the model can.
 */
data class TransitionRecord(
    val style: TransitionStyle,
    val exit: TrackStructure.Exit.Reason,
    val phase: SetArc.Phase,
    val matchScore: Int?,
    val energyDelta: Int,
    val fadeBars: Double,
    val enteredMidTrack: Boolean,
) {
    fun features(): DoubleArray = TransitionFeatures.of(
        style = style,
        exit = exit,
        phase = phase,
        matchScore = matchScore,
        energyDelta = energyDelta,
        fadeBars = fadeBars,
        entered = enteredMidTrack,
    )
}

/**
 * One transition, as numbers a model can learn from.
 *
 * The features are the choreographer's own vocabulary — the style it chose, the
 * exit it left on, how well the two tracks matched, where in the set it happened
 * — because those are exactly the terms in which a preference is worth holding.
 * "You skip cuts between tracks that barely match" is actionable; a weight on an
 * anonymous embedding dimension is not.
 *
 * A fixed layout, one-hot for the categorical parts. Fixed because the encoding
 * is persisted: adding a feature in the middle would silently reinterpret every
 * saved weight, so new features go on the end and [TransitionTaste.decode]
 * discards a model whose length no longer matches.
 */
object TransitionFeatures {

    private val STYLES = TransitionStyle.entries
    private val EXITS = TrackStructure.Exit.Reason.entries

    /** Where each block of the vector starts. */
    private val STYLE_AT = 0
    private val EXIT_AT = STYLE_AT + STYLES.size
    private val PHASE_AT = EXIT_AT + EXITS.size
    private val SCORE_AT = PHASE_AT + SetArc.Phase.entries.size
    private val ENERGY_AT = SCORE_AT + 1
    private val FADE_AT = ENERGY_AT + 1
    private val ENTRY_AT = FADE_AT + 1

    /** Length of a feature vector. */
    val COUNT = ENTRY_AT + 1

    /**
     * @param matchScore 0..100 harmonic compatibility, or null when unmeasured.
     * @param energyDelta incoming energy minus outgoing, on the 1..10 scale.
     * @param fadeBars length of the transition in bars of the outgoing track.
     * @param entered true when the incoming track came in somewhere other than
     *   its own beginning.
     */
    fun of(
        style: TransitionStyle,
        exit: TrackStructure.Exit.Reason,
        phase: SetArc.Phase,
        matchScore: Int?,
        energyDelta: Int,
        fadeBars: Double,
        entered: Boolean,
    ): DoubleArray {
        val features = DoubleArray(COUNT)
        features[STYLE_AT + STYLES.indexOf(style)] = 1.0
        features[EXIT_AT + EXITS.indexOf(exit)] = 1.0
        features[PHASE_AT + SetArc.Phase.entries.indexOf(phase)] = 1.0
        // Centred and scaled to roughly -1..1, so no single feature dominates the
        // gradient purely by being measured in larger units.
        features[SCORE_AT] = ((matchScore ?: 50) - 50) / 50.0
        features[ENERGY_AT] = (energyDelta.coerceIn(-9, 9)) / 9.0
        features[FADE_AT] = (ln(fadeBars.coerceAtLeast(0.5)) - ln(8.0)) / ln(8.0)
        features[ENTRY_AT] = if (entered) 1.0 else 0.0
        return features
    }

    /** A weight, said in English. */
    fun describe(index: Int, weight: Double): String {
        val likes = weight > 0
        return when (index) {
            in STYLE_AT until EXIT_AT -> {
                val style = STYLES[index - STYLE_AT].label.lowercase()
                if (likes) "keeps $style transitions" else "skips $style transitions"
            }

            in EXIT_AT until PHASE_AT -> {
                val exit = when (EXITS[index - EXIT_AT]) {
                    TrackStructure.Exit.Reason.BREAKDOWN -> "leaving on a breakdown"
                    TrackStructure.Exit.Reason.AFTER_DROP -> "leaving after a drop"
                    TrackStructure.Exit.Reason.LOOP_END -> "leaving at the end of a loop"
                    TrackStructure.Exit.Reason.TRACK_END -> "playing tracks to the end"
                }
                if (likes) "likes $exit" else "dislikes $exit"
            }

            in PHASE_AT until SCORE_AT -> {
                val phase = SetArc.Phase.entries[index - PHASE_AT].name.lowercase()
                if (likes) "happy with the $phase of a set" else "restless in the $phase of a set"
            }

            SCORE_AT ->
                if (likes) "wants tracks that match closely" else "tolerates a loose match"

            ENERGY_AT ->
                if (likes) "wants the energy to climb" else "prefers the energy held or eased"

            FADE_AT ->
                if (likes) "prefers long transitions" else "prefers short transitions"

            ENTRY_AT ->
                if (likes) "likes coming in mid-track" else "prefers tracks from the top"

            else -> "unnamed preference"
        }
    }
}
