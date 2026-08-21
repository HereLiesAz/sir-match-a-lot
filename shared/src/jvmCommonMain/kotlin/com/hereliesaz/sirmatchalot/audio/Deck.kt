package com.hereliesaz.sirmatchalot.audio

import com.hereliesaz.sirmatchalot.dsp.Biquad
import com.hereliesaz.sirmatchalot.dsp.BiquadCoefficients
import com.hereliesaz.sirmatchalot.dsp.Resampler
import kotlin.math.abs

/**
 * One audio clip placed on a deck's circular timeline.
 *
 * @param id stable identifier, matching the library track or loop.
 * @param buffer decoded audio.
 * @param startFrame where on the deck timeline this clip begins.
 * @param gain linear gain multiplier.
 * @param loop whether the clip repeats for the whole timeline rather than
 *   occupying only its own span. A single clip alone on a deck loops, which is
 *   what makes one sample circle the whole platter.
 */
class Clip(
    val id: String,
    val buffer: PcmBuffer,
    val startFrame: Int = 0,
    val gain: Float = 1f,
    val loop: Boolean = false,
) {
    val frameCount: Int get() = buffer.frameCount

    /** One past the last frame of the deck timeline this clip covers. */
    val endFrame: Int get() = startFrame + frameCount
}

/**
 * Where a new or moved clip actually lands, once it is not allowed to sit on
 * top of another clip already on the same deck.
 *
 * Nothing used to stop two clips sharing a stretch of the circle: a drop or a
 * drag only ever picked a frame, snapped it to the beat grid, and wrote it
 * down. Two clips at the same point overlapped in sound *and* on the ring —
 * the waveforms painted over each other and the deck a performer was reading
 * by eye stopped saying anything true.
 */
object ClipPlacement {

    /**
     * Adjusts [desiredStart] so a clip of [length] frames does not overlap any
     * of [occupied] — the other clips already on the deck, as `start` to
     * `endExclusive` frame pairs.
     *
     * A placement that does not overlap anything is returned unchanged. One
     * that does is pulled to the nearest edge of a free gap — the gap's start
     * if [desiredStart] fell before the clip it collided with, the gap's end
     * if after — so a drop lands just outside whichever clip it landed on
     * rather than jumping across the whole circle to some other free stretch.
     * When no gap on the ring is big enough, the clip is appended after the
     * last one instead, extending the revolution — the same fallback an empty
     * deck already uses when a track is loaded without an explicit position.
     */
    fun nonOverlappingStart(
        desiredStart: Int,
        length: Int,
        occupied: List<Pair<Int, Int>>,
    ): Int {
        val start = desiredStart.coerceAtLeast(0)
        if (length <= 0) return start

        val ranges = occupied.filter { it.second > it.first }.sortedBy { it.first }
        if (ranges.isEmpty()) return start

        fun overlaps(s: Int, e: Int) = ranges.any { (rangeStart, rangeEnd) -> s < rangeEnd && e > rangeStart }
        if (!overlaps(start, start + length)) return start

        // Every clip's own edges are candidate landing points: just after it
        // ends, or just far enough before it starts to clear it.
        val candidates = LinkedHashSet<Int>()
        candidates.add(0)
        for ((rangeStart, rangeEnd) in ranges) {
            candidates.add(rangeEnd)
            candidates.add((rangeStart - length).coerceAtLeast(0))
        }

        val free = candidates.filter { it >= 0 && !overlaps(it, it + length) }
        if (free.isEmpty()) return ranges.maxOf { it.second }
        return free.minBy { kotlin.math.abs(it - start) }
    }

    /**
     * [nonOverlappingStart] against a deck's actual [Clip]s, excluding
     * [excludingId] — the clip being placed, when it is already on the deck
     * and would otherwise collide with itself.
     */
    fun nonOverlappingStart(
        desiredStart: Int,
        length: Int,
        existingClips: List<Clip>,
        excludingId: String? = null,
    ): Int = nonOverlappingStart(
        desiredStart = desiredStart,
        length = length,
        occupied = existingClips.filter { it.id != excludingId }.map { it.startFrame to it.endFrame },
    )
}

/**
 * A deck: a circular timeline of clips read by a single signed-rate playhead,
 * then filtered and gained.
 *
 * The playhead is a `Double` frame index advanced by a **signed** rate, so a
 * negative rate plays backwards and the signal stays continuous through zero —
 * there is no seek, and therefore no click, anywhere in a scratch. The timeline
 * wraps at [cycleFrames], which is the same wrap the platter draws as one full
 * revolution: angle and time are the same quantity, measured once.
 *
 * Not thread-safe. Owned by the render thread; controls are set from outside via
 * plain volatile writes, which is safe for the single `Float`/`Double` fields
 * used here and avoids locking the audio path. [playhead] is the one
 * exception: loading a clip onto a *playing* deck clamps it to the new,
 * possibly shorter, cycle from the loading thread, while the render thread
 * both reads and rewrites it every block — a plain volatile read-modify-write
 * from the loader could race that block-end write and leave the playhead a
 * block short of where either side thought it landed. `playheadLock` is a
 * short, uncontended `synchronized` around just that field's read and write,
 * not the whole render path.
 */
