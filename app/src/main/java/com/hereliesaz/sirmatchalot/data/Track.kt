package com.hereliesaz.sirmatchalot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val bpm: Int,
    val keyName: String,
    val camelotKey: String,
    val progression: String,
    val atmosphere: String,
    val energyLevel: Int,
    val mixTips: String,
    val youtubeId: String?,
    val localPath: String?, // Absolute path to file on storage (null if streaming)
    val isUserAdded: Boolean = true,
    val cuePoint1: Float? = null,
    val cuePoint2: Float? = null,
    val cuePoint3: Float? = null,
    val cuePoint4: Float? = null,
    val durationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val peaksPath: String? = null
)
