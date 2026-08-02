package com.hereliesaz.sirmatchalot.domain

/**
 * The shape of a set over its whole length.
 *
 * A mix that chooses each transition purely on what the two adjacent tracks are
 * doing has no arc: it is locally sensible and globally shapeless, which is the
 * other half of sounding robotic. A set has somewhere it is going. The opening
 * is patient, the middle climbs, the peak is bold and quick, and the tail lets
 * go.
 *
 * The phase is what stops "varied" from meaning "random": it is the reason a
 * long patient blend belongs at track two and a hard cut belongs at track seven,
 * rather than either landing wherever the dice fell.
 *
 * Derived from position and from the planned energy curve together. Position
 * alone ignores what was actually planned; energy alone is noisy, because
 * `energyLevel` is a single 1..10 average and a quiet intro can drag a loud
 * track's figure down.
 */
class SetArc private constructor(private val phases: List<Phase>) {

    val size: Int get() = phases.size

    /** Phase of step [index]; [Phase.BUILD] for anything out of range. */
    fun phaseAt(index: Int): Phase = phases.getOrElse(index) { Phase.BUILD }

    /** Index of the loudest planned moment, or -1 for an empty set. */
    val peakIndex: Int get() = phases.indexOf(Phase.PEAK)

    enum class Phase {
        /** Settle in. Long blends, whole tracks, nothing clever. */
        OPENING,

        /** Climbing. Blends shorten; the first bold moves appear. */
        BUILD,

        /** The top. Short, bold, cut hard, cut early. */
        PEAK,

        /** Coming down. Long again, but with the material now established. */
        RELEASE,
    }

    companion object {
        /** Below this many tracks a set has no room for an arc. */
        const val MINIMUM_FOR_ARC = 4

        /**
         * Where the peak sits when the energy levels cannot say.
         *
         * Two thirds through: late enough that the middle has somewhere to climb
         * to, early enough to leave a release that is more than one track.
         */
        const val NOMINAL_PEAK_FRACTION = 0.66

        /**
         * Energy spread below which a plan is treated as having no shape.
         *
         * `energyLevel` is an integer 1..10, so anything under a whole level of
         * spread across the whole set is noise from the smoothing rather than an
         * arc anyone would hear.
         */
        const val FLAT_ENERGY_RANGE = 1.0

        /**
         * Reads the arc out of a planned running order.
         *
         * The peak is placed where the plan actually peaks rather than at a
         * fixed fraction — [MixPlanner.automatchicMix] builds by energy, so the
         * loudest stretch is where it put it, and overriding that with two
         * thirds of the way through would fight the plan it is describing.
         */
        fun of(steps: List<MixStep>): SetArc {
            if (steps.isEmpty()) return SetArc(emptyList())
            if (steps.size < MINIMUM_FOR_ARC) {
                // Too short to shape. Everything is the build; a three-track set
                // labelled opening/peak/release would be a caption, not an arc.
                return SetArc(List(steps.size) { Phase.BUILD })
            }

            val energies = steps.map { (it.track.energyLevel ?: 5).toDouble() }
            // Smoothed, so one loud track in a quiet stretch is not "the peak".
            val smoothed = energies.indices.map { i ->
                val from = maxOf(0, i - 1)
                val to = minOf(energies.lastIndex, i + 1)
                (from..to).sumOf { energies[it] } / (to - from + 1)
            }
            val measured = smoothed.indices.maxByOrNull { smoothed[it] } ?: 0
            // How long the opening wants to be, before the peak has a say.
            val opening = maxOf(1, steps.size / 5)

            // Energies that do not vary cannot locate a peak, and `maxByOrNull`
            // answers index 0 rather than admitting it. That zeroed the opening
            // (`peak - 1` clamps to 0) and put `peakStart` at 0 with it, so a
            // library whose tracks all land in the same bucket of
            // `EnergyCurve.energyLevel` — a genre-consistent one, which is
            // ordinary — got a set that is PEAK from the first track and
            // RELEASE thereafter. No opening and no build at all, against a
            // header that says the opening is patient and the middle climbs, and
            // downstream that is `playFraction = 0.55` and `boldness = 2.2` from
            // track one.
            //
            // Only the flat case is overridden. A plan that genuinely front-loads
            // its energy keeps its early peak: the arc describes the plan, it
            // does not argue with it.
            val flat = (smoothed.max() - smoothed.min()) < FLAT_ENERGY_RANGE
            val peak = if (flat) ((steps.size - 1) * NOMINAL_PEAK_FRACTION).toInt() else measured

            // The opening is the first stretch, never more than a quarter of the
            // set and never past the peak.
            val openingEnd = minOf(opening, maxOf(0, peak - 1))
            // The peak is a stretch rather than an instant: one transition at the
            // top is not a peak anybody would notice.
            val peakSpan = maxOf(1, steps.size / 6)
            val peakStart = maxOf(openingEnd, peak - peakSpan / 2)
            val peakEnd = minOf(steps.lastIndex, peakStart + peakSpan)

            return SetArc(
                List(steps.size) { i ->
                    when {
                        i < openingEnd -> Phase.OPENING
                        i in peakStart..peakEnd -> Phase.PEAK
                        i < peakStart -> Phase.BUILD
                        else -> Phase.RELEASE
                    }
                },
            )
        }

        /** An arc for a set nothing is known about — used where no plan exists. */
        fun flat(size: Int) = SetArc(List(size) { Phase.BUILD })
    }
}
