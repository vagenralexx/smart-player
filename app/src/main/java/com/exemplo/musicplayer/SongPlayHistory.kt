package com.exemplo.musicplayer

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Registo de uma reprodução individual de uma música.
 * Guarda snapshot dos metadados no momento da reprodução.
 */
@Entity(tableName = "song_play_history")
data class SongPlayHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId:      Long,
    val title:       String,
    val artist:      String,
    val album:       String,
    val albumArtUri: String?,
    /** Timestamp Unix em milissegundos */
    val playedAt:    Long = System.currentTimeMillis()
)
