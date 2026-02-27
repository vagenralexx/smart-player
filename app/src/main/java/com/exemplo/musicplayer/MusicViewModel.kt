package com.exemplo.musicplayer

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

// ---------------------------------------------------------------------------
// Enums de estado do player
// ---------------------------------------------------------------------------

enum class RepeatMode(val playerValue: Int) {
    OFF(Player.REPEAT_MODE_OFF),
    ALL(Player.REPEAT_MODE_ALL),
    ONE(Player.REPEAT_MODE_ONE);

    companion object {
        fun fromPlayer(value: Int) = entries.find { it.playerValue == value } ?: OFF
    }
}

enum class AppTab { HOME, LIBRARY, PLAYLISTS }

/** Sub-tab da aba Biblioteca */
enum class LibraryTab(val label: String) {
    SONGS("Músicas"),
    ARTISTS("Artistas"),
    ALBUMS("Álbuns")
}

// ---------------------------------------------------------------------------
// Estado global da UI
// ---------------------------------------------------------------------------

data class MusicUiState(
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val filteredSongs: List<Song> = emptyList(),
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null,
    // Controles de reprodução
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    /** Últimas músicas tocadas (máx 30) */
    val recentSongs: List<Song> = emptyList(),
    /** Contagem de reproduções por artista (para sugestões) */
    val artistPlayCount: Map<String, Int> = emptyMap(),
    /** Mostra modal de atualização disponível */
    val updateInfo: UpdateChecker.UpdateInfo? = null,
    /** Mostra modal de partilha WhatsApp */
    val showShareModal: Boolean = false,
    // Navegação principal
    val activeTab: AppTab = AppTab.HOME,
    // Sub-tabs da biblioteca
    val activeLibraryTab: LibraryTab = LibraryTab.SONGS,
    // Player em ecrã completo
    val showNowPlaying: Boolean = false,
    // Detalhe artista / álbum
    val openArtistName: String? = null,
    val openAlbumName: String? = null,
    // Playlists
    val playlists: List<PlaylistEntity> = emptyList(),
    /** playlistId → lista ordenada de songIds */
    val playlistSongIds: Map<Long, List<Long>> = emptyMap(),
    /** ID da playlist aberta no detalhe (null = nenhuma) */
    val openPlaylistId: Long? = null,
    /** Histórico de reproduções (mais recentes primeiro) */
    val playHistory: List<SongPlayHistory> = emptyList(),
    /** Mostra ecrã de pesquisa global */
    val showSearch: Boolean = false,
    /** Query da pesquisa global */
    val globalSearchQuery: String = "",
    /** Mostra ecrã Wrapped anual */
    val showWrapped: Boolean = false,
    /** Dados do Wrapped calculados */
    val wrappedData: WrappedData? = null
)

