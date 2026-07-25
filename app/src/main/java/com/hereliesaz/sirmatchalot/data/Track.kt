package com.hereliesaz.sirmatchalot.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A track in the library.
 *
 * The analysis columns are **nullable on purpose**. Previously `bpm` was a
 * non-null `Int` and `camelotKey` a non-null `String`, which left no way to say
 * "not measured" — so the code filled them with a hash of the filename or
 * `(90..150).random()`, and every downstream feature treated those inventions as
 * fact. A null here means the value is genuinely unknown, and the UI says so
 * rather than showing a confident number nobody should mix against.
 *
 * @param id stable identity. A random UUID, not a hash of the file path: the
 *   previous `path.hashCode().toString()` was a 32-bit value used as a primary
 *   key, where collisions silently overwrite a different track.
 * @param sourceUri content URI or file path the audio came from.
 * @param bpm measured tempo, null if none was found.
 * @param camelotKey measured key as a Camelot code, null if none was found.
 * @param firstBeatSeconds phase of the beat grid.
 * @param downbeatOffset which beat of the bar carries the downbeat.
 * @param peaksPath file holding the serialised [com.hereliesaz.sirmatchalot.dsp.PeakEnvelope].
 * @param energyPath file holding the serialised energy curve.
 * @param analysisVersion which analyser produced these values, so a future
 *   improvement can re-analyse stale rows instead of trusting them forever.
 */
@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val artist: String,
    val sourceUri: String? = null,

    // --- Measured ---
    val bpm: Double? = null,
    @ColumnInfo(defaultValue = "0") val tempoConfidence: Float = 0f,
    val firstBeatSeconds: Double? = null,
    @ColumnInfo(defaultValue = "0") val downbeatOffset: Int = 0,
    val camelotKey: String? = null,
    val keyName: String? = null,
    @ColumnInfo(defaultValue = "0") val keyConfidence: Float = 0f,
    val energyLevel: Int? = null,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val sampleRate: Int = 0,

    // --- Trimming, measured from the signal ---
    @ColumnInfo(defaultValue = "0") val trimStartMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val trimEndMs: Long = 0L,

    // --- Cached analysis artefacts ---
    val peaksPath: String? = null,
    val energyPath: String? = null,
    @ColumnInfo(defaultValue = "0") val analysisVersion: Int = 0,

    // --- User data ---
    @ColumnInfo(defaultValue = "0") val isUserAdded: Boolean = true,
    val cuePointsCsv: String? = null,
) {
    /** True once this track has been analysed by the current analyser. */
    val isAnalysed: Boolean
        get() = analysisVersion >= CURRENT_ANALYSIS_VERSION

    /** Cue points in seconds, parsed from storage. */
    val cuePoints: List<Double>
        get() = cuePointsCsv
            ?.split(',')
            ?.mapNotNull { it.trim().toDoubleOrNull() }
            ?: emptyList()

    /** Tempo for display, or a dash when it was never measured. */
    fun bpmLabel(): String = bpm?.let { String.format("%.1f", it) } ?: "—"

    /** Key for display, or a dash when it was never measured. */
    fun keyLabel(): String = camelotKey ?: "—"

    companion object {
        /**
         * Bump when the analysis pipeline changes in a way that invalidates
         * stored results, so existing rows are re-analysed rather than trusted.
         */
        const val CURRENT_ANALYSIS_VERSION = 1

        fun cuePointsToCsv(points: List<Double>): String? =
            points.takeIf { it.isNotEmpty() }?.joinToString(",")
    }
}
