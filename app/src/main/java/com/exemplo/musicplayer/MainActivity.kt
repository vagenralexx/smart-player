package com.exemplo.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken)
            .buildAsync()
            .also { future ->
                future.addListener({
                    viewModel.setMediaController(future.get())
                }, MoreExecutors.directExecutor())
            }

        setContent {
            MaterialTheme(
                colorScheme = dynamicColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MusicPlayerApp(viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@Composable
private fun dynamicColorScheme(): ColorScheme =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        androidx.compose.material3.dynamicDarkColorScheme(androidx.compose.ui.platform.LocalContext.current)
    else darkColorScheme()

// ---------------------------------------------------------------------------
// Root — gestão de permissões
// ---------------------------------------------------------------------------
@Composable
fun MusicPlayerApp(viewModel: MusicViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    var permissionGranted by remember { mutableStateOf(false) }
    var permissionDenied  by remember { mutableStateOf(false) }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permissionGranted = ok; if (ok) viewModel.loadSongs() else permissionDenied = true
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        audioLauncher.launch(requiredPermission)
    }

    if (!permissionGranted && permissionDenied)
        PermissionDeniedScreen(onRetry = { audioLauncher.launch(requiredPermission) })
    else
        MainScreen(uiState = uiState, viewModel = viewModel)
}

// ---------------------------------------------------------------------------
// Ecrã principal com Bottom Navigation
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(uiState: MusicUiState, viewModel: MusicViewModel) {
    val openPlaylist = uiState.openPlaylistId?.let { id -> uiState.playlists.find { it.id == id } }

    // Cadeia de BackHandlers: search → wrapped → nowPlaying → artistDetail → albumDetail → playlistDetail
    BackHandler(enabled = uiState.showSearch) { viewModel.setShowSearch(false) }
    BackHandler(enabled = uiState.showWrapped && !uiState.showSearch) { viewModel.setShowWrapped(false) }
    BackHandler(enabled = uiState.showNowPlaying && !uiState.showSearch && !uiState.showWrapped) { viewModel.setShowNowPlaying(false) }
    BackHandler(enabled = !uiState.showNowPlaying && !uiState.showSearch && !uiState.showWrapped &&
            (uiState.openArtistName != null || uiState.openAlbumName != null)) {
        viewModel.closeSubDetail()
    }
    BackHandler(enabled = !uiState.showNowPlaying && !uiState.showSearch && !uiState.showWrapped &&
            uiState.openArtistName == null && uiState.openAlbumName == null && openPlaylist != null) {
        viewModel.openPlaylist(null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                when {
                    uiState.openArtistName != null -> TopAppBar(
                        title = { Text(uiState.openArtistName, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.closeSubDetail() }) {
                                Icon(Icons.Default.ArrowBack, "Voltar")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                    uiState.openAlbumName != null -> TopAppBar(
                        title = { Text(uiState.openAlbumName, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.closeSubDetail() }) {
                                Icon(Icons.Default.ArrowBack, "Voltar")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                    openPlaylist != null -> TopAppBar(
                        title = { Text(openPlaylist.name, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.openPlaylist(null) }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                    uiState.activeTab == AppTab.LIBRARY -> TopAppBar(
                        title = { Text("Smart Player", fontWeight = FontWeight.Bold) },
                        actions = {
                            IconButton(onClick = { viewModel.setShowSearch(true) }) {
                                Icon(Icons.Default.Search, contentDescription = "Pesquisar")
                            }
                            IconButton(onClick = { viewModel.loadSongs() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                    uiState.activeTab == AppTab.HOME -> TopAppBar(
                        title = { Text("Smart Player", fontWeight = FontWeight.Bold) },
                        actions = {
                            IconButton(onClick = { viewModel.loadWrapped() }) {
                                Icon(Icons.Default.BarChart, contentDescription = "Wrapped anual")
                            }
                            IconButton(onClick = { viewModel.setShowSearch(true) }) {
                                Icon(Icons.Default.Search, contentDescription = "Pesquisar")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                    else -> TopAppBar(
                        title = { Text("Playlists", fontWeight = FontWeight.Bold) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                }
            },
            bottomBar = {
                Column {
                    AnimatedVisibility(
                        visible = uiState.currentSong != null,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit  = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    ) {
                        uiState.currentSong?.let { song ->
                            MiniPlayer(
                                song              = song,
                                isPlaying         = uiState.isPlaying,
                                shuffleEnabled    = uiState.shuffleEnabled,
                                repeatMode        = uiState.repeatMode,
                                currentPositionMs = uiState.currentPositionMs,
                                totalDurationMs   = uiState.totalDurationMs,
                                onExpand          = { viewModel.setShowNowPlaying(true) },
                                onPlayPause       = { viewModel.togglePlayPause() },
                                onSkipNext        = { viewModel.skipToNext() },
                                onSkipPrev        = { viewModel.skipToPrevious() },
                                onSeek            = { viewModel.seekTo(it) },
                                onToggleShuffle   = { viewModel.toggleShuffle() },
                                onCycleRepeat     = { viewModel.cycleRepeatMode() }
                            )
                        }
                    }
                    val showNav = openPlaylist == null &&
                            uiState.openArtistName == null && uiState.openAlbumName == null
                    if (showNav) {
                        NavigationBar {
                            NavigationBarItem(
                                selected = uiState.activeTab == AppTab.HOME,
                                onClick  = { viewModel.setActiveTab(AppTab.HOME) },
                                icon     = { Icon(Icons.Default.Home, null) },
                                label    = { Text("Início") }
                            )
                            NavigationBarItem(
                                selected = uiState.activeTab == AppTab.LIBRARY,
                                onClick  = { viewModel.setActiveTab(AppTab.LIBRARY) },
                                icon     = { Icon(Icons.Default.LibraryMusic, null) },
                                label    = { Text("Biblioteca") }
                            )
                            NavigationBarItem(
                                selected = uiState.activeTab == AppTab.PLAYLISTS,
                                onClick  = { viewModel.setActiveTab(AppTab.PLAYLISTS) },
                                icon     = { Icon(Icons.Default.QueueMusic, null) },
                                label    = { Text("Playlists") }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when {
                    uiState.openArtistName != null -> ArtistDetailScreen(
                        artistName  = uiState.openArtistName,
                        songs       = uiState.songs,
                        currentSong = uiState.currentSong,
                        onSongClick = { song ->
                            val queue = uiState.songs
                                .filter { it.artistOrUnknown == uiState.openArtistName }
                                .sortedWith(compareBy({ it.albumOrUnknown }, { it.track }, { it.title }))
                            viewModel.playSong(song, queue)
                        }
                    )
                    uiState.openAlbumName != null -> AlbumDetailScreen(
                        albumName   = uiState.openAlbumName,
                        songs       = uiState.songs,
                        currentSong = uiState.currentSong,
                        onSongClick = { song, queue -> viewModel.playSong(song, queue) }
                    )
                    openPlaylist != null -> {
                        val ids   = uiState.playlistSongIds[openPlaylist.id] ?: emptyList()
                        val songs = ids.mapNotNull { id -> uiState.songs.firstOrNull { it.id == id } }
                        PlaylistDetailScreen(
                            playlist     = openPlaylist,
                            songs        = songs,
                            currentSong  = uiState.currentSong,
                            onSongClick  = { viewModel.playSong(it, songs) },
                            onPlayAll    = { viewModel.playPlaylist(openPlaylist) },
                            onRemoveSong = { viewModel.removeSongFromPlaylist(openPlaylist.id, it.id) }
                        )
                    }
                    uiState.activeTab == AppTab.HOME     -> HomeContent(uiState, viewModel)
                    uiState.activeTab == AppTab.LIBRARY   -> LibraryContent(uiState, viewModel)
                    else                                  -> PlaylistsContent(uiState, viewModel)
                }
            }
        }

        // ---- Ecrã completo Now Playing (desliza de baixo para cima) -----------
        AnimatedVisibility(
            visible  = uiState.showNowPlaying && uiState.currentSong != null,
            enter    = slideInVertically(animationSpec = tween(400), initialOffsetY = { it }) + fadeIn(tween(300)),
            exit     = slideOutVertically(animationSpec = tween(350), targetOffsetY  = { it }) + fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            uiState.currentSong?.let { song ->
                NowPlayingScreen(
                    song              = song,
                    isPlaying         = uiState.isPlaying,
                    shuffleEnabled    = uiState.shuffleEnabled,
                    repeatMode        = uiState.repeatMode,
                    currentPositionMs = uiState.currentPositionMs,
                    totalDurationMs   = uiState.totalDurationMs,
                    onClose           = { viewModel.setShowNowPlaying(false) },
                    onPlayPause       = { viewModel.togglePlayPause() },
                    onSkipNext        = { viewModel.skipToNext() },
                    onSkipPrev        = { viewModel.skipToPrevious() },
                    onSeek            = { viewModel.seekTo(it) },
                    onToggleShuffle   = { viewModel.toggleShuffle() },
                    onCycleRepeat     = { viewModel.cycleRepeatMode() }
                )
            }
        }
        // ---- Modal de atualização disponível --------------------------------
        uiState.updateInfo?.let { info ->
            UpdateDialog(
                info     = info,
                onDismiss = { viewModel.dismissUpdateModal() }
            )
        }

        // ---- Modal de partilha WhatsApp ------------------------------------
        if (uiState.showShareModal) {
            ShareModal(onDismiss = { viewModel.dismissShareModal() })
        }

        // ---- Pesquisa global -----------------------------------------------
        AnimatedVisibility(
            visible  = uiState.showSearch,
            enter    = fadeIn(tween(200)),
            exit     = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize()
        ) {
            GlobalSearchScreen(uiState = uiState, viewModel = viewModel)
        }

        // ---- Wrapped anual -------------------------------------------------
        AnimatedVisibility(
            visible  = uiState.showWrapped,
            enter    = slideInVertically(animationSpec = tween(400), initialOffsetY = { it }) + fadeIn(tween(300)),
            exit     = slideOutVertically(animationSpec = tween(350), targetOffsetY  = { it }) + fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            uiState.wrappedData?.let { data ->
                WrappedScreen(data = data, onClose = { viewModel.setShowWrapped(false) })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Ecrã Início — Recentes + Sugestões
// ---------------------------------------------------------------------------
@Composable
fun HomeContent(uiState: MusicUiState, viewModel: MusicViewModel) {
    if (uiState.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (uiState.songs.isEmpty()) {
        EmptyState(hasQuery = false, modifier = Modifier.fillMaxSize())
        return
    }

    // Stats from history
    val totalHistoryPlays = uiState.playHistory.size
    // Sugestões: faixas de artistas mais tocados que ainda não estão nos recentes
    val suggestions = remember(uiState.artistPlayCount, uiState.recentSongs, uiState.songs) {
        val recentIds = uiState.recentSongs.map { it.id }.toSet()
        val topArtists = uiState.artistPlayCount.entries
            .sortedByDescending { it.value }.map { it.key }.take(3)
        val suggested = topArtists.flatMap { artist ->
            uiState.songs.filter { it.artistOrUnknown == artist && it.id !in recentIds }
                .shuffled().take(4)
        }.take(10)
        // Fallback: músicas aleatórias se não houver histórico suficiente
        if (suggested.size < 4)
            uiState.songs.filter { it.id !in recentIds }.shuffled().take(10 - suggested.size)
                .let { suggested + it }
        else suggested
    }

    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // ---- Card Wrapped (só aparece com histórico suficiente) -----------------
        if (totalHistoryPlays >= 5) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape    = RoundedCornerShape(16.dp),
                    colors   = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    onClick  = { viewModel.loadWrapped() }
                ) {
                    Row(
                        modifier          = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.BarChart, null, modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                "O teu Wrapped está pronto 🎉",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "$totalHistoryPlays reproduções • Ver as tuas estatísticas",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }

        // ---- Secção Recentes -----------------------------------------------
        if (uiState.recentSongs.isNotEmpty()) {
            item {
                SectionHeader(
                    title    = "Tocadas recentemente",
                    subtitle = "${uiState.recentSongs.size} música${if (uiState.recentSongs.size != 1) "s" else ""}"
                )
            }
            item {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding        = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.recentSongs.take(10), key = { "recent_${it.id}" }) { song ->
                        SongCard(
                            song          = song,
                            isCurrentSong = song.id == uiState.currentSong?.id,
                            onClick       = { viewModel.playSong(song, uiState.recentSongs) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ---- Secção Sugestões ----------------------------------------------
        if (suggestions.isNotEmpty()) {
            item {
                SectionHeader(
                    title    = if (uiState.artistPlayCount.isNotEmpty()) "Podes gostar" else "Descobre músicas",
                    subtitle = if (uiState.artistPlayCount.isNotEmpty())
                        "Baseado no teu gosto" else "Explora a tua biblioteca"
                )
            }
            items(suggestions, key = { "sug_${it.id}" }) { song ->
                SongItem(
                    song          = song,
                    isCurrentSong = song.id == uiState.currentSong?.id,
                    onClick       = { viewModel.playSong(song, suggestions) }
                )
            }
        }

        // ---- Fallback: todas as músicas se não houver histórico -------------
        if (uiState.recentSongs.isEmpty() && suggestions.isEmpty()) {
            item { SectionHeader(title = "Todas as músicas", subtitle = "${uiState.songs.size} faixas") }
            items(uiState.songs.take(20), key = { "all_${it.id}" }) { song ->
                SongItem(
                    song          = song,
                    isCurrentSong = song.id == uiState.currentSong?.id,
                    onClick       = { viewModel.playSong(song, uiState.songs) }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SongCard(song: Song, isCurrentSong: Boolean, onClick: () -> Unit) {
    val border = if (isCurrentSong)
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
    else Modifier.clip(RoundedCornerShape(12.dp))

    Column(
        modifier            = border.clickable(onClick = onClick).width(120.dp).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val art = artworkModel(song.albumArtUri, song.artistOrUnknown, song.albumOrUnknown)
        Card(shape = RoundedCornerShape(10.dp), elevation = CardDefaults.cardElevation(4.dp)) {
            AsyncImage(
                model              = art,
                contentDescription = null,
                modifier           = Modifier.size(104.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale       = ContentScale.Crop
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(song.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (isCurrentSong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(song.artistOrUnknown, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------
// Diálogo de atualização disponível
// ---------------------------------------------------------------------------
@Composable
fun UpdateDialog(info: UpdateChecker.UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = {
            Icon(Icons.Default.SystemUpdate, null,
                modifier = Modifier.size(36.dp),
                tint     = MaterialTheme.colorScheme.primary)
        },
        title = { Text("Nova versão disponível!", fontWeight = FontWeight.Bold) },
        text  = {
            Column {
                Text(
                    text  = "Versão ${info.latestVersion} já está disponível para descarregar.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (info.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text  = info.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                UpdateChecker.openDownloadPage(context, info.downloadUrl)
                onDismiss()
            }) { Text("Descarregar agora") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Mais tarde") }
        }
    )
}

// ---------------------------------------------------------------------------
// Modal de Partilha — WhatsApp
// ---------------------------------------------------------------------------
@Composable
fun ShareModal(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apkLink = "https://www.mediafire.com/file/5nnbdcx86ryempt/Smart_Player.apk/file"
    val shareText = "\uD83C\uDFB5 Estou a usar o *Smart Player* e adorei!\n\n" +
            "É um player de música grátis, bonito e rápido \uD83D\uDE80\n\n" +
            "Descarrega aqui \uD83D\uDC47\n$apkLink"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text  = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Ícone de música grande
                Box(
                    modifier        = Modifier.size(72.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, null,
                        modifier = Modifier.size(40.dp),
                        tint     = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text      = "Gostaste do Smart Player?",
                    style     = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text      = "Ajuda a partilhar com os teus amigos!\nÉ grátis e faz toda a diferença \uD83D\uDE4F",
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign  = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                // Botão WhatsApp
                Button(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type    = "text/plain"
                                setPackage("com.whatsapp")
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            // WhatsApp não instalado — abre partilha genérica
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Partilhar via"))
                        }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                    shape    = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Partilhar no WhatsApp", fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Agora não", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {}
    )
}

// ---------------------------------------------------------------------------
// Biblioteca com sub-abas: Músicas / Artistas / Álbuns
// ---------------------------------------------------------------------------
@Composable
fun LibraryContent(uiState: MusicUiState, viewModel: MusicViewModel) {
    var songToAdd by remember { mutableStateOf<Song?>(null) }
    val tabs = LibraryTab.entries

    Column(modifier = Modifier.fillMaxSize()) {
        // Barra de pesquisa — só na aba de músicas
        if (uiState.activeLibraryTab == LibraryTab.SONGS) {
            SearchBar(
                query         = uiState.searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }

        // TabRow: Músicas / Artistas / Álbuns
        ScrollableTabRow(
            selectedTabIndex = uiState.activeLibraryTab.ordinal,
            edgePadding      = 16.dp,
            containerColor   = MaterialTheme.colorScheme.surface
        ) {
            tabs.forEach { tab ->
                Tab(
                    selected = uiState.activeLibraryTab == tab,
                    onClick  = { viewModel.setLibraryTab(tab) },
                    text     = { Text(tab.label) }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                uiState.isLoading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                uiState.errorMessage != null ->
                    ErrorMessage(uiState.errorMessage, { viewModel.loadSongs() }, Modifier.align(Alignment.Center))
                else -> when (uiState.activeLibraryTab) {
                    LibraryTab.SONGS -> {
                        if (uiState.filteredSongs.isEmpty())
                            EmptyState(uiState.searchQuery.isNotBlank(), Modifier.align(Alignment.Center))
                        else
                            SongList(
                                songs           = uiState.filteredSongs,
                                currentSong     = uiState.currentSong,
                                onSongClick     = { viewModel.playSong(it) },
                                onSongLongClick = { songToAdd = it }
                            )
                    }
                    LibraryTab.ARTISTS -> ArtistsContent(
                        songs         = uiState.songs,
                        onArtistClick = { viewModel.openArtistDetail(it) },
                        modifier      = Modifier.fillMaxSize()
                    )
                    LibraryTab.ALBUMS -> AlbumsContent(
                        songs        = uiState.songs,
                        onAlbumClick = { viewModel.openAlbumDetail(it) },
                        modifier     = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    songToAdd?.let { song ->
        AddToPlaylistDialog(
            song           = song,
            playlists      = uiState.playlists,
            onDismiss      = { songToAdd = null },
            onSelect       = { pl -> viewModel.addSongToPlaylist(pl.id, song.id); songToAdd = null },
            onCreateAndAdd = { name -> viewModel.createPlaylist(name); songToAdd = null }
        )
    }
}

// ---------------------------------------------------------------------------
// Aba de Artistas
// ---------------------------------------------------------------------------
@Composable
fun ArtistsContent(songs: List<Song>, onArtistClick: (String) -> Unit, modifier: Modifier = Modifier) {
    val artists = remember(songs) {
        songs.groupBy { it.artistOrUnknown }
            .entries.sortedBy { it.key }
            .map { (artist, list) -> Triple(artist, list.size, list.firstOrNull()?.albumArtUri) }
    }
    if (artists.isEmpty()) { EmptyState(hasQuery = false, modifier = modifier); return }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(artists, key = { it.first }) { (artist, count, art) ->
            Row(
                modifier          = Modifier.fillMaxWidth().clickable { onArtistClick(artist) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = art, contentDescription = null,
                    modifier = Modifier.size(54.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(artist, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$count música${if (count != 1) "s" else ""}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(modifier = Modifier.padding(start = 84.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
    }
}

// ---------------------------------------------------------------------------
// Aba de Álbuns (grelha 2 colunas)
// ---------------------------------------------------------------------------
@Composable
fun AlbumsContent(songs: List<Song>, onAlbumClick: (String) -> Unit, modifier: Modifier = Modifier) {
    val albums = remember(songs) {
        songs.groupBy { it.albumOrUnknown }
            .entries.sortedBy { it.key }
            .map { (album, list) -> Triple(album, list.first().artistOrUnknown, list.first().albumArtUri) }
    }
    if (albums.isEmpty()) { EmptyState(hasQuery = false, modifier = modifier); return }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement   = Arrangement.spacedBy(12.dp)
    ) {
        items(albums, key = { it.first }) { (album, artist, art) ->
            Card(onClick = { onAlbumClick(album) }, shape = RoundedCornerShape(12.dp)) {
                Column {
                    AsyncImage(
                        model = art, contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(album, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(artist, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Detalhe de artista
// ---------------------------------------------------------------------------
@Composable
fun ArtistDetailScreen(
    artistName: String,
    songs: List<Song>,
    currentSong: Song?,
    onSongClick: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val artistSongs = remember(artistName, songs) {
        songs.filter { it.artistOrUnknown == artistName }
            .sortedWith(compareBy({ it.albumOrUnknown }, { it.track }, { it.title }))
    }

    // MusicBrainz: carrega info do artista assíncrono
    var artistInfo   by remember(artistName) { mutableStateOf<MusicBrainzFetcher.ArtistInfo?>(null) }
    var mbLoading    by remember(artistName) { mutableStateOf(true) }
    LaunchedEffect(artistName) {
        artistInfo = MusicBrainzFetcher.fetchArtistInfo(artistName)
        mbLoading  = false
    }

    // iTunes: artwork de fallback para o artista
    val artworkUri   = artistSongs.firstOrNull()?.albumArtUri
    val artworkModel = artworkModel(
        albumArtUri = artworkUri,
        artist      = artistName,
        album       = artistSongs.firstOrNull()?.albumOrUnknown ?: ""
    )

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = artworkModel, contentDescription = null,
                modifier = Modifier.size(80.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(artistName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${artistSongs.size} música${if (artistSongs.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // MusicBrainz: descrição + géneros
                artistInfo?.let { info ->
                    if (info.disambiguation != null) {
                        Text(info.disambiguation, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    if (info.genres.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            info.genres.take(3).forEach { genre ->
                                SuggestionChip(
                                    onClick = {},
                                    label   = { Text(genre, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        SongList(songs = artistSongs, currentSong = currentSong, onSongClick = onSongClick)
    }
}

// ---------------------------------------------------------------------------
// Detalhe de álbum
// ---------------------------------------------------------------------------
@Composable
fun AlbumDetailScreen(
    albumName: String,
    songs: List<Song>,
    currentSong: Song?,
    onSongClick: (Song, List<Song>) -> Unit,
    modifier: Modifier = Modifier
) {
    val albumSongs = remember(albumName, songs) {
        songs.filter { it.albumOrUnknown == albumName }
            .sortedWith(compareBy({ it.track }, { it.title }))
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = albumSongs.firstOrNull()?.albumArtUri, contentDescription = null,
                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(albumName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(albumSongs.firstOrNull()?.artistOrUnknown ?: "", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${albumSongs.size} faixa${if (albumSongs.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        SongList(songs = albumSongs, currentSong = currentSong,
            onSongClick = { song -> onSongClick(song, albumSongs) })
    }
}

// ---------------------------------------------------------------------------
// Playlists — lista
// ---------------------------------------------------------------------------
@Composable
fun PlaylistsContent(uiState: MusicUiState, viewModel: MusicViewModel) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.playlists.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.QueueMusic, null, modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text("Nenhuma playlist criada",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { showCreateDialog = true }) { Text("Criar playlist") }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(uiState.playlists, key = { it.id }) { pl ->
                    PlaylistItem(
                        playlist  = pl,
                        songCount = uiState.playlistSongIds[pl.id]?.size ?: 0,
                        onClick   = { viewModel.openPlaylist(pl.id) },
                        onPlay    = { viewModel.playPlaylist(pl) },
                        onDelete  = { viewModel.deletePlaylist(pl) },
                        onRename  = { viewModel.renamePlaylist(pl.id, it) }
                    )
                }
            }
        }
        FloatingActionButton(
            onClick  = { showCreateDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, "Nova playlist") }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate  = { name -> viewModel.createPlaylist(name); showCreateDialog = false }
        )
    }
}

// ---------------------------------------------------------------------------
// Playlists — detalhe
// ---------------------------------------------------------------------------
@Composable
fun PlaylistDetailScreen(
    playlist: PlaylistEntity,
    songs: List<Song>,
    currentSong: Song?,
    onSongClick: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onRemoveSong: (Song) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.QueueMusic, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${songs.size} música(s)", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (songs.isNotEmpty()) {
                FilledTonalButton(onClick = onPlayAll) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Tocar tudo")
                }
            }
        }
        HorizontalDivider()
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Adiciona músicas com um toque longo na biblioteca.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(songs, key = { it.id }) { song ->
                    SongItem(
                        song          = song,
                        isCurrentSong = song.id == currentSong?.id,
                        onClick       = { onSongClick(song) },
                        trailingContent = {
                            IconButton(onClick = { onRemoveSong(song) }) {
                                Icon(Icons.Default.RemoveCircleOutline, "Remover",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Playlist Item
// ---------------------------------------------------------------------------
@Composable
fun PlaylistItem(
    playlist: PlaylistEntity,
    songCount: Int,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit
) {
    var showMenu   by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.QueueMusic, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("$songCount música(s)", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Default.PlayCircleOutline, "Tocar", tint = MaterialTheme.colorScheme.primary)
        }
        Box {
            IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Renomear") },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = { showMenu = false; showRename = true }
                )
                DropdownMenuItem(
                    text = { Text("Eliminar", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { showMenu = false; onDelete() }
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 80.dp))
    if (showRename) {
        var text by remember { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title    = { Text("Renomear playlist") },
            text     = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Nome") }) },
            confirmButton = { TextButton(onClick = { onRename(text); showRename = false }, enabled = text.isNotBlank()) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancelar") } }
        )
    }
}

// ---------------------------------------------------------------------------
// Diálogos
// ---------------------------------------------------------------------------
@Composable
fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova playlist") },
        text  = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Nome") }, placeholder = { Text("ex: As minhas favoritas") })
        },
        confirmButton  = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Criar") } },
        dismissButton  = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun AddToPlaylistDialog(
    song: Song,
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onSelect: (PlaylistEntity) -> Unit,
    onCreateAndAdd: (String) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var newName    by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar a playlist") },
        text  = {
            Column {
                Text("\"${song.title}\"", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                if (playlists.isEmpty()) {
                    Text("Nenhuma playlist disponível.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(playlists, key = { it.id }) { pl ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onSelect(pl) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.QueueMusic, null, modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text(pl.name, style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (showCreate) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true,
                        label = { Text("Nome da nova playlist") }, modifier = Modifier.fillMaxWidth())
                } else {
                    TextButton(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Nova playlist")
                    }
                }
            }
        },
        confirmButton = {
            if (showCreate)
                TextButton(onClick = { onCreateAndAdd(newName) }, enabled = newName.isNotBlank()) { Text("Criar e adicionar") }
            else
                TextButton(onClick = onDismiss) { Text("Fechar") }
        },
        dismissButton = if (showCreate) ({ TextButton(onClick = { showCreate = false; newName = "" }) { Text("Cancelar") } }) else null
    )
}

// ---------------------------------------------------------------------------
// Campo de busca
// ---------------------------------------------------------------------------
@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query, onValueChange = onQueryChange, modifier = modifier,
        placeholder = { Text("Buscar por título, artista ou álbum...") },
        leadingIcon  = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty())
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Clear, "Limpar") }
        },
        singleLine = true, shape = RoundedCornerShape(50)
    )
}

// ---------------------------------------------------------------------------
// Lista de músicas
// ---------------------------------------------------------------------------
@Composable
fun SongList(
    songs: List<Song>,
    currentSong: Song?,
    onSongClick: (Song) -> Unit,
    onSongLongClick: (Song) -> Unit = {}
) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(songs, key = { it.id }) { song ->
            SongItem(
                song          = song,
                isCurrentSong = song.id == currentSong?.id,
                onClick       = { onSongClick(song) },
                onLongClick   = { onSongLongClick(song) }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Item de música — suporta long press + trailing content opcional
// ---------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItem(
    song: Song,
    isCurrentSong: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    trailingContent: (@Composable () -> Unit)? = null
) {
    val bg = if (isCurrentSong) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
             else Color.Transparent
    Row(
        modifier = Modifier.fillMaxWidth().background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = song.albumArtUri, contentDescription = null,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrentSong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artistOrUnknown, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailingContent != null) trailingContent()
        else Text(song.durationFormatted, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 80.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}

// ---------------------------------------------------------------------------
// Mini Player — toque na capa/título para abrir o player completo
// ---------------------------------------------------------------------------
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    currentPositionMs: Long,
    totalDurationMs: Long,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit
) {
    var isDragging   by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    val progress = if (totalDurationMs > 0)
        (if (isDragging) dragPosition else currentPositionMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
    else 0f

    Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Barra de progresso
            Slider(
                value = progress,
                onValueChange         = { dragPosition = it; isDragging = true },
                onValueChangeFinished = {
                    isDragging = false
                    if (totalDurationMs > 0) onSeek((dragPosition * totalDurationMs).toLong())
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(24.dp),
                colors = SliderDefaults.colors(
                    thumbColor         = MaterialTheme.colorScheme.primary,
                    activeTrackColor   = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                )
            )
            // Tempos
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    text  = formatTime(if (isDragging) (dragPosition * totalDurationMs).toLong() else currentPositionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(formatTime(totalDurationMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Controles
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // Toque na capa / título abre o player completo
                Row(
                    modifier          = Modifier.weight(1f).clickable(onClick = onExpand).padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = song.albumArtUri, contentDescription = null,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                    Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                        Text(song.title, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artistOrUnknown, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onToggleShuffle, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Shuffle, "Aleatório",
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onSkipPrev, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Anterior")
                }
                FilledIconButton(onClick = onPlayPause, shape = CircleShape, modifier = Modifier.size(42.dp)) {
                    Icon(
                        imageVector        = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pausar" else "Tocar"
                    )
                }
                IconButton(onClick = onSkipNext, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.SkipNext, "Próxima")
                }
                IconButton(onClick = onCycleRepeat, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector        = if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repetir",
                        tint = if (repeatMode == RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant
                               else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helper: modelo de artwork com fallback iTunes
// ---------------------------------------------------------------------------
@Composable
private fun artworkModel(albumArtUri: android.net.Uri?, artist: String, album: String): Any? {
    if (albumArtUri != null) return albumArtUri
    var url by remember(artist, album) { mutableStateOf<String?>(null) }
    LaunchedEffect(artist, album) {
        if (url == null) url = ArtworkFetcher.fetchArtworkUrl(artist, album)
    }
    return url
}

// ---------------------------------------------------------------------------
// Now Playing — ecrã completo com gradiente extraído da capa (Palette)
// ---------------------------------------------------------------------------
@Composable
fun NowPlayingScreen(
    song: Song,
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    currentPositionMs: Long,
    totalDurationMs: Long,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit
) {
    val context = LocalContext.current

    // iTunes fallback artwork
    val artModel = artworkModel(song.albumArtUri, song.artistOrUnknown, song.albumOrUnknown)

    // Extrai a cor dominante da capa do álbum via biblioteca Palette
    var dominantColor by remember(song.id) { mutableStateOf(Color(0xFF1A1A2E)) }
    LaunchedEffect(song.id) {
        val rgb = withContext(Dispatchers.IO) {
            try {
                val bmp = song.albumArtUri?.let { uri ->
                    context.contentResolver.openInputStream(uri)?.use { s ->
                        BitmapFactory.decodeStream(s)
                    }
                }
                bmp?.let { bitmap ->
                    val palette = Palette.from(bitmap).maximumColorCount(8).generate()
                    palette.darkVibrantSwatch?.rgb
                        ?: palette.vibrantSwatch?.rgb
                        ?: palette.dominantSwatch?.rgb
                }
            } catch (_: Exception) { null }
        }
        rgb?.let { dominantColor = Color(it) }
    }

    val animatedColor by animateColorAsState(
        targetValue   = dominantColor,
        animationSpec = tween(800),
        label         = "nowPlayingBg"
    )

    var isDragging   by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    val progress = if (totalDurationMs > 0)
        (if (isDragging) dragPosition else currentPositionMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
    else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f  to animatedColor,
                        0.55f to animatedColor.copy(alpha = 0.75f),
                        1.0f  to Color.Black
                    )
                )
            )
    ) {
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- Barra superior --------------------------------------------
            Row(
                modifier          = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.KeyboardArrowDown, "Fechar",
                        tint     = Color.White,
                        modifier = Modifier.size(32.dp))
                }
                Text(
                    text      = "A tocar agora",
                    color     = Color.White.copy(alpha = 0.85f),
                    style     = MaterialTheme.typography.labelLarge,
                    modifier  = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(48.dp))
            }

            Spacer(Modifier.height(20.dp))

            // ---- Capa do álbum ---------------------------------------------
            Card(
                shape     = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 24.dp),
                modifier  = Modifier.size(280.dp)
            ) {
                AsyncImage(
                    model              = artModel,
                    contentDescription = "Capa do álbum",
                    modifier           = Modifier.fillMaxSize().background(Color.DarkGray),
                    contentScale       = ContentScale.Crop
                )
            }

            Spacer(Modifier.height(36.dp))

            // ---- Info da música --------------------------------------------
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(song.title, color = Color.White,
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(song.artistOrUnknown, color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.albumOrUnknown, color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(24.dp))

            // ---- Progresso -------------------------------------------------
            Slider(
                value                 = progress,
                onValueChange         = { dragPosition = it; isDragging = true },
                onValueChangeFinished = {
                    isDragging = false
                    if (totalDurationMs > 0) onSeek((dragPosition * totalDurationMs).toLong())
                },
                modifier = Modifier.fillMaxWidth(),
                colors   = SliderDefaults.colors(
                    thumbColor         = Color.White,
                    activeTrackColor   = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    formatTime(if (isDragging) (dragPosition * totalDurationMs).toLong() else currentPositionMs),
                    color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.weight(1f))
                Text(formatTime(totalDurationMs), color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(20.dp))

            // ---- Controles -------------------------------------------------
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(Icons.Default.Shuffle, "Aleatório",
                        tint     = if (shuffleEnabled) Color.White else Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = onSkipPrev, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Anterior",
                        tint = Color.White, modifier = Modifier.size(42.dp))
                }
                FilledIconButton(
                    onClick  = onPlayPause,
                    modifier = Modifier.size(68.dp),
                    shape    = CircleShape,
                    colors   = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White,
                        contentColor   = Color.Black
                    )
                ) {
                    Icon(
                        imageVector        = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pausar" else "Tocar",
                        modifier           = Modifier.size(38.dp)
                    )
                }
                IconButton(onClick = onSkipNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, "Próxima",
                        tint = Color.White, modifier = Modifier.size(42.dp))
                }
                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        imageVector        = if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repetir",
                        tint               = if (repeatMode == RepeatMode.OFF) Color.White.copy(alpha = 0.45f) else Color.White,
                        modifier           = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Utilitário de formatação de tempo
// ---------------------------------------------------------------------------
private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

// ---------------------------------------------------------------------------
// Pesquisa Global — ecrã completo com resultados agrupados
// ---------------------------------------------------------------------------
@Composable
fun GlobalSearchScreen(uiState: MusicUiState, viewModel: MusicViewModel) {
    val query = uiState.globalSearchQuery

    // Resultados agrupados
    val filteredSongs = remember(query, uiState.songs) {
        if (query.isBlank()) emptyList()
        else uiState.songs.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.artistOrUnknown.contains(query, ignoreCase = true) ||
            it.albumOrUnknown.contains(query, ignoreCase = true)
        }.take(20)
    }
    val filteredArtists = remember(query, uiState.songs) {
        if (query.isBlank()) emptyList()
        else uiState.songs.map { it.artistOrUnknown }.distinct()
            .filter { it.contains(query, ignoreCase = true) }.take(8)
    }
    val filteredAlbums = remember(query, uiState.songs) {
        if (query.isBlank()) emptyList()
        else uiState.songs.map { it.albumOrUnknown }.distinct()
            .filter { it.contains(query, ignoreCase = true) }.take(8)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            // ---- TopBar com botão voltar + campo de busca -------------------
            Row(
                modifier          = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.setShowSearch(false) }) {
                    Icon(Icons.Default.ArrowBack, "Fechar pesquisa")
                }
                OutlinedTextField(
                    value          = query,
                    onValueChange  = viewModel::onGlobalSearchQuery,
                    modifier       = Modifier.weight(1f),
                    placeholder    = { Text("Músicas, artistas, álbuns...") },
                    singleLine     = true,
                    shape          = RoundedCornerShape(50),
                    trailingIcon   = {
                        if (query.isNotEmpty())
                            IconButton(onClick = { viewModel.onGlobalSearchQuery("") }) {
                                Icon(Icons.Default.Clear, "Limpar")
                            }
                    }
                )
                Spacer(Modifier.width(4.dp))
            }
            HorizontalDivider()

            if (query.isBlank()) {
                // ---- Estado inicial: histórico recente ----------------------
                if (uiState.playHistory.isNotEmpty()) {
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        item {
                            Text(
                                "Tocadas recentemente",
                                style     = MaterialTheme.typography.labelMedium,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier  = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        items(uiState.playHistory.take(10), key = { it.id }) { entry ->
                            val song = uiState.songs.firstOrNull { it.id == entry.songId }
                            Row(
                                modifier          = Modifier.fillMaxWidth()
                                    .clickable { song?.let { viewModel.playSong(it); viewModel.setShowSearch(false) } }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.History, null, modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.title, style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(entry.artist, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 48.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Search, null, modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("Pesquisa músicas, artistas ou álbuns",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                // ---- Resultados agrupados ----------------------------------
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    // Músicas
                    if (filteredSongs.isNotEmpty()) {
                        item {
                            Text("Músicas", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        items(filteredSongs, key = { "gs_song_${it.id}" }) { song ->
                            SongItem(
                                song          = song,
                                isCurrentSong = song.id == uiState.currentSong?.id,
                                onClick       = { viewModel.playSong(song, filteredSongs); viewModel.setShowSearch(false) }
                            )
                        }
                    }
                    // Artistas
                    if (filteredArtists.isNotEmpty()) {
                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text("Artistas", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        items(filteredArtists, key = { "gs_art_$it" }) { artist ->
                            val count = uiState.songs.count { it.artistOrUnknown == artist }
                            Row(
                                modifier          = Modifier.fillMaxWidth()
                                    .clickable { viewModel.setShowSearch(false); viewModel.openArtistDetail(artist) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val art = uiState.songs.firstOrNull { it.artistOrUnknown == artist }?.albumArtUri
                                AsyncImage(model = art, contentDescription = null,
                                    modifier = Modifier.size(44.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentScale = ContentScale.Crop)
                                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(artist, style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("$count músicas", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.ChevronRight, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 72.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                    // Álbuns
                    if (filteredAlbums.isNotEmpty()) {
                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text("Álbuns", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        items(filteredAlbums, key = { "gs_alb_$it" }) { album ->
                            val albumSong = uiState.songs.firstOrNull { it.albumOrUnknown == album }
                            Row(
                                modifier          = Modifier.fillMaxWidth()
                                    .clickable { viewModel.setShowSearch(false); viewModel.openAlbumDetail(album) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(model = albumSong?.albumArtUri, contentDescription = null,
                                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentScale = ContentScale.Crop)
                                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(album, style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(albumSong?.artistOrUnknown ?: "", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Default.ChevronRight, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 72.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                    // Sem resultados
                    if (filteredSongs.isEmpty() && filteredArtists.isEmpty() && filteredAlbums.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.SearchOff, null, modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Nenhum resultado para \"$query\"",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Wrapped Anual — resumo das estatísticas de audição
// ---------------------------------------------------------------------------
@Composable
fun WrappedScreen(data: WrappedData, onClose: () -> Unit) {
    val gradStart = Color(0xFF6A0DAD)  // roxo
    val gradEnd   = Color(0xFF1A0033)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(gradStart, gradEnd)))
    ) {
        LazyColumn(
            modifier       = Modifier.fillMaxSize().systemBarsPadding(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // Botão fechar
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Spacer(Modifier.size(48.dp))
                    Text("Wrapped", color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, "Fechar", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Ano + total
            item {
                Text(
                    text      = data.year.toString(),
                    color     = Color.White,
                    style     = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text  = "o teu ano em músicas 🎵",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(24.dp))
            }

            // Cards de stats
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Total reproduções
                    Card(
                        modifier = Modifier.weight(1f),
                        colors   = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.15f)),
                        shape    = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(data.totalPlays.toString(), color = Color.White,
                                style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                            Text("reproduções", color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                        }
                    }
                    // Horas estimadas
                    Card(
                        modifier = Modifier.weight(1f),
                        colors   = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.15f)),
                        shape    = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("%.1f".format(data.estimatedHours), color = Color.White,
                                style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                            Text("horas ouvidas", color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }

            // Top Músicas
            if (data.topSongs.isNotEmpty()) {
                item {
                    Text("🎵 As tuas 5 músicas favoritas",
                        color = Color.White, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                }
                itemsIndexed(data.topSongs) { idx, row ->
                    Row(
                        modifier          = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = if (idx == 0) 0.2f else 0.1f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text      = "#${idx + 1}",
                            color     = if (idx == 0) Color(0xFFFFD700) else Color.White.copy(alpha = 0.7f),
                            style     = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            modifier  = Modifier.width(36.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.title, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(row.artist, color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            text  = "${row.playCount}x",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            // Top Artistas
            if (data.topArtists.isNotEmpty()) {
                item {
                    Text("🎤 Os teus 5 artistas favoritos",
                        color = Color.White, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                }
                itemsIndexed(data.topArtists) { idx, row ->
                    Row(
                        modifier          = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = if (idx == 0) 0.2f else 0.1f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text      = "#${idx + 1}",
                            color     = if (idx == 0) Color(0xFFFFD700) else Color.White.copy(alpha = 0.7f),
                            style     = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            modifier  = Modifier.width(36.dp)
                        )
                        Text(row.artist, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text  = "${row.playCount}x",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }

            // Footer
            item {
                Text(
                    text      = "Smart Player • ${data.year}",
                    color     = Color.White.copy(alpha = 0.5f),
                    style     = MaterialTheme.typography.labelSmall,
                    modifier  = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Ecrãs auxiliares
// ---------------------------------------------------------------------------
@Composable
fun EmptyState(hasQuery: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(
            text  = if (hasQuery) "Nenhuma música encontrada para essa busca."
                    else "Nenhuma música encontrada no dispositivo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ErrorMessage(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Tentar novamente") }
    }
}

@Composable
fun PermissionDeniedScreen(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Lock, null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text("Permissão necessária", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Este app precisa aceder aos ficheiros de áudio do dispositivo para exibir a biblioteca de músicas.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Conceder permissão") }
    }
}






