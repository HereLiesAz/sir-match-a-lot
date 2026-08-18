package com.hereliesaz.sirmatchalot.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

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
 *
 * ## Standing down
 *
 * A blocking write is only cheap when there is something to write. Left alone,
 * this thread renders, filters, limits and meters silence forever — the full
 * per-sample cost of the whole graph, at the device's native rate, from the
 * moment the app opens until it is killed — while `AudioTrack` holds the output
 * path open so the audio hardware never gets to idle either. Measured against
 * everything else in the app, that was the largest single power draw and it was
 * paid whether or not a single track had been loaded.
 *
 * So when [isIdle] says nothing is sounding, the track is paused and this thread
 * parks. It wakes on a short timer — or immediately on [wake] — checks again,
 * and resumes the moment there is audio. Pausing is deliberately delayed by
 * about a second of blocks so a gap between clips, or a scratch held at zero
 * rate, does not tear down and rebuild the output path underneath the music.
 */
class AudioTrackOutput(
    override val sampleRate: Int = 44_100,
    override val framesPerBuffer: Int = 256,
) : AudioOutput {

    /**
     * Says whether nothing is currently sounding.
     *
     * Supplied by [AudioEngine], which is the only thing that can answer it.
     * Absent — or always false — means never stand down, which is exactly the
     * old behaviour and what someone who wants no wake-up latency at all gets.
     */
    @Volatile
    var isIdle: (() -> Boolean)? = null

    /**
     * Called once each time the output stands down, on the audio thread.
     *
     * The engine uses it to drop the meters to silence: a light show frozen at
     * whatever level was playing when the music stopped is worse than one that
     * goes dark, and nothing else is going to clear them once this thread has
     * stopped measuring.
     */
    @Volatile
    var onStandDown: (() -> Unit)? = null

    /** True while the output is parked because nothing was sounding. */
    @Volatile
    var isStoodDown: Boolean = false
        private set

    override fun configureIdleTracking(isIdle: () -> Boolean, onStandDown: () -> Unit) {
        this.isIdle = isIdle
        this.onStandDown = onStandDown
    }

    private val gate = java.lang.Object()

    companion object Factory {
        private const val BYTES_PER_FLOAT = 4

        /** How long [release] waits for the render thread before giving up. */
        private const val RELEASE_JOIN_TIMEOUT_MS = 2_000L

        /**
         * How long the audio thread parks between idle checks.
         *
         * Twenty milliseconds is about two render blocks, so the worst-case
         * delay before sound resumes is comparable to the buffering already in
         * the path — and a wake-up that does nothing but read one volatile
         * boolean costs a rounding error next to a block of DSP.
         */
        const val IDLE_POLL_MILLIS = 20L

        /**
         * Builds an output at the rate [option] asks for, defaulting to the
         * device's native mixer rate.
         *
         * @see com.hereliesaz.sirmatchalot.data.SampleRateOption for what the
         *   choice costs in memory, power and bandwidth.
         */
        fun forDevice(
            context: android.content.Context,
            option: com.hereliesaz.sirmatchalot.data.SampleRateOption,
        ): AudioTrackOutput {
            val native = forDevice(context)
            val rate = option.resolve(native.sampleRate)
            if (rate == native.sampleRate) return native
            // The platform's preferred burst is expressed in frames at the
            // native rate. Scaling it keeps the block roughly the same *duration*
            // — which is what the buffering is actually about — instead of
            // quartering the latency budget along with the rate.
            val frames = (native.framesPerBuffer.toLong() * rate / native.sampleRate)
                .toInt()
                .coerceAtLeast(64)
            return AudioTrackOutput(sampleRate = rate, framesPerBuffer = frames)
        }

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

        // A start()/stop() cycle without an intervening release() used to
        // leave the previous AudioTrack allocated: this overwrote `track`
        // with the new one and never released the old — one leaked native
        // track per cycle. Torn down here, the same careful way release()
        // does, before a new one is built.
        releaseCurrentTrack()

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

        // Roughly a second of blocks. Long enough that a silent bar, a clip
        // boundary or a scratch held still is not mistaken for the end of the
        // performance; short enough that leaving the app paused stops costing
        // anything almost immediately.
        val idleBlocksBeforeStandDown =
            (sampleRate / framesPerBuffer.coerceAtLeast(1)).coerceAtLeast(1)

        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = FloatArray(framesPerBuffer * Deck.CHANNELS)
            created.play()
            var lastUnderrun = 0
            var idleBlocks = 0
            var paused = false

            while (running.get()) {
                if (isIdle?.invoke() == true) idleBlocks++ else idleBlocks = 0

                if (idleBlocks >= idleBlocksBeforeStandDown) {
                    if (!paused) {
                        runCatching {
                            created.pause()
                            // Drop what is queued rather than letting a second
                            // of stale silence play out on the next resume.
                            created.flush()
                        }
                        paused = true
                        isStoodDown = true
                        onStandDown?.invoke()
                    }
                    // Parked, not spinning: one short wait per cycle, and a
                    // caller that knows sound is coming can cut it short.
                    synchronized(gate) {
                        runCatching { gate.wait(IDLE_POLL_MILLIS) }
                    }
                    continue
                }

                if (paused) {
                    runCatching { created.play() }
                    paused = false
                    isStoodDown = false
                }

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

    /** Cuts a stand-down short, so the next sample is not up to a poll late. */
    override fun wake() {
        synchronized(gate) { gate.notifyAll() }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        // The thread may be parked in the idle wait; without this, stopping
        // would block for the rest of the poll interval.
        wake()
        // Bounded, not joined to completion: a caller changing decks should
        // not be stuck behind a slow teardown. `thread` deliberately keeps
        // its reference rather than being nulled here — release() and a
        // following start() both need to know whether it is still alive
        // before touching the AudioTrack it may still be writing to.
        thread?.join(500)
        isStoodDown = false
    }

    /**
     * Releases [track], but only once the render thread that may still be
     * writing to it has actually finished.
     *
     * [stop]'s join is bounded so it stays responsive; that leaves a narrow
     * window where the thread is still inside a blocking `AudioTrack.write()`
     * call when a caller asks to release the track. Releasing underneath it
     * then would be a use of a freed native object from that thread. Waited
     * out here instead, with a longer bound: the thread only blocks on I/O
     * that completes once `running` (already false by the time this runs) is
     * observed, so this is not expected to matter in practice — but gambling
     * on that in the code that actually frees the native object is the wrong
     * trade.
     *
     * If the thread is still alive even after that, the track is *not*
     * released — leaking it is safer than risking a use-after-free, and this
     * object's own reference to it is dropped either way so nothing else
     * mistakes it for current.
     */
    private fun releaseCurrentTrack() {
        val renderThread = thread
        thread = null
        if (renderThread != null && renderThread.isAlive) {
            renderThread.join(RELEASE_JOIN_TIMEOUT_MS)
        }
        if (renderThread == null || !renderThread.isAlive) {
            track?.release()
        }
        track = null
    }

    override fun release() {
        stop()
        releaseCurrentTrack()
    }
}
