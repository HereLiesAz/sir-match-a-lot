package com.hereliesaz.sirmatchalot.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks")
    fun getAllTracksFlow(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): Track?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: Track)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<Track>)

    @Update
    suspend fun updateTrack(track: Track)

    @Delete
    suspend fun deleteTrack(track: Track)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackById(id: String)
}
