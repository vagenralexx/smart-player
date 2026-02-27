package com.exemplo.musicplayer

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Insert
    suspend fun insertPlay(entry: SongPlayHistory)

    /** Todas as entradas, mais recentes primeiro */
    @Query("SELECT * FROM song_play_history ORDER BY playedAt DESC")
    fun getAll(): Flow<List<SongPlayHistory>>

    /** Top músicas por número de reproduções (para Wrapped) */
    @Query("""
        SELECT songId, title, artist, album, albumArtUri,
               COUNT(*) as playCount
        FROM song_play_history
        GROUP BY songId
        ORDER BY playCount DESC
        LIMIT :limit
    """)
    suspend fun getTopSongs(limit: Int = 10): List<TopSongRow>

    /** Top artistas por número de reproduções */
    @Query("""
        SELECT artist, COUNT(*) as playCount
        FROM song_play_history
        GROUP BY artist
        ORDER BY playCount DESC
        LIMIT :limit
    """)
    suspend fun getTopArtists(limit: Int = 10): List<TopArtistRow>

    /** Total de reproduções */
    @Query("SELECT COUNT(*) FROM song_play_history")
    suspend fun getTotalPlays(): Int

    /** Tempo total ouvido em ms */
    @Query("SELECT COUNT(*) * 180000 FROM song_play_history")
    suspend fun getEstimatedListenTimeMs(): Long

    /** Reproduções de um ano específico */
    @Query("""
        SELECT * FROM song_play_history
        WHERE playedAt >= :fromMs AND playedAt < :toMs
        ORDER BY playedAt DESC
    """)
    suspend fun getByYear(fromMs: Long, toMs: Long): List<SongPlayHistory>

    /** Top músicas de um ano */
    @Query("""
        SELECT songId, title, artist, album, albumArtUri,
               COUNT(*) as playCount
        FROM song_play_history
        WHERE playedAt >= :fromMs AND playedAt < :toMs
        GROUP BY songId
        ORDER BY playCount DESC
        LIMIT :limit
    """)
    suspend fun getTopSongsByYear(fromMs: Long, toMs: Long, limit: Int = 5): List<TopSongRow>

    /** Top artistas de um ano */
    @Query("""
        SELECT artist, COUNT(*) as playCount
        FROM song_play_history
        WHERE playedAt >= :fromMs AND playedAt < :toMs
        GROUP BY artist
        ORDER BY playCount DESC
        LIMIT :limit
    """)
    suspend fun getTopArtistsByYear(fromMs: Long, toMs: Long, limit: Int = 5): List<TopArtistRow>

    @Query("DELETE FROM song_play_history")
    suspend fun clearAll()
}

/** Resultado agregado por música */
data class TopSongRow(
    val songId:      Long,
    val title:       String,
    val artist:      String,
    val album:       String,
    val albumArtUri: String?,
    val playCount:   Int
)

/** Resultado agregado por artista */
data class TopArtistRow(
    val artist:    String,
    val playCount: Int
)
