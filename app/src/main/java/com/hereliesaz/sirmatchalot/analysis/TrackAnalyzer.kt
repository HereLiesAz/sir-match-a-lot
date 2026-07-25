package com.hereliesaz.sirmatchalot.analysis

import com.hereliesaz.sirmatchalot.audio.PcmBuffer
import com.hereliesaz.sirmatchalot.domain.HarmonicEngine
import com.hereliesaz.sirmatchalot.dsp.BeatGrid
import com.hereliesaz.sirmatchalot.dsp.EnergyCurve
import com.hereliesaz.sirmatchalot.dsp.KeyDetector
import com.hereliesaz.sirmatchalot.dsp.OnsetDetector
import com.hereliesaz.sirmatchalot.dsp.PeakEnvelope
import com.hereliesaz.sirmatchalot.dsp.TempoDetector

/**
 * Everything measured from one track.
 *
 * Every field here is derived from the audio signal. Nothing is estimated from a
 * filename, a title, or a random number generator — which is what the previous
 * implementation did for [bpm] and [camelotKey], the two values the entire
 * harmonic-mixing feature set depends on.
 *
 * @param bpm measured tempo, or null when no periodicity was found (ambient
 *   material, spoken word, a sample too short to have a tempo).
 * @param tempoConfidence 0..1, how clearly the winning tempo stood out.
 * @param beatGrid where beats and bars fall, present only when [bpm] is.
 * @param camelotKey Camelot code, or null when no tonal centre was found.
 * @param keyName human-readable key, e.g. "A minor".
 * @param keyConfidence 0..1, margin over the runner-up key.
 * @param energyCurve energy over time, for the ring around the platter.
 * @param peaks min/max envelope for drawing the waveform.
 */
data class TrackAnalysis(
    val durationSeconds: Double,
    val sampleRate: Int,
    val bpm: Double?,
    val tempoConfidence: Float,
    val beatGrid: BeatGrid?,
    val camelotKey: String?,
    val keyName: String?,
    val keyConfidence: Float,
    val energyCurve: EnergyCurve,
    val peaks: PeakEnvelope,
) {
    /** 1..10 energy rating for the library UI. */
    val energyLevel: Int get() = energyCurve.energyLevel()

    /** True when both tempo and key were measured, so harmonic mixing is meaningful. */
    val isFullyAnalysed: Boolean get() = bpm != null && camelotKey != null
}

/**
 * Runs the whole `dsp` pipeline over a decoded track.
 *
 * One decode feeds every measurement. The onset envelope is computed once and
 * reused by both the tempo detector and the downbeat inference, and the mono
 * downmix is computed once and shared by all of them — the previous code path
 * decoded separately for peaks and would have needed a third pass for anything
 * else.
 *
 * Pure with respect to Android: takes a [PcmBuffer], returns a [TrackAnalysis].
 * That is what makes it testable against synthetic signals with known tempo and
 * key.
 */
class TrackAnalyzer(
    private val onsetDetector: OnsetDetector = OnsetDetector(),
    private val tempoDetector: TempoDetector = TempoDetector(),
    private val keyDetector: KeyDetector = KeyDetector(),
    private val peakBuckets: Int = 2048,
) {
    /**
     * Measures [pcm].
     *
     * **On the confidence thresholds.** They default to zero, which is a
     * deliberate choice rather than an oversight. "No tempo" is decided
     * structurally, not by a threshold: [TempoDetector] returns null when the
     * onset envelope has no periodicity at all, which is what silence and
     * unpitched ambience produce. A threshold on top of that would be a second,
     * weaker gate — and an earlier revision set it to 0.05, which was an
     * uncalibrated guess that discarded a correctly measured 100 BPM.
     *
     * The confidence figures are not probabilities and are not calibrated across
     * tempi; they are relative peak prominence. So they are *surfaced* — stored
     * on the track and shown in the library — rather than used to hide a
     * measurement behind a number nobody chose on evidence. Callers that want to
     * be stricter can raise these.
     *
     * @param minimumTempoConfidence reject a tempo measuring below this.
     * @param minimumKeyConfidence reject a key measuring below this.
     */
    fun analyse(
        pcm: PcmBuffer,
        minimumTempoConfidence: Float = 0f,
        minimumKeyConfidence: Float = 0f,
    ): TrackAnalysis {
        val mono = pcm.toMonoFloat()
        val sampleRate = pcm.sampleRate

        val onsets = onsetDetector.detect(mono, sampleRate)
        val tempo = tempoDetector.detect(onsets)
            ?.takeIf { it.confidence >= minimumTempoConfidence }

        val beatGrid = tempo?.let {
            BeatGrid(
                bpm = it.bpm,
                firstBeatSeconds = it.firstBeatSeconds,
                downbeatOffset = BeatGrid.inferDownbeat(onsets, it.bpm, it.firstBeatSeconds),
            )
        }

        val key = keyDetector.detect(mono, sampleRate)
            ?.takeIf { it.confidence >= minimumKeyConfidence }

        return TrackAnalysis(
            durationSeconds = pcm.durationSeconds,
            sampleRate = sampleRate,
            bpm = tempo?.bpm,
            tempoConfidence = tempo?.confidence ?: 0f,
            beatGrid = beatGrid,
            camelotKey = key?.let { HarmonicEngine.camelotFor(it) },
            keyName = key?.name,
            keyConfidence = key?.confidence ?: 0f,
            energyCurve = EnergyCurve.compute(mono, sampleRate),
            peaks = PeakEnvelope.compute(mono, peakBuckets),
        )
    }
}