/** Dados calculados para o Wrapped anual */
data class WrappedData(
    val year: Int,
    val totalPlays: Int,
    val estimatedHours: Float,
    val topSongs: List<TopSongRow>,
    val topArtists: List<TopArtistRow>
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val db         = AppDatabase.getInstance(application)

    private val prefs = application.getSharedPreferences("smartplayer_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(MusicUiState(isLoading = true))
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    private var mediaController: MediaController? = null
    private var positionJob: Job? = null

    /** Receptor de broadcasts do widget (botões play/pause/skip) */
    private val widgetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PlayerWidget.ACTION_PLAY_PAUSE -> togglePlayPause()
                PlayerWidget.ACTION_SKIP_NEXT  -> skipToNext()
                PlayerWidget.ACTION_SKIP_PREV  -> skipToPrevious()
            }
        }
    }

    init {
        loadSongs()
        checkForUpdate()
        // Observa a lista de playlists em tempo real
        viewModelScope.launch {
            db.playlistDao().getAllPlaylists().collect { list ->
                _uiState.update { it.copy(playlists = list) }
            }
        }
        // Observa todos os (playlistId → songId) de todas as playlists em tempo real
        viewModelScope.launch {
            db.playlistDao().getAllPlaylistSongRefs().collect { refs ->
                val map = refs.groupBy({ it.playlistId }, { it.songId })
                _uiState.update { it.copy(playlistSongIds = map) }
            }
        }
        // Observa o histórico de reproduções em tempo real
        viewModelScope.launch {
            db.historyDao().getAll().collect { history ->
                _uiState.update { it.copy(playHistory = history) }
            }
        }

        // Regista receptor dos botões do widget
        val filter = IntentFilter().apply {
            addAction(PlayerWidget.ACTION_PLAY_PAUSE)
            addAction(PlayerWidget.ACTION_SKIP_NEXT)
            addAction(PlayerWidget.ACTION_SKIP_PREV)
        }
        ContextCompat.registerReceiver(
            application, widgetReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // ---- Player Listener -------------------------------------------------------
    // Garante que isPlaying, currentSong, shuffle e repeat reflectem sempre
    // o estado real do ExoPlayer — independente da causa da mudança
    // (chamada telefônica, headset desconectado, música terminou, etc.).

    private val playerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            // Actualiza widget
            _uiState.value.currentSong?.let { song ->
                PlayerWidget.updateWidgets(
                    getApplication(), song.title, song.artistOrUnknown,
                    isPlaying, song.albumArtUri?.toString()
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaId = mediaItem?.mediaId?.toLongOrNull()
            val song    = _uiState.value.songs.firstOrNull { it.id == mediaId }
            if (song != null) {
                // Actualiza lista de Recentes (sem duplicados consecutivos, máx 30)
                val newRecent = (_uiState.value.recentSongs.filterNot { it.id == song.id })
                    .toMutableList().also { it.add(0, song) }.take(30)

                // Contagem por artista
                val newCount = _uiState.value.artistPlayCount.toMutableMap()
                newCount[song.artistOrUnknown] = (newCount[song.artistOrUnknown] ?: 0) + 1

                // Contador global persistido (trigger do modal a 5, 15 e 30 plays)
                val totalPlays = prefs.getInt("total_plays", 0) + 1
                prefs.edit().putInt("total_plays", totalPlays).apply()
                val showModal = totalPlays == 5 || totalPlays == 15 || totalPlays == 30

                // Grava no histórico Room DB
                viewModelScope.launch {
                    db.historyDao().insertPlay(
                        SongPlayHistory(
                            songId      = song.id,
                            title       = song.title,
                            artist      = song.artistOrUnknown,
                            album       = song.albumOrUnknown,
                            albumArtUri = song.albumArtUri?.toString()
                        )
                    )
                }

                _uiState.update { it.copy(
                    currentSong    = song,
                    recentSongs    = newRecent,
                    artistPlayCount = newCount,
                    showShareModal = it.showShareModal || showModal
                ) }
                // Actualiza widget com nova música
                PlayerWidget.updateWidgets(
                    getApplication(), song.title, song.artistOrUnknown,
                    true, song.albumArtUri?.toString()
                )
            } else {
                _uiState.update { it.copy(currentSong = song) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _uiState.update { it.copy(isPlaying = false, currentPositionMs = 0L) }
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _uiState.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _uiState.update { it.copy(repeatMode = RepeatMode.fromPlayer(repeatMode)) }
        }
    }

    fun setMediaController(controller: MediaController) {
        mediaController = controller
        controller.addListener(playerListener)
        startPositionTracking()
    }

    // ---- Rastreamento de posição (a cada 500 ms) --------------------------------

    private fun startPositionTracking() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                delay(500)
                try {
                    mediaController?.let { ctrl ->
                        val pos = ctrl.currentPosition.coerceAtLeast(0L)
                        val dur = if (ctrl.duration == C.TIME_UNSET) 0L
                                  else ctrl.duration.coerceAtLeast(0L)
                        _uiState.update { it.copy(currentPositionMs = pos, totalDurationMs = dur) }
                    }
                } catch (_: Exception) {
                    // Controller pode estar desconectado temporariamente — ignora e continua
                }
            }
        }
    }

    // ---- Biblioteca ------------------------------------------------------------

    fun loadSongs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val songs = repository.getAllSongs()
                _uiState.update { it.copy(songs = songs, filteredSongs = songs, isLoading = false) }
            } catch (e: SecurityException) {
                _uiState.update {
                    it.copy(isLoading = false,
                        errorMessage = "Permissão negada. Conceda acesso ao armazenamento nas definições.")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Erro ao carregar músicas: ${e.message}")
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { state ->
            val filtered = if (query.isBlank()) state.songs
            else state.songs.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.artistOrUnknown.contains(query, ignoreCase = true) ||
                it.albumOrUnknown.contains(query, ignoreCase = true)
            }
            state.copy(searchQuery = query, filteredSongs = filtered)
        }
    }

    fun setActiveTab(tab: AppTab) = _uiState.update { it.copy(activeTab = tab) }
    fun setLibraryTab(tab: LibraryTab) = _uiState.update { it.copy(activeLibraryTab = tab) }
    fun setShowNowPlaying(show: Boolean) = _uiState.update { it.copy(showNowPlaying = show) }
    fun openArtistDetail(name: String) = _uiState.update { it.copy(openArtistName = name, openAlbumName = null) }
    fun openAlbumDetail(name: String) = _uiState.update { it.copy(openAlbumName = name, openArtistName = null) }
    fun closeSubDetail() = _uiState.update { it.copy(openArtistName = null, openAlbumName = null) }
    fun dismissShareModal() = _uiState.update { it.copy(showShareModal = false) }
    fun dismissUpdateModal() = _uiState.update { it.copy(updateInfo = null) }
    fun setShowSearch(show: Boolean) = _uiState.update { it.copy(showSearch = show, globalSearchQuery = if (!show) "" else it.globalSearchQuery) }
    fun onGlobalSearchQuery(q: String) = _uiState.update { it.copy(globalSearchQuery = q) }
    fun setShowWrapped(show: Boolean) = _uiState.update { it.copy(showWrapped = show) }

    fun loadWrapped(year: Int = Calendar.getInstance().get(Calendar.YEAR)) {
        viewModelScope.launch {
            val cal = Calendar.getInstance()
            cal.set(year, Calendar.JANUARY, 1, 0, 0, 0); cal.set(Calendar.MILLISECOND, 0)
            val from = cal.timeInMillis
            cal.set(year + 1, Calendar.JANUARY, 1, 0, 0, 0)
            val to = cal.timeInMillis
            val topSongs   = db.historyDao().getTopSongsByYear(from, to, 5)
            val topArtists = db.historyDao().getTopArtistsByYear(from, to, 5)
            val totalPlays = db.historyDao().getByYear(from, to).size
            val hours      = totalPlays * 3f / 60f   // estimativa: 3 min/música
            _uiState.update { it.copy(
                wrappedData = WrappedData(year, totalPlays, hours, topSongs, topArtists),
                showWrapped = true
            ) }
        }
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            val info = UpdateChecker.checkForUpdate(getApplication())
            if (info != null) _uiState.update { it.copy(updateInfo = info) }
        }
    }

    // ---- Controles de reprodução -----------------------------------------------

    /** Toca [song] dentro de [queue]. Por defeito a fila é a lista filtrada actual. */
    fun playSong(song: Song, queue: List<Song> = _uiState.value.filteredSongs) {
        val controller = mediaController ?: return
        val index = queue.indexOfFirst { it.id == song.id }
        val items = queue.map { s ->
            MediaItem.Builder()
                .setUri(s.uri)
                .setMediaId(s.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artistOrUnknown)
                        .setAlbumTitle(s.albumOrUnknown)
                        .setArtworkUri(s.albumArtUri)
                        .setTrackNumber(s.track)
                        .setRecordingYear(s.year.takeIf { it > 0 })
                        .build()
                ).build()
        }
        controller.setMediaItems(items, index.coerceAtLeast(0), 0L)
        controller.prepare()
        controller.play()
        _uiState.update { it.copy(currentSong = song, isPlaying = true) }
    }

    fun togglePlayPause() {
        val ctrl = mediaController ?: return
        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
    }

    fun skipToNext()     { mediaController?.seekToNextMediaItem() }
    fun skipToPrevious() { mediaController?.seekToPreviousMediaItem() }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
        _uiState.update { it.copy(currentPositionMs = positionMs) }
    }

    fun toggleShuffle() {
        val ctrl = mediaController ?: return
        val new  = !ctrl.shuffleModeEnabled
        ctrl.shuffleModeEnabled = new
        _uiState.update { it.copy(shuffleEnabled = new) }
    }

    /** Cicla: OFF → ALL → ONE → OFF */
    fun cycleRepeatMode() {
        val ctrl = mediaController ?: return
        val next = when (ctrl.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else                   -> Player.REPEAT_MODE_OFF
        }
        ctrl.repeatMode = next
        _uiState.update { it.copy(repeatMode = RepeatMode.fromPlayer(next)) }
    }

    // ---- Playlists -------------------------------------------------------------

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { db.playlistDao().createPlaylist(PlaylistEntity(name = name.trim())) }
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch { db.playlistDao().deletePlaylist(playlist) }
    }

    fun renamePlaylist(id: Long, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { db.playlistDao().renamePlaylist(id, newName.trim()) }
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            db.playlistDao().addSongToPlaylist(
                PlaylistSongRef(playlistId = playlistId, songId = songId)
            )
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch { db.playlistDao().removeSongFromPlaylist(playlistId, songId) }
    }

    fun openPlaylist(playlistId: Long?) {
        _uiState.update { it.copy(openPlaylistId = playlistId) }
    }

    fun playPlaylist(playlist: PlaylistEntity) {
        val state = _uiState.value
        val ids   = state.playlistSongIds[playlist.id] ?: return
        val songs = ids.mapNotNull { id -> state.songs.firstOrNull { it.id == id } }
        if (songs.isNotEmpty()) playSong(songs.first(), songs)
    }

    // ---- Cleanup ---------------------------------------------------------------

    override fun onCleared() {
        mediaController?.removeListener(playerListener)
        positionJob?.cancel()
        try { getApplication<Application>().unregisterReceiver(widgetReceiver) } catch (_: Exception) {}
        super.onCleared()
    }
}
