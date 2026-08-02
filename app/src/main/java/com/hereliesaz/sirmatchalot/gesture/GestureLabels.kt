package com.hereliesaz.sirmatchalot.gesture

/**
 * A label being shown for an active or fading gesture.
 *
 * @param slot index into [GestureLabels.CLOCK_SLOTS].
 * @param text the gesture's name. Text only — no box, no background.
 * @param alpha 1 while the gesture runs, falling to 0 once it ends.
 * @param riseFraction how far the label has floated upward, 0..1 and beyond.
 */
data class GestureLabel(
    val slot: Int,
    val text: String,
    val alpha: Float,
    val riseFraction: Float,
)

/**
 * Places gesture names at clock positions around the platter and animates them.
 *
 * The rules, all specified explicitly:
 *
 * - Priority order is 12:00 first, then 1:30, 10:30, 3:00, 9:00, 4:30, 7:30, and
 *   6:00 last.
 * - A label appears the moment its gesture starts and begins dissolving the
 *   moment the gesture ends — not when the finger lifts.
 * - A slot is only reusable once its previous label has finished dissolving.
 * - If a gesture recurs while its own label is still fading, it reclaims that
 *   slot rather than taking a new one.
 * - Labels float upward from the moment they appear, so a fast sequence of
 *   gestures cannot build a backlog.
 *
 * Driven by the frame clock. The previous implementation ran an unconditional
 * 16 ms `while(true)` loop for the lifetime of the screen whether or not any
 * label was visible, and never actually dissolved them.
 */
class GestureLabels(
    private val fadeDurationMillis: Long = 500L,
    private val riseDurationMillis: Long = 1_400L,
) {
    private class Slot {
        var text: String? = null
        var active = false
        var alpha = 1f
        var riseFraction = 0f

        /** When the label appeared, so the rise is measured in time. */
        var startedAt: Long = 0L
        /** Set when the gesture ends; null while it is still running. */
        var fadeStartedAt: Long? = null

        val free: Boolean get() = text == null

        fun clear() {
            text = null
            active = false
            alpha = 1f
            riseFraction = 0f
            startedAt = 0L
            fadeStartedAt = null
        }
    }

    private val slots = Array(CLOCK_SLOTS.size) { Slot() }

    /**
     * Reconciles the visible labels with the currently active gestures and
     * advances their animations.
     *
     * @param activeTexts names of the gestures running right now.
     * @param nowMillis a monotonic clock, passed in so this is testable.
     */
    fun update(activeTexts: Set<String>, nowMillis: Long) {
        // Any label whose gesture has stopped begins dissolving immediately.
        for (slot in slots) {
            val text = slot.text ?: continue
            val stillRunning = text in activeTexts
            if (stillRunning) {
                // Reclaims its own slot even if it was mid-dissolve.
                slot.active = true
                slot.fadeStartedAt = null
                slot.alpha = 1f
            } else if (slot.active) {
                slot.active = false
                slot.fadeStartedAt = nowMillis
            }
        }

        // New gestures take the highest-priority slot that is genuinely free —
        // a slot still finishing a dissolve is not free.
        val shown = slots.mapNotNull { it.text }.toSet()
        for (text in activeTexts) {
            if (text in shown) continue
            val slot = slots.firstOrNull { it.free } ?: continue
            slot.text = text
            slot.active = true
            slot.alpha = 1f
            slot.riseFraction = 0f
            slot.startedAt = nowMillis
            slot.fadeStartedAt = null
        }

        advance(nowMillis)
    }

    /** Advances fade and rise, releasing slots whose labels have gone. */
    private fun advance(nowMillis: Long) {
        for (slot in slots) {
            if (slot.text == null) continue

            val fadeStart = slot.fadeStartedAt
            if (fadeStart != null) {
                val elapsed = (nowMillis - fadeStart).coerceAtLeast(0L)
                slot.alpha = 1f - elapsed.toFloat() / fadeDurationMillis
                if (slot.alpha <= 0f) {
                    slot.clear()
                    continue
                }
            }

            // Rising continues whether active or fading, until gone or offscreen.
            //
            // Measured against the clock, like the fade directly above it. This
            // was `RISE_PER_MILLI * FRAME_MILLIS` with FRAME_MILLIS hardcoded at
            // 16, advanced once per `update` — so the rise ran on the frame
            // *count*, not on time: twice as fast on a 120 Hz display, and at a
            // crawl at the app's lower visual-refresh settings, while the fade
            // beside it stayed correct. The two halves of one animation ran on
            // two different clocks. `riseDurationMillis` — a constructor
            // parameter — was read by nothing at all.
            val risen = (nowMillis - slot.startedAt).coerceAtLeast(0L)
            slot.riseFraction = risen.toFloat() / riseDurationMillis
            if (slot.riseFraction >= 1f && !slot.active) {
                slot.clear()
            }
        }
    }

    /** Labels to draw. */
    fun visible(): List<GestureLabel> =
        slots.mapIndexedNotNull { index, slot ->
            slot.text?.let {
                GestureLabel(index, it, slot.alpha.coerceIn(0f, 1f), slot.riseFraction)
            }
        }

    /** True when nothing is showing, so the caller can stop animating entirely. */
    fun isIdle(): Boolean = slots.all { it.free }

    fun reset() = slots.forEach { it.clear() }

    companion object {
        /**
         * Clock positions in priority order, as fractions of a revolution from
         * 12 o'clock: 12:00, 1:30, 10:30, 3:00, 9:00, 4:30, 7:30, 6:00.
         *
         * 6:00 is last, being the least favoured position.
         */
        val CLOCK_SLOTS = floatArrayOf(
            0f / 8f,  // 12:00
            1f / 8f,  // 1:30
            7f / 8f,  // 10:30
            2f / 8f,  // 3:00
            6f / 8f,  // 9:00
            3f / 8f,  // 4:30
            5f / 8f,  // 7:30
            4f / 8f,  // 6:00
        )

        /** Human-readable names, matching [CLOCK_SLOTS] order. */
        val CLOCK_NAMES = arrayOf(
            "12:00", "1:30", "10:30", "3:00", "9:00", "4:30", "7:30", "6:00",
        )

    }
}
