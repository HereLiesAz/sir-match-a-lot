package com.hereliesaz.sirmatchalot.audio

import com.hereliesaz.sirmatchalot.dsp.LoopCandidate
import com.hereliesaz.sirmatchalot.dsp.Resampler
import kotlin.math.abs
import kotlin.math.min

/**
 * One sampler pad.
 *
 * @param index position on the grid.
 * @param buffer the audio it holds, or null when empty.
 * @param label what to show on it.
 * @param loop whether it repeats while held.
 */
class SamplerPad(val index: Int) {

    @Volatile
    var buffer: PcmBuffer? = null
        private set

    @Volatile
    var label: String? = null
        private set

    @Volatile
    var loop: Boolean = false

    @Volatile
    var gain: Float = 1f

    /** Playback position in frames; negative means idle. */
    @Volatile
    var playhead: Double = IDLE
        private set

    val isEmpty: Boolean get() = buffer == null

    val isPlaying: Boolean get() = playhead >= 0.0

    fun load(buffer: PcmBuffer?, label: String?, loop: Boolean = false) {
        stop()
        this.buffer = buffer
        this.label = label
        this.loop = loop
    }

    fun clear() = load(null, null)

    fun trigger() {
        if (buffer != null) playhead = 0.0
    }

    fun stop() {
        playhead = IDLE
    }

    internal fun advanceTo(position: Double) {
        playhead = position
    }

    companion object {
        const val IDLE = -1.0
    }
}

/**
 * Pads that record from the mix, replay, and fill themselves from the music.
 *
 * This replaces a "sampler" that sampled nothing: the previous implementation
 * synthesised sine and noise bursts on every pad, had no recording, and never
 * touched the loaded tracks.
 *
 * Recording captures the master bus into a **preallocated** buffer, because
 * capture runs on the audio thread and allocating there is what causes
 * dropouts. The trade is a fixed ceiling on take length, which is the right way
 * round for a performance tool.
 *
 * @param padCount number of pads.
 * @param sampleRate the engine's rate; recorded takes adopt it.
 * @param maxRecordSeconds ceiling on a single take.
 */
class Sampler(
    val padCount: Int = 8,
    private val sampleRate: Int = 44_100,
    maxRecordSeconds: Double = 30.0,
) {
    val pads: List<SamplerPad> = List(padCount) { SamplerPad(it) }

    /** Preallocated capture space — never grown while recording. */
    private val captureLeft = ShortArray((maxRecordSeconds * sampleRate).toInt())
    private val captureRight = ShortArray(captureLeft.size)

    @Volatile
    private var captureLength = 0

    /** Pad currently being recorded into, or -1. */
    @Volatile
    var recordingPad: Int = -1
        private set

    val isRecording: Boolean get() = recordingPad >= 0

    /** How much of the capture ceiling the current take has used, 0..1. */
    fun recordProgress(): Float =
        if (captureLeft.isEmpty()) 0f else captureLength.toFloat() / captureLeft.size

    /**
     * Starts recording into [pad]. Any existing content is replaced when the take
     * finishes, not now, so an aborted take leaves the pad as it was.
     */
    fun beginRecording(pad: Int) {
        if (pad !in pads.indices) return
        captureLength = 0
        recordingPad = pad
    }

    /**
     * Captures [frames] of interleaved stereo from the master bus.
     *
     * Called from the audio thread. Allocates nothing, and stops accepting audio
     * once the ceiling is reached rather than overwriting the take.
     */
    fun captureFromMaster(buffer: FloatArray, frames: Int) {
        if (recordingPad < 0) return
        val space = captureLeft.size - captureLength
        if (space <= 0) return
        val toCopy = min(frames, space)
        for (i in 0 until toCopy) {
            val base = i * Deck.CHANNELS
            captureLeft[captureLength + i] = toShort(buffer[base])
            captureRight[captureLength + i] = toShort(buffer[base + 1])
        }
        captureLength += toCopy
    }

    /**
     * Ends the take and loads it onto the pad.
     *
     * @return the captured audio, or null if nothing was captured.
     */
    fun endRecording(label: String? = null, loop: Boolean = true): PcmBuffer? {
        val pad = recordingPad
        recordingPad = -1
        if (pad !in pads.indices || captureLength == 0) return null

        val captured = PcmBuffer(
            channels = arrayOf(
                captureLeft.copyOf(captureLength),
                captureRight.copyOf(captureLength),
            ),
            sampleRate = sampleRate,
        )
        pads[pad].load(captured, label ?: "Take ${pad + 1}", loop)
        captureLength = 0
        return captured
    }

    /** Abandons the current take, leaving the pad untouched. */
    fun cancelRecording() {
        recordingPad = -1
        captureLength = 0
    }

    fun trigger(pad: Int) = pads.getOrNull(pad)?.trigger()

    fun stop(pad: Int) = pads.getOrNull(pad)?.stop()

    fun stopAll() = pads.forEach { it.stop() }

    /**
     * Mixes every playing pad into [out], which is interleaved stereo.
     *
     * Adds to existing content rather than replacing it, so the sampler layers
     * over the decks. Allocates nothing.
     */
    fun render(out: FloatArray, frames: Int) {
        for (pad in pads) {
            val buffer = pad.buffer ?: continue
            var position = pad.playhead
            if (position < 0.0) continue

            val left = buffer.channel(0)
            val right = buffer.channel(1)
            val gain = pad.gain
            val length = buffer.frameCount

            for (i in 0 until frames) {
                if (position >= length) {
                    if (!pad.loop) { position = SamplerPad.IDLE; break }
                    position -= length
                }
                val base = i * Deck.CHANNELS
                out[base] += Resampler.read(left, position) * gain
                out[base + 1] += Resampler.read(right, position) * gain
                position += 1.0
            }
            pad.advanceTo(position)
        }
    }

    /**
     * Fills empty pads with loops taken from a track — the "automatically grab
     * samples to fill up the unused pads" request.
     *
     * Occupied pads are never overwritten, because a performer's own takes must
     * not vanish because the app found something it liked better. Candidates are
     * used strongest first.
     *
     * @param source the decoded track the candidates were found in.
     * @return how many pads were filled.
     */
    fun autoFill(
        candidates: List<LoopCandidate>,
        source: PcmBuffer,
        trackTitle: String = "Loop",
    ): Int {
        var filled = 0
        val ordered = candidates.sortedByDescending { it.score }
        for (candidate in ordered) {
            val pad = pads.firstOrNull { it.isEmpty } ?: break
            val startFrame = (candidate.startSeconds * source.sampleRate).toInt()
            val endFrame = ((candidate.startSeconds + candidate.durationSeconds) * source.sampleRate).toInt()
            if (startFrame >= source.frameCount || endFrame <= startFrame) continue

            pad.load(
                buffer = source.slice(startFrame, endFrame),
                label = "$trackTitle ${candidate.bars}bar",
                loop = true,
            )
            filled++
        }
        return filled
    }

    /** Empties every pad. */
    fun clearAll() {
        cancelRecording()
        pads.forEach { it.clear() }
    }

    val emptyPadCount: Int get() = pads.count { it.isEmpty }

    private fun toShort(sample: Float): Short =
        (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
}