class Deck(
    val name: String,
    val outputSampleRate: Int = 44_100,
) {
    /** Clips currently loaded. Replaced wholesale, never mutated in place. */
    @Volatile
    var clips: List<Clip> = emptyList()
        set(value) {
            field = value
            // Keep the playhead inside the new cycle rather than stranding it
            // past the end of a shortened timeline. Synchronized against the
            // render thread's own end-of-block write to playhead below, so
            // this read-modify-write cannot race it.
            val frames = cycleFramesOf(value)
            if (frames > 0) {
                synchronized(playheadLock) {
                    if (playhead >= frames) playhead %= frames
                }
            }
        }

    private val playheadLock = Any()

    /**
     * Length of one revolution in frames — the longest clip extent, so the
     * timeline is exactly as long as the material on it.
     */
    val cycleFrames: Int get() = cycleFramesOf(clips)

    /**
     * [cycleFrames] over an already-read [snapshot], so a caller that needs
     * both the cycle length and the clip list itself — [render] does — reads
     * the volatile [clips] field exactly once rather than once per use.
     * `clips` can be replaced by another thread between any two reads of it,
     * and reading it three times in one render block used to let a cycle
     * length computed from one clip list be combined with a clip snapshot
     * from another — a load landing mid-block could make one block of audio
     * wrap at the wrong point or read a clip at the wrong modulus.
     */
    private fun cycleFramesOf(snapshot: List<Clip>): Int {
        // Indexed rather than `maxOfOrNull`, which iterates and so allocates.
        // Once per render block rather than once per frame, but the block is
        // still the audio callback.
        var longest = 0
        for (index in 0 until snapshot.size) {
            val clip = snapshot[index]
            val end = clip.startFrame + timelineFrames(clip)
            if (end > longest) longest = end
        }
        return longest
    }

    /**
     * How many frames of *this deck's* timeline [clip] occupies.
     *
     * The timeline is measured in output frames, so a clip decoded at another
     * rate covers a different number of them than it has samples: 4,800 frames
     * of 48 kHz audio is 4,410 frames of a 44.1 kHz timeline, and is a tenth of
     * a second either way.
     */
    private fun timelineFrames(clip: Clip): Int =
        (clip.frameCount * outputSampleRate.toDouble() / clip.buffer.sampleRate).toInt()

    /**
     * True when this deck can produce sound: running, with material on it.
     *
     * Exactly the condition [render] checks before doing any work, exposed so
     * the output can tell whether the whole graph is idle and stand down. Kept
     * next to that check rather than reconstructed elsewhere, so the two cannot
     * drift apart and leave the output running for a deck that renders nothing.
     */
    val isSounding: Boolean
        get() {
            val snapshot = clips
            return playing && snapshot.isNotEmpty() && cycleFramesOf(snapshot) > 0
        }

    /** Current position on the timeline, in frames. */
    @Volatile
    var playhead: Double = 0.0

    /**
     * Playback rate. 1.0 is normal speed, negative plays backwards, 0.0 holds.
     * Pitch follows rate, as on a turntable.
     */
    @Volatile
    var rate: Double = 1.0

    @Volatile
    var gain: Float = 1f

    /** Low-shelf boost in dB, driven by the two-finger pinch bass-boost gesture. */
    @Volatile
    var bassBoostDb: Double = 0.0

    /** High-shelf gain in dB. */
    @Volatile
    var trebleDb: Double = 0.0

    @Volatile
    var playing: Boolean = false

    private val bassFilters = Array(CHANNELS) { Biquad() }
    private val trebleFilters = Array(CHANNELS) { Biquad() }
    private var appliedBassDb = Double.NaN
    private var appliedTrebleDb = Double.NaN

    /** Peak absolute sample of the most recent block, for the visuals. */
    @Volatile
    var outputLevel: Float = 0f
        private set

    /**
     * Renders [frames] frames into [out], which is interleaved stereo and must
     * hold at least `frames * 2` values. Existing content is overwritten.
     *
     * Allocates nothing — and now really does not.
     *
     * `for (clip in snapshot)` sat inside the per-frame loop, and a `for` over a
     * `List` compiles to `List.iterator()`: one iterator object per output frame
     * per deck, which at 512-frame blocks and two decks is on the order of
     * 88,000 objects a second, on the thread whose own contract is that it must
     * not allocate. Indexed loops here and in [cycleFrames].
     */
    fun render(out: FloatArray, frames: Int) {
        require(out.size >= frames * CHANNELS) { "output buffer too small" }

        java.util.Arrays.fill(out, 0, frames * CHANNELS, 0f)

        val snapshot = clips
        val cycle = cycleFramesOf(snapshot)
        if (!playing || cycle <= 0 || snapshot.isEmpty()) {
            outputLevel = 0f
            return
        }

        // Synchronized against the `clips` setter's own clamp of this same
        // field: without it, a load landing here raced this read and could
        // hand this block a playhead the loader was in the middle of
        // rewriting.
        var position = synchronized(playheadLock) { playhead }
        // A shorter clip list can land between two render calls and shrink
        // the cycle out from under a playhead that was valid a block ago.
        if (position >= cycle || position < 0.0) {
            position %= cycle
            if (position < 0.0) position += cycle
        }
        // The playhead moves in output frames. Nothing about the material scales
        // it any more — `localPosition` converts per clip.
        val step = rate
        val clipCount = snapshot.size

        for (frame in 0 until frames) {
            var left = 0f
            var right = 0f

            for (clipIndex in 0 until clipCount) {
                val clip = snapshot[clipIndex]
                val local = localPosition(clip, position, cycle)
                if (local.isNaN()) continue
                // One branch per clip per frame on the depth, rather than a
                // conversion per sample. A hi-res clip is read from its float
                // storage directly; a 16-bit one from its Short storage. The
                // alternative — a common float view — would mean either
                // widening every 16-bit track in memory, doubling the heap the
                // decks hold, or narrowing every hi-res one, which is the thing
                // carrying depth exists to avoid.
                if (clip.buffer.isFloat) {
                    left += Resampler.read(clip.buffer.floatChannel(0), local) * clip.gain
                    right += Resampler.read(clip.buffer.floatChannel(1), local) * clip.gain
                } else {
                    left += Resampler.read(clip.buffer.channel(0), local) * clip.gain
                    right += Resampler.read(clip.buffer.channel(1), local) * clip.gain
                }
            }

            val base = frame * CHANNELS
            out[base] = left
            out[base + 1] = right

            position += step
            // Wrap in whichever direction the playhead is travelling.
            if (position >= cycle) position -= cycle
            else if (position < 0.0) position += cycle
        }

        synchronized(playheadLock) { playhead = position }

        applyEq(out, frames)
        applyGain(out, frames)
        outputLevel = peakOf(out, frames)
    }

    /**
     * Position within [clip]'s own buffer for a deck-timeline [position], or
     * [SILENT_HERE] if the clip is not sounding there.
     *
     * A `Double?` would read better and box: Kotlin's nullable `Double` is a
     * `java.lang.Double` on the heap, and this is called once per clip per
     * frame, so it was the largest single allocation source in a render loop
     * whose contract is that it allocates nothing. NaN is the natural sentinel
     * here — it is not a position, and every comparison against it is false.
     */
    private fun localPosition(clip: Clip, position: Double, cycle: Int): Double {
        if (clip.frameCount <= 0) return SILENT_HERE
        // Source frames per timeline frame, for this clip alone. The correction
        // used to be taken once from `clips.first()` and applied to the playhead
        // itself, which put the whole timeline in one clip's frame domain: a
        // 44.1 kHz clip sharing a deck with a 48 kHz one played 8.8% fast — a
        // semitone and a half sharp, drifting off the beat grid — while the KDoc
        // on `rateScale` claimed this was exactly the case it handled.
        val scale = clip.buffer.sampleRate.toDouble() / outputSampleRate
        val span = timelineFrames(clip)
        if (span <= 0) return SILENT_HERE
        if (clip.loop) {
            // A looping clip sounds everywhere on the timeline.
            var offset = (position - clip.startFrame) % span
            if (offset < 0) offset += span
            return offset * scale
        }
        if (position < clip.startFrame || position >= clip.startFrame + span) return SILENT_HERE
        return (position - clip.startFrame) * scale
    }

    /** Returned by [localPosition] when the clip is not sounding at that point. */
    private val SILENT_HERE = Double.NaN

    private fun applyEq(out: FloatArray, frames: Int) {
        val bass = bassBoostDb
        val treble = trebleDb

        if (bass != appliedBassDb) {
            val coefficients =
                if (abs(bass) < 0.01) BiquadCoefficients.IDENTITY
                else BiquadCoefficients.lowShelf(200.0, outputSampleRate, bass)
            bassFilters.forEach { it.coefficients = coefficients }
            appliedBassDb = bass
        }
        if (treble != appliedTrebleDb) {
            val coefficients =
                if (abs(treble) < 0.01) BiquadCoefficients.IDENTITY
                else BiquadCoefficients.highShelf(4000.0, outputSampleRate, treble)
            trebleFilters.forEach { it.coefficients = coefficients }
            appliedTrebleDb = treble
        }

        val bassActive = abs(appliedBassDb) >= 0.01
        val trebleActive = abs(appliedTrebleDb) >= 0.01
        if (!bassActive && !trebleActive) return

        for (channel in 0 until CHANNELS) {
            var i = channel
            while (i < frames * CHANNELS) {
                var sample = out[i]
                if (bassActive) sample = bassFilters[channel].process(sample)
                if (trebleActive) sample = trebleFilters[channel].process(sample)
                out[i] = sample
                i += CHANNELS
            }
        }
    }

    /**
     * Smoothed towards [gain] one frame at a time, same time constant as
     * [Mixer]'s crossfade/master smoothing — 1/[GAIN_SMOOTHING] = 200
     * samples, ~4.5 ms at 44.1 kHz.
     *
     * This used to read [gain] once per block and multiply the whole block
     * by it, unlike every other gain stage in the render graph: a scripted
     * gain change (`TransitionChoreographer`'s flourishes go straight to
     * this field) took effect on the very next sample as a step, not a ramp
     * — an audible click on every flourish, in the middle of an automated
     * mix.
     */
    private var smoothedGain = 1f
    private var gainInitialised = false

    private fun applyGain(out: FloatArray, frames: Int) {
        val target = gain
        if (!gainInitialised) {
            smoothedGain = target
            gainInitialised = true
        }
        for (frame in 0 until frames) {
            smoothedGain += (target - smoothedGain) * GAIN_SMOOTHING
            val base = frame * CHANNELS
            for (channel in 0 until CHANNELS) out[base + channel] *= smoothedGain
        }
    }

    private fun peakOf(out: FloatArray, frames: Int): Float {
        var peak = 0f
        for (i in 0 until frames * CHANNELS) {
            val magnitude = abs(out[i])
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }

    /** Length of one revolution in seconds. Zero when the deck is empty. */
    val cycleSeconds: Double
        get() = cycleFrames.toDouble() / outputSampleRate

    /**
     * Moves the playhead by [seconds], wrapping around the circular timeline in
     * whichever direction it travels.
     *
     * Wrapping rather than clamping is what makes a nudge on a looping deck
     * behave like a nudge on a record: pushing back past the start arrives at the
     * end, not at a stop. Does nothing on an empty deck.
     */
    fun nudgeSeconds(seconds: Double) {
        val cycle = cycleFrames
        if (cycle <= 0) return
        // Synchronized: this is a read-modify-write of the same field the
        // render thread rewrites once per block.
        synchronized(playheadLock) {
            var position = (playhead + seconds * outputSampleRate) % cycle
            if (position < 0) position += cycle
            playhead = position
        }
    }

    /**
     * Places the playhead [seconds] from the start of the timeline, wrapping if
     * that is past the end. Does nothing on an empty deck.
     */
    fun seekToSeconds(seconds: Double) {
        val cycle = cycleFrames
        if (cycle <= 0) return
        var position = (seconds * outputSampleRate) % cycle
        if (position < 0) position += cycle
        synchronized(playheadLock) { playhead = position }
    }

    /** Position on the timeline as a fraction of one revolution, for the platter. */
    fun cyclePosition(): Float {
        val cycle = cycleFrames
        if (cycle <= 0) return 0f
        return (playhead / cycle).toFloat().coerceIn(0f, 1f)
    }

    /** Seeks to a fraction of one revolution. */
    fun seekToFraction(fraction: Float) {
        val cycle = cycleFrames
        val position = if (cycle <= 0) 0.0 else fraction.coerceIn(0f, 1f).toDouble() * cycle
        // Synchronized against the render thread's own end-of-block write to
        // this same field, same as nudgeSeconds/seekToSeconds — an
        // unsynchronized write here is a lost update if the render thread's
        // in-flight block finishes and overwrites it with a position computed
        // from before this seek landed.
        synchronized(playheadLock) { playhead = position }
    }

    fun reset() {
        playhead = 0.0
        rate = 1.0
        playing = false
        bassFilters.forEach { it.reset() }
        trebleFilters.forEach { it.reset() }
    }

    companion object {
        const val CHANNELS = 2

        /** See [applyGain]; same time constant as [Mixer.SMOOTHING]. */
        private const val GAIN_SMOOTHING = 0.005f
    }
}
