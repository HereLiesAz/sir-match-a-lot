package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.floor

/**
 * Fractional-position sample reading.
 *
 * The playhead is a `Double` sample index advanced by a **signed** rate, which
 * is what makes reverse playback and scratching possible: a negative rate reads
 * backwards, and because the read is interpolated rather than quantised, the
 * signal stays continuous as the rate passes through zero. There is no seek and
 * no discontinuity — the same code path covers 1.0x, 0.02x, 0.0, and -1.7x.
 */
object Resampler {

    /**
     * Catmull-Rom interpolated read of [source] at fractional [position].
     *
     * Returns 0 for positions outside the buffer. Positions within the buffer
     * but lacking neighbours (the first and last sample) clamp to the edge
     * sample, so there is no click at the boundaries.
     */
    fun read(source: FloatArray, position: Double): Float {
        val n = source.size
        if (n == 0) return 0f
        if (position < 0.0 || position > (n - 1).toDouble()) return 0f

        val i1 = floor(position).toInt()
        val t = (position - i1).toFloat()
        if (t == 0f) return source[i1]

        val p0 = source[if (i1 - 1 < 0) 0 else i1 - 1]
        val p1 = source[i1]
        val p2 = source[if (i1 + 1 >= n) n - 1 else i1 + 1]
        val p3 = source[if (i1 + 2 >= n) n - 1 else i1 + 2]

        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (
            2f * p1 +
                (-p0 + p2) * t +
                (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                (-p0 + 3f * p1 - 3f * p2 + p3) * t3
            )
    }

    /**
     * Renders [count] samples from [source] into [out] starting at [outOffset],
     * advancing the playhead by [rate] source samples per output sample.
     *
     * [rate] may be negative (reverse) or zero (hold silence — the playhead
     * stops but the read position is preserved). Once the playhead leaves the
     * buffer the remaining output is silence.
     *
     * @return the playhead position after the block.
     */
    fun render(
        source: FloatArray,
        startPosition: Double,
        rate: Double,
        out: FloatArray,
        outOffset: Int = 0,
        count: Int = out.size - outOffset,
    ): Double {
        var position = startPosition
        for (i in outOffset until outOffset + count) {
            out[i] = read(source, position)
            position += rate
        }
        return position
    }

    /**
     * As [render], but the playhead wraps within `[loopStart, loopEnd)` in
     * whichever direction it is travelling, so a loop survives being scratched
     * backwards past its start.
     *
     * @return the playhead position after the block, always inside the loop.
     */
    fun renderLooping(
        source: FloatArray,
        startPosition: Double,
        rate: Double,
        loopStart: Int,
        loopEnd: Int,
        out: FloatArray,
        outOffset: Int = 0,
        count: Int = out.size - outOffset,
    ): Double {
        require(loopEnd > loopStart) { "loopEnd ($loopEnd) must exceed loopStart ($loopStart)" }
        val length = (loopEnd - loopStart).toDouble()
        var position = wrap(startPosition, loopStart.toDouble(), length)
        for (i in outOffset until outOffset + count) {
            out[i] = read(source, position)
            position = wrap(position + rate, loopStart.toDouble(), length)
        }
        return position
    }

    /** Wraps [position] into `[start, start + length)`. */
    private fun wrap(position: Double, start: Double, length: Double): Double {
        var p = (position - start) % length
        if (p < 0) p += length
        return start + p
    }
}
