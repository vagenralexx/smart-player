package com.exemplo.musicplayer

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Tabela de playlists criadas pelo utilizador.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Tabela de junção playlist ↔ música.
 * Armazena apenas o ID da música (Long) para cruzar com o MediaStore em memória.
 */
@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE   // apaga as músicas quando a playlist é removida
        )
    ]
)
data class PlaylistSongRef(
    val playlistId: Long,
    val songId: Long,
    val position: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)
