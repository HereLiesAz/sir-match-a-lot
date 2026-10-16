package com.hereliesaz.sirmatchalot.desktop

import com.hereliesaz.sirmatchalot.audio.AudioOutput
import com.hereliesaz.sirmatchalot.audio.Deck
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Plays through a [SourceDataLine] on a dedicated render thread.
 *
 * The desktop analogue of `AudioTrackOutput` in `:app` — same blocking-write
 * render loop, same idle-standdown behaviour, because both implement the one
 * [AudioOutput] contract [com.hereliesaz.sirmatchalot.audio.AudioEngine]
 * actually talks to. The difference is entirely in how a buffer becomes
 * sound: `javax.sound.sampled` here, `AudioTrack` there.
 *
 * 16-bit PCM, not float — `PCM_FLOAT` support in `SourceDataLine`
 * implementations is inconsistent across platforms and mixers in a way
 * `AudioTrack`'s `ENCODING_PCM_FLOAT` is not, so [render]'s float buffer is
 * quantised here rather than risk a line that silently refuses to open.
 */
class DesktopAudioOutput(
    override val sampleRate: Int = 44_100,
    override val framesPerBuffer: Int = 512,
    /** Where lines come from — the system mixer, or a fake in tests. */
    private val openLine: (AudioFormat) -> SourceDataLine = { AudioSystem.getSourceDataLine(it) },
) : AudioOutput {

    /** Says whether nothing is currently sounding — see [AudioOutput.configureIdleTracking]. */
    @Volatile
    private var isIdle: (() -> Boolean)? = null

    @Volatile
    private var onStandDown: (() -> Unit)? = null

    /** True while the output is parked because nothing was sounding. */
    @Volatile
    var isStoodDown: Boolean = false
        private set

    private val gate = Object()

    /** Guards [line] and [thread] against [start] and [stop] racing. */
    private val lifecycle = Any()
    private var line: SourceDataLine? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    /**
     * Told when [start] could not open a line at all — no audio hardware, or
     * none matching this format — or when the render loop itself fails (the
     * render callback or the line throwing). A phone essentially always has
     * an output; a desktop, especially a fresh VM or a headless build box,
     * may not, and that must reach whoever asked for playback rather than
     * crash the render thread with an uncaught exception. After an error the
     * output is stopped and its line closed; [start] may be called again.
     */
    @Volatile
    var onError: ((Throwable) -> Unit)? = null

    override fun configureIdleTracking(isIdle: () -> Boolean, onStandDown: () -> Unit) {
        this.isIdle = isIdle
        this.onStandDown = onStandDown
    }

    override fun start(render: (FloatArray, Int) -> Unit) {
        synchronized(lifecycle) { startLocked(render) }
    }

    private fun startLocked(render: (FloatArray, Int) -> Unit) {
        if (running.getAndSet(true)) return

        val format = AudioFormat(sampleRate.toFloat(), BITS_PER_SAMPLE, Deck.CHANNELS, true, false)
        val opened = runCatching {
            openLine(format).apply {
                // Three render buffers of headroom, matching AudioTrackOutput's
                // own margin — enough that a late render call doesn't starve the
                // line outright, without adding much latency.
                open(format, framesPerBuffer * Deck.CHANNELS * BYTES_PER_SAMPLE * 3)
            }
        }.getOrElse {
            running.set(false)
            onError?.invoke(it)
            return
        }
        line = opened

        val idleBlocksBeforeStandDown =
            (sampleRate / framesPerBuffer.coerceAtLeast(1)).coerceAtLeast(1)

        thread = Thread {
            try {
                renderLoop(opened, render, idleBlocksBeforeStandDown)
            } catch (t: Throwable) {
                // Only report a failure that happened while still meant to be
                // playing — a write that throws because stop() closed the line
                // underneath it is the expected way out, not an error.
                if (running.getAndSet(false)) onError?.invoke(t)
            } finally {
                isStoodDown = false
                runCatching { opened.stop() }
                runCatching { opened.close() }
            }
        }.apply {
            name = "SirMatchALot-Audio"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun renderLoop(
        opened: SourceDataLine,
        render: (FloatArray, Int) -> Unit,
        idleBlocksBeforeStandDown: Int,
    ) {
        val floatBuffer = FloatArray(framesPerBuffer * Deck.CHANNELS)
        val byteBuffer = ByteArray(framesPerBuffer * Deck.CHANNELS * BYTES_PER_SAMPLE)
        opened.start()
        var idleBlocks = 0
        var paused = false

        while (running.get()) {
            if (isIdle?.invoke() == true) idleBlocks++ else idleBlocks = 0

            if (idleBlocks >= idleBlocksBeforeStandDown) {
                if (!paused) {
                    runCatching {
                        opened.stop()
                        opened.flush()
                    }
                    paused = true
                    isStoodDown = true
                    onStandDown?.invoke()
                }
                synchronized(gate) {
                    if (running.get()) runCatching { gate.wait(IDLE_POLL_MILLIS) }
                }
                continue
            }

            if (paused) {
                runCatching { opened.start() }
                paused = false
                isStoodDown = false
            }

            render(floatBuffer, framesPerBuffer)
            floatToPcm16(floatBuffer, byteBuffer)
            var offset = 0
            while (offset < byteBuffer.size && running.get()) {
                val written = opened.write(byteBuffer, offset, byteBuffer.size - offset)
                if (written <= 0) break
                offset += written
            }
        }
    }

    override fun wake() {
        synchronized(gate) { gate.notifyAll() }
    }

    /**
     * Stops the render thread and closes the line. Safe to call repeatedly,
     * before [start], or after an error; [start] works again afterwards.
     */
    override fun stop() {
        synchronized(lifecycle) { stopLocked() }
    }

    private fun stopLocked() {
        running.set(false)
        wake()
        val renderThread = thread
        val openLine = line
        thread = null
        line = null
        if (renderThread != null && renderThread !== Thread.currentThread()) {
            renderThread.join(STOP_JOIN_TIMEOUT_MS)
            if (renderThread.isAlive) {
                // Still blocked in a write — closing the line releases it.
                runCatching { openLine?.close() }
                renderThread.join(STOP_JOIN_TIMEOUT_MS)
            }
        }
        runCatching { openLine?.close() }
        isStoodDown = false
    }

    override fun release() {
        stop()
    }

    companion object {
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = 2
        private const val STOP_JOIN_TIMEOUT_MS = 1_000L

        /** Same reasoning as `AudioTrackOutput.IDLE_POLL_MILLIS`. */
        const val IDLE_POLL_MILLIS = 20L

        /** Interleaved float PCM (-1..1) to little-endian signed 16-bit PCM bytes. */
        private fun floatToPcm16(floats: FloatArray, out: ByteArray) {
            for (i in floats.indices) {
                val sample = (floats[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                val byteIndex = i * BYTES_PER_SAMPLE
                out[byteIndex] = (sample.toInt() and 0xFF).toByte()
                out[byteIndex + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
        }
    }
}
