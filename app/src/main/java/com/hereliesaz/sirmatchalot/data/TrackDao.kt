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

    @Delete
    suspend fun deleteTrack(track: Track)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackById(id: String)
}
