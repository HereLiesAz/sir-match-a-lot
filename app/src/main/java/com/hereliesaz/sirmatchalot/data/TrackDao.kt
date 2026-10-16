package com.hereliesaz.sirmatchalot.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks")
    fun getAllTracksFlow(): Flow<List<Track>>

    /**
     * Every track, once.
     *
     * The flow above is what the UI observes; a background run needs a snapshot
     * it can iterate without the list changing underneath it mid-pass.
     */
    @Query("SELECT * FROM tracks")
    suspend fun getAllTracks(): List<Track>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): Track?

    @Query("SELECT * FROM tracks WHERE sourceUri = :sourceUri LIMIT 1")
    suspend fun getTrackBySourceUri(sourceUri: String): Track?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackRaw(track: Track)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<Track>)

    /**
     * Inserts [track], unless a track pointing at the same [Track.sourceUri]
     * is already in the library.
     *
     * Every row gets a fresh random [Track.id], so the `REPLACE` conflict
     * strategy on the primary key never fires for a re-import of the same
     * file — nothing about the id collides. Single-file import (unlike
     * folder import, playlist import, and store-pack import, which all
     * filter against the in-memory library by [Track.sourceUri] before
     * calling in here) had no such check, so opening the same file twice
     * silently duplicated it. Checked here, at the DAO, so every caller of
     * [insertTrack] is covered rather than only the ones that remember to
     * filter first.
     */
    @Transaction
    suspend fun insertTrack(track: Track) {
        val source = track.sourceUri
        if (source != null && getTrackBySourceUri(source) != null) return
        insertTrackRaw(track)
    }

    @Update
    suspend fun updateTrack(track: Track)

    /**
     * Writes the measured columns of one row, and nothing else.
     *
     * Analysis takes seconds to minutes per track, and the row it started from
     * is a snapshot. Writing `track.copy(...)` back through [updateTrack]
     * replaced every column with that snapshot, so an edit made meanwhile — a
     * cue point, a rename, the local copy recorded by playback — was silently
     * reverted when the analysis landed. A targeted update touches only what
     * the analyser owns.
     */
    @Query(
        "UPDATE tracks SET bpm = :bpm, tempoConfidence = :tempoConfidence, " +
            "firstBeatSeconds = :firstBeatSeconds, downbeatOffset = :downbeatOffset, " +
            "camelotKey = :camelotKey, keyName = :keyName, keyConfidence = :keyConfidence, " +
            "energyLevel = :energyLevel, durationMs = :durationMs, sampleRate = :sampleRate, " +
            "trimStartMs = :trimStartMs, trimEndMs = :trimEndMs, peaksPath = :peaksPath, " +
            "energyPath = :energyPath, analysisVersion = :analysisVersion WHERE id = :id",
    )
    suspend fun updateAnalysis(
        id: String,
        bpm: Double?,
        tempoConfidence: Float,
        firstBeatSeconds: Double?,
        downbeatOffset: Int,
        camelotKey: String?,
        keyName: String?,
        keyConfidence: Float,
        energyLevel: Int?,
        durationMs: Long,
        sampleRate: Int,
        trimStartMs: Long,
        trimEndMs: Long,
        peaksPath: String?,
        energyPath: String?,
        analysisVersion: Int,
    )

    /** Applies [columns] to row [id]; see [updateAnalysis]. */
    suspend fun updateAnalysis(id: String, columns: AnalysisColumns) = updateAnalysis(
        id = id,
        bpm = columns.bpm,
        tempoConfidence = columns.tempoConfidence,
        firstBeatSeconds = columns.firstBeatSeconds,
        downbeatOffset = columns.downbeatOffset,
        camelotKey = columns.camelotKey,
        keyName = columns.keyName,
        keyConfidence = columns.keyConfidence,
        energyLevel = columns.energyLevel,
        durationMs = columns.durationMs,
        sampleRate = columns.sampleRate,
        trimStartMs = columns.trimStartMs,
        trimEndMs = columns.trimEndMs,
        peaksPath = columns.peaksPath,
        energyPath = columns.energyPath,
        analysisVersion = columns.analysisVersion,
    )

    /** Records (or, with null, forgets) the local copy of one row's audio, touching nothing else. */
    @Query("UPDATE tracks SET cachedPath = :cachedPath WHERE id = :id")
    suspend fun updateCachedPath(id: String, cachedPath: String?)

    /** Forgets every local copy at once, for "clear local copies". */
    @Query("UPDATE tracks SET cachedPath = NULL WHERE cachedPath IS NOT NULL")
    suspend fun clearCachedPaths()

    @Delete
    suspend fun deleteTrack(track: Track)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackById(id: String)
}
