package com.hereliesaz.sirmatchalot.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The boundary between the mixer and the platform's audio sink.
 *
 * This exists so the render graph has no dependency on how samples leave the
 * process. Two consequences:
 *
 * - Tests drive the whole engine through [OfflineAudioOutput] and assert on the
 *   samples it collects, so mixing, crossfade law, EQ, and reverse playback are
 *   all covered by ordinary JVM unit tests with no device involved.
 * - If on-device measurement shows underruns from GC jitter, an Oboe/AAudio
 *   backend implements this interface and nothing in `dsp/`, `Deck`, `Mixer`, or
 *   the UI changes. That is the migration path, and it is one class wide.
 */
interface AudioOutput {

    /** Sample rate the sink expects. */
    val sampleRate: Int

    /** Frames requested per render callback. */
    val framesPerBuffer: Int

    /**
     * Starts pulling from [render], which must fill the supplied interleaved
     * stereo buffer with the given number of frames.
     */
    fun start(render: (buffer: FloatArray, frames: Int) -> Unit)

    fun stop()

    fun release()
}

/**
 * Collects rendered audio into memory instead of playing it.
 *
 * Used by tests to inspect exactly what the engine produces.
 */
class OfflineAudioOutput(
    override val sampleRate: Int = 44_100,
    override val framesPerBuffer: Int = 256,
) : AudioOutput {

    private val collected = ArrayList<Float>()

    override fun start(render: (FloatArray, Int) -> Unit) = Unit

    override fun stop() = Unit

    override fun release() = Unit

    /** Renders [blocks] buffers through [render] and returns the interleaved result. */
    fun renderBlocks(blocks: Int, render: (FloatArray, Int) -> Unit): FloatArray {
        val buffer = FloatArray(framesPerBuffer * Deck.CHANNELS)
        collected.clear()
        repeat(blocks) {
            render(buffer, framesPerBuffer)
            for (i in 0 until framesPerBuffer * Deck.CHANNELS) collected.add(buffer[i])
        }
        return collected.toFloatArray()
    }
}

/**
 * Plays through [AudioTrack] on a dedicated high-priority thread.
 *
 * Uses `MODE_STREAM` with a blocking `write`, rather than a callback, because a
 * blocking write with a modest buffer tolerates scheduling jitter better than a
 * callback that must never be late — which matters in a managed runtime where a
 * GC pause is always possible. The cost is latency, and the buffer size below is
 * the knob that trades one against the other.
 *
 * The render lambda runs on this thread and must not allocate.
 */
class AudioTrackOutput(
    override val sampleRate: Int = 44_100,
    override val framesPerBuffer: Int = 256,
) : AudioOutput {

    companion object Factory {
        private const val BYTES_PER_FLOAT = 4

        /**
         * Builds an output running at the device's **native** mixer rate.
         *
         * Running the engine at any other rate makes AudioFlinger resample every
         * block on the way out, adding a conversion nobody asked for and no
         * control over its quality. Matching the native rate means the only
         * sample-rate conversion in the whole signal path is the one done once
         * per track at load time, where we choose the algorithm and can afford a
         * good one.
         *
         * Also adopts the platform's preferred buffer size, which is the burst
         * the fast mixer actually wants; a mismatched size costs latency or
         * causes underruns.
         */
        fun forDevice(context: android.content.Context): AudioTrackOutput {
            val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
            val nativeRate = manager
                ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: 48_000
            val nativeBurst = manager
                ?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: 256
            // Two bursts per render call keeps the blocking write comfortably
            // ahead of the mixer without adding much latency.
            return AudioTrackOutput(
                sampleRate = nativeRate,
                framesPerBuffer = nativeBurst * 2,
            )
        }
    }

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    /** Underrun count since start, for diagnosing whether an Oboe backend is warranted. */
    @Volatile
    var underrunCount: Int = 0
        private set

    override fun start(render: (FloatArray, Int) -> Unit) {
        if (running.getAndSet(true)) return

        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val minimumBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        // Three render buffers, or the platform minimum if that is larger.
        val requestedBytes = framesPerBuffer * Deck.CHANNELS * BYTES_PER_FLOAT * 3
        val bufferBytes = maxOf(minimumBytes, requestedBytes)

        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        val created = builder.build()
        track = created

        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = FloatArray(framesPerBuffer * Deck.CHANNELS)
            created.play()
            var lastUnderrun = 0

            while (running.get()) {
                render(buffer, framesPerBuffer)
                var offset = 0
                val total = buffer.size
                while (offset < total && running.get()) {
                    val written = created.write(
                        buffer,
                        offset,
                        total - offset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written <= 0) break
                    offset += written
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val current = created.underrunCount
                    if (current != lastUnderrun) {
                        underrunCount = current
                        lastUnderrun = current
                    }
                }
            }
            created.stop()
        }.apply {
            name = "SirMatchALot-Audio"
            start()
        }
    }

    /** True once [start] has been called and the render thread is running. */
    val isRunning: Boolean get() = running.get()

    override fun stop() {
        if (!running.getAndSet(false)) return
        thread?.join(500)
        thread = null
    }

    override fun release() {
        stop()
        track?.release()
        track = null
    }

}

/**
 * Owns the mixer and the output, and wires one to the other.
 *
 * The single entry point the ViewModel talks to.
 */
class AudioEngine(
    val output: AudioOutput = AudioTrackOutput(),
) {
    val deckA = Deck("A", output.sampleRate)
    val deckB = Deck("B", output.sampleRate)
    val mixer = Mixer(deckA, deckB, output.sampleRate, maxFrames = output.framesPerBuffer)

    /** Fired once when a scratch is dragged past the reverse threshold. */
    @Volatile
    var onReverseThreshold: (() -> Unit)? = null

    private val scratch = ScratchModel()
    private var started = false

    fun start() {
        if (started) return
        started = true
        output.start { buffer, frames ->
            mixer.render(buffer, frames)
            if (scratch.accountForRenderedFrames(frames)) {
                onReverseThreshold?.invoke()
            }
        }
    }

    fun stop() {
        output.stop()
        started = false
    }

    fun release() {
        output.release()
        started = false
    }

    /**
     * Applies a computed beat alignment to a deck.
     *
     * Tempo is a rate multiplier and phase is a playhead nudge, both applied
     * once rather than chased. The previous implementation polled every 250 ms
     * from the UI and called `seekTo` whenever drift exceeded 40 ms, which could
     * not converge because the correction was coarser than the error.
     *
     * @param phaseOffsetSeconds positive shifts the deck later.
     */
    fun applyAlignment(deckName: String, tempoRatio: Double, phaseOffsetSeconds: Double) {
        val deck = if (deckName == "A") deckA else deckB
        deck.rate = tempoRatio
        val cycle = deck.cycleFrames
        if (cycle <= 0) return
        val shift = phaseOffsetSeconds * output.sampleRate
        var position = deck.playhead + shift
        // Keep the playhead on the circle rather than letting a correction walk
        // it off either end.
        position %= cycle
        if (position < 0) position += cycle
        deck.playhead = position
    }

    /** Begins a scratch on both decks. */
    fun beginScratch() = scratch.begin(deckA.rate)

    /** Updates a scratch in progress from the gesture's cumulative displacement. */
    fun updateScratch(totalDeltaY: Float) {
        val rate = scratch.update(totalDeltaY)
        deckA.rate = rate
        deckB.rate = rate
    }

    fun endScratch() {
        val rate = scratch.end()
        deckA.rate = rate
        deckB.rate = rate
    }

    fun scratchReverseProgress(): Float = scratch.reverseProgress()
}
