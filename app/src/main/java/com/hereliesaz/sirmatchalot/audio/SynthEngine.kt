package com.hereliesaz.sirmatchalot.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

class SynthEngine(private val context: Context) {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        .build()

    private val sampleMap = mutableMapOf<Int, Int>()

    private var audioTrack: AudioTrack? = null
    private var synthThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    @Volatile var frequency: Float = 220f
    @Volatile var filterCutoff: Float = 0.5f
    @Volatile var delayFeedback: Float = 0f
    @Volatile var isStutterActive: Boolean = false
    @Volatile var synthVolume: Float = 0.2f

    fun playSample(padId: Int) {
        val soundId = sampleMap[padId]
        if (soundId != null) {
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        } else {
            playSynthesizedSamplerSound(padId)
        }
    }

    private fun playSynthesizedSamplerSound(padId: Int) {
        Thread {
            val sampleRate = 44100
            val duration = when (padId) {
                1 -> 0.3f
                2 -> 0.25f
                3 -> 0.12f
                else -> 0.4f
            }
            val numSamples = (duration * sampleRate).toInt()
            val buffer = ShortArray(numSamples)

            for (i in 0 until numSamples) {
                val t = i.toFloat() / sampleRate
                buffer[i] = when (padId) {
                    1 -> {
                        val freq = 150f * (1.0f - t / duration) + 30f
                        val phase = 2.0 * Math.PI * freq * t
                        (sin(phase) * 28000f * (1.0f - t / duration)).toInt().toShort()
                    }
                    2 -> {
                        val noise = (Math.random() * 2.0 - 1.0) * 15000f
                        val tone = sin(2.0 * Math.PI * 180.0 * t) * 10000f * (1.0f - t / duration)
                        ((noise + tone) * (1.0f - t / duration)).toInt().toShort()
                    }
                    3 -> {
                        val noise = (Math.random() * 2.0 - 1.0) * 24000f
                        (noise * (1.0f - t / duration) * (1.0f - t / duration)).toInt().toShort()
                    }
                    else -> {
                        val phase = 2.0 * Math.PI * 330.0 * t
                        val wave = if (sin(phase) > 0) 12000f else -12000f
                        (wave * (1.0f - t / duration)).toInt().toShort()
                    }
                }
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(buffer, 0, buffer.size)
            track.play()
            Thread.sleep((duration * 1000f).toLong() + 50)
            track.release()
        }.start()
    }

    fun startSynth() {
        if (isRunning.get()) return
        isRunning.set(true)

        val sampleRate = 44100
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        synthThread = Thread {
            audioTrack?.play()
            val buffer = ShortArray(512)
            var phase = 0.0

            val delayBuffer = FloatArray(22050)
            var delayWriteHead = 0

            var stutterCounter = 0
            var isStutterGated = false

            while (isRunning.get()) {
                val currentFreq = frequency
                val currentVol = synthVolume
                val feedback = delayFeedback
                val isStutter = isStutterActive
                
                val phaseIncrement = (2.0 * Math.PI * currentFreq) / sampleRate

                for (i in buffer.indices) {
                    phase += phaseIncrement
                    if (phase > 2.0 * Math.PI) {
                        phase -= 2.0 * Math.PI
                    }

                    val rawSample = (sin(phase) + (sin(2 * phase) / 2.0) + (sin(3 * phase) / 3.0)).toFloat()
                    val cutoff = filterCutoff
                    val filteredSample = rawSample * cutoff

                    val delayReadHead = (delayWriteHead - 11025 + delayBuffer.size) % delayBuffer.size
                    val delayOutput = delayBuffer[delayReadHead]
                    val mixedSample = filteredSample + delayOutput * feedback

                    delayBuffer[delayWriteHead] = mixedSample
                    delayWriteHead = (delayWriteHead + 1) % delayBuffer.size

                    var finalVol = currentVol
                    if (isStutter) {
                        stutterCounter++
                        if (stutterCounter >= 2750) {
                            isStutterGated = !isStutterGated
                            stutterCounter = 0
                        }
                        if (isStutterGated) {
                            finalVol = 0f
                        }
                    }

                    buffer[i] = (mixedSample * finalVol * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }

                audioTrack?.write(buffer, 0, buffer.size)
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stopSynth() {
        isRunning.set(false)
        synthThread?.join()
        synthThread = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    fun release() {
        stopSynth()
        soundPool.release()
    }
}
