package com.hereliesaz.sirmatchalot.data

import com.hereliesaz.sirmatchalot.analysis.TrackAnalysis

/**
 * The columns of a [Track] that analysis owns, and nothing else.
 *
 * Both the background [com.hereliesaz.sirmatchalot.analysis.AnalysisService] and
 * the in-app single-track path measure a track and store the result. They used
 * to build the row by copying a snapshot of the whole [Track], which wrote stale
 * copies of every other column back over whatever had changed while the
 * analysis ran. This is what [TrackDao.updateAnalysis] writes instead.
 */
data class AnalysisColumns(
    val bpm: Double?,
    val tempoConfidence: Float,
    val firstBeatSeconds: Double?,
    val downbeatOffset: Int,
    val camelotKey: String?,
    val keyName: String?,
    val keyConfidence: Float,
    val energyLevel: Int?,
    val durationMs: Long,
    val sampleRate: Int,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val peaksPath: String?,
    val energyPath: String?,
    val analysisVersion: Int = Track.CURRENT_ANALYSIS_VERSION,
) {
    companion object {
        /**
         * The columns for [analysis] of audio decoded at [sampleRate], whose
         * kept span starts [trimmedStartFrames] in and lasts [frameCount] frames.
         */
        fun from(
            analysis: TrackAnalysis,
            sampleRate: Int,
            trimmedStartFrames: Int,
            frameCount: Int,
            peaksPath: String?,
            energyPath: String?,
        ): AnalysisColumns {
            val rate = sampleRate.toDouble()
            return AnalysisColumns(
                bpm = analysis.bpm,
                tempoConfidence = analysis.tempoConfidence,
                firstBeatSeconds = analysis.beatGrid?.firstBeatSeconds,
                downbeatOffset = analysis.beatGrid?.downbeatOffset ?: 0,
                camelotKey = analysis.camelotKey,
                keyName = analysis.keyName,
                keyConfidence = analysis.keyConfidence,
                energyLevel = analysis.energyLevel,
                durationMs = (analysis.durationSeconds * 1000).toLong(),
                sampleRate = sampleRate,
                trimStartMs = (trimmedStartFrames / rate * 1000).toLong(),
                trimEndMs = ((trimmedStartFrames.toLong() + frameCount) / rate * 1000).toLong(),
                peaksPath = peaksPath,
                energyPath = energyPath,
            )
        }
    }
}
