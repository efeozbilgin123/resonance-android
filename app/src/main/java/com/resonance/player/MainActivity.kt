package com.resonance.player

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.DisposableEffect
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val lime = Color(0xFFC6F17B)
private val bg = Color(0xFF080B0A)
private val panel = Color(0xFF171D19)
private val muted = Color(0xFF9BA39B)
private data class Song(val uri: Uri, val title: String, val artist: String)

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private var incomingYouTubeUrl by mutableStateOf<String?>(null)

    private fun acceptYouTubeShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val raw = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = Regex("https://[^\\s]+").findAll(raw)
            .map { it.value.trimEnd(')', '.', ',') }
            .firstOrNull { YouTubeLinks.parse(it) != null }
        if (url != null) incomingYouTubeUrl = url
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptYouTubeShare(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptYouTubeShare(intent)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ runCatching { controller = future.get() } }, ContextCompat.getMainExecutor(this))
        val storage = getSharedPreferences("tracks", Context.MODE_PRIVATE)
        setContent {
            var songs by remember {
                mutableStateOf(storage.getStringSet("uris", emptySet()).orEmpty()
                    .mapNotNull { runCatching { song(Uri.parse(it)) }.getOrNull() })
            }
            var selected by remember { mutableStateOf<Song?>(null) }
            var playing by remember { mutableStateOf(false) }
            var page by remember { mutableStateOf(1) }
            LaunchedEffect(incomingYouTubeUrl) {
                if (incomingYouTubeUrl != null) page = 1
            }
            var controllerReady by remember { mutableStateOf(false) }
            var repeatMode by remember { mutableIntStateOf(0) }
            var shuffle by remember { mutableStateOf(false) }
            var queueIndex by remember { mutableIntStateOf(0) }
            var search by remember { mutableStateOf("") }
            var streamUrl by remember { mutableStateOf("") }
            var favorites by remember { mutableStateOf(storage.getStringSet("favorites", emptySet()).orEmpty()) }
            var error by remember { mutableStateOf<String?>(null) }
            var pendingExternal by remember { mutableStateOf<ExternalPlaylistItem?>(null) }
            var pendingSpotifyPremium by remember { mutableStateOf<SpotifyTrack?>(null) }
            var pendingYouTube by remember { mutableStateOf<ExternalPlaylistItem?>(null) }
            var pendingAddSpotify by remember { mutableStateOf<SpotifyTrack?>(null) }
            val queueStorage = remember { PlaybackQueuePersistence(this@MainActivity) }
            var playbackQueue by remember { mutableStateOf(queueStorage.restore()) }
            LaunchedEffect(playbackQueue) {
                withContext(Dispatchers.IO) { queueStorage.save(playbackQueue) }
            }
            var playbackPosition by remember { mutableLongStateOf(0L) }
            var playbackDuration by remember { mutableLongStateOf(0L) }
            val latestAutoAdvance = remember { mutableStateOf<() -> Unit>({}) }
            val spotifyEngine = SpotifyAudioEngine
            var streamRequest by remember { mutableIntStateOf(0) }
            var activeUnified by remember {
                mutableStateOf<UnifiedSong?>(playbackQueue.current)
            }
            LaunchedEffect(Unit) {
                activeUnified?.let {
                    if (selected == null) selected = Song(
                        Uri.parse(it.id), it.title, it.artist)
                }
            }
            var audioRetryAttempts by remember { mutableIntStateOf(0) }
            var consecutiveFailures by remember { mutableIntStateOf(0) }
            val scope = rememberCoroutineScope()
            val unifiedCatalog = remember { UnifiedMusicCatalog(this@MainActivity,
                SpotifyClient(this@MainActivity)) }
            DisposableEffect(Unit) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playing = isPlaying
                        if (isPlaying) {
                            error = null
                            consecutiveFailures = 0
                        }
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED &&
                            activeUnified?.source != MusicOrigin.SPOTIFY) {
                            latestAutoAdvance.value()
                        }
                    }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                        item ?: return
                        val location = item.localConfiguration?.uri ?: return
                        selected = Song(location,
                            item.mediaMetadata.title?.toString() ?: "Ses yayını",
                            item.mediaMetadata.artist?.toString() ?: "Resonance")
                    }
                    override fun onPlayerError(playerError: PlaybackException) {
                        val track = activeUnified
                        if (track?.source == MusicOrigin.YOUTUBE_MUSIC &&
                            audioRetryAttempts < 3) {
                            audioRetryAttempts++
                            error = "YouTube ses kaynağı değiştiriliyor... (${audioRetryAttempts}/3)"
                            val seq = streamRequest
                            scope.launch {
                                try {
                                    delay(350L * audioRetryAttempts)
                                    val fresh = withContext(Dispatchers.IO) {
                                        unifiedCatalog.invalidateStream(track)
                                        unifiedCatalog.audioStream(track)
                                    }
                                    if (seq == streamRequest) {
                                        val active = controller ?: kotlin.error("Oynatıcı hazır değil")
                                        active.setMediaItem(MediaItem.Builder().setUri(fresh.url)
                                            .setMimeType(fresh.mimeType)
                                            .setMediaId(track.stableKey)
                                            .setMediaMetadata(MediaMetadata.Builder()
                                                .setTitle(track.title).setArtist(track.artist)
                                                .build()).build())
                                        active.prepare()
                                        active.play()
                                        error = null
                                    }
                                } catch (e: Exception) {
                                    if (seq == streamRequest) {
                                        error = "YouTube kaynakları kullanılamıyor: ${e.localizedMessage}"
                                        playing = false
                                        consecutiveFailures++
                                        if (consecutiveFailures <= 3)
                                            latestAutoAdvance.value()
                                    }
                                }
                            }
                        } else {
                            error = "Ses kaynağı açılamadı: ${playerError.localizedMessage}"
                            playing = false
                            consecutiveFailures++
                            if (activeUnified != null && consecutiveFailures <= 3)
                                latestAutoAdvance.value()
                        }
                    }
                }
                var registered = false
                val attach = {
                    val c = controller
                    if (c != null && !registered) {
                        c.addListener(listener)
                        registered = true
                        controllerReady = true
                        playing = c.isPlaying
                    }
                }
                attach()
                future.addListener({ attach() }, ContextCompat.getMainExecutor(this@MainActivity))
                onDispose { if (registered) controller?.removeListener(listener) }
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { chosen ->
                chosen.forEach { uri ->
                    try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                }
                songs = (songs + chosen.mapNotNull { runCatching { song(it) }.getOrNull() }).distinctBy { it.uri }
                storage.edit().putStringSet("uris", songs.map { it.uri.toString() }.toSet()).apply()
            }
            val startSong: (Song) -> Unit = { track ->
                spotifyEngine.stop()
                activeUnified = null
                playbackQueue = PlaybackQueue()
                runCatching {
                    val active = controller ?: error("Oynatıcı henüz hazır değil")
                    active.setMediaItem(MediaItem.Builder().setUri(track.uri).setMediaMetadata(
                        MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).build()
                    ).build())
                    active.prepare()
                    active.play()
                    selected = track
                    playing = true
                    error = null
                }.onFailure { error = it.localizedMessage }
            }
            val startRadio: (RadioStation) -> Unit = { station ->
                spotifyEngine.stop()
                activeUnified = null
                playbackQueue = PlaybackQueue()
                runCatching {
                    val active = controller ?: error("Oynatıcı henüz hazır değil")
                    val item = MediaItem.Builder().setUri(station.stream)
                        .setMediaMetadata(MediaMetadata.Builder()
                            .setTitle(station.name).setArtist("Canlı radyo · ${station.country}")
                            .build())
                        .build()
                    active.setMediaItem(item)
                    active.prepare()
                    active.play()
                    selected = Song(Uri.parse(station.stream), station.name, "Canlı radyo")
                    playing = true
                    error = null
                }.onFailure { error = it.localizedMessage }
            }
            val startAudius: (AudiusTrack, List<AudiusTrack>) -> Unit = { track, tracks ->
                spotifyEngine.stop()
                activeUnified = null
                playbackQueue = PlaybackQueue()
                runCatching {
                    val active = controller ?: error("Oynatıcı henüz hazır değil")
                    val playable = tracks.map { candidate ->
                        MediaItem.Builder()
                            .setUri(candidate.audioUrl)
                            .setMimeType(MimeTypes.AUDIO_MPEG)
                            .setMediaId(candidate.id)
                            .setMediaMetadata(MediaMetadata.Builder()
                                .setTitle(candidate.title).setArtist(candidate.artist).build())
                            .build()
                    }
                    val index = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                    active.setMediaItems(playable, index, 0L)
                    active.prepare()
                    active.play()
                    selected = Song(Uri.parse(track.audioUrl), track.title, track.artist)
                    playing = true
                    error = null
                }.onFailure { error = it.localizedMessage }
            }
            val launchUnifiedAudio: (UnifiedSong) -> Unit = { track ->
                activeUnified = track
                selected = Song(Uri.parse(track.id), track.title, track.artist)
                error = null
                if (track.source == MusicOrigin.SPOTIFY) {
                    ++streamRequest
                    controller?.pause()
                    pendingSpotifyPremium = track.spotifyTrack()
                    if (spotifyEngine.ready || spotifyEngine.connecting) {
                        spotifyEngine.load(track.spotifyTrack())
                    } else {
                        // First Premium selection authenticates inside the native page.
                        page = 6
                    }
                } else {
                    if (spotifyEngine.ready || spotifyEngine.connecting) spotifyEngine.stop()
                    audioRetryAttempts = 0
                    val seq = ++streamRequest
                    controller?.pause()
                    scope.launch {
                        try {
                            val stream = withContext(Dispatchers.IO) {
                                unifiedCatalog.audioStream(track)
                            }
                            if (seq == streamRequest) {
                                val active = controller ?: kotlin.error("Oynatıcı henüz hazır değil")
                                val builder = MediaItem.Builder().setUri(stream.url)
                                    .setMediaId(track.stableKey)
                                    .setMediaMetadata(MediaMetadata.Builder()
                                        .setTitle(track.title).setArtist(track.artist).build())
                                if (stream.mimeType.isNotBlank())
                                    builder.setMimeType(stream.mimeType)
                                active.setMediaItem(builder.build())
                                active.prepare()
                                active.play()
                                selected = Song(Uri.parse(stream.url), track.title, track.artist)
                                playing = true
                                error = null
                            }
                        } catch (e: Exception) {
                            if (seq == streamRequest) {
                                error = "Müzik kaynağı açılamadı: ${e.localizedMessage}"
                                playing = false
                            }
                        }
                    }
                }
            }
            val playUnified: (UnifiedSong, List<UnifiedSong>) -> Unit = { track, rows ->
                val list = rows.takeIf { data -> data.any { it.stableKey == track.stableKey } }
                    ?: listOf(track)
                playbackQueue = playbackQueue.start(list, track.stableKey)
                launchUnifiedAudio(track)
            }
            val nextUnified: (Boolean) -> Unit = { automatic ->
                val next = playbackQueue.forward(automatic)
                if (next != null) {
                    playbackQueue = next
                    next.current?.let { launchUnifiedAudio(it) }
                } else if (playbackQueue.entries.isEmpty() && !automatic) {
                    controller?.seekToNextMediaItem()
                } else if (automatic) {
                    playing = false
                }
            }
            val unifiedNext: () -> Unit = { nextUnified(false) }
            val unifiedPrevious: () -> Unit = {
                val previous = playbackQueue.backward()
                if (previous != null) {
                    playbackQueue = previous
                    previous.current?.let { launchUnifiedAudio(it) }
                } else controller?.seekToPreviousMediaItem()
            }
            SideEffect {
                latestAutoAdvance.value = { nextUnified(true) }
                spotifyEngine.onEndOfTrack = { nextUnified(true) }
                spotifyEngine.onNext = unifiedNext
                spotifyEngine.onPrevious = unifiedPrevious
            }
            DisposableEffect(Unit) {
                onDispose {
                    spotifyEngine.onEndOfTrack = null
                    spotifyEngine.onUnavailable = null
                    spotifyEngine.onNext = null
                    spotifyEngine.onPrevious = null
                }
            }
            val playExternal: (ExternalPlaylistItem) -> Unit = { track ->
                val source = when (track.source) {
                    "youtube" -> MusicOrigin.YOUTUBE_MUSIC
                    "audius" -> MusicOrigin.AUDIUS
                    "local" -> MusicOrigin.LOCAL
                    else -> MusicOrigin.STREAM
                }
                val id = if (source == MusicOrigin.LOCAL ||
                    source == MusicOrigin.STREAM) track.playbackUri else track.id
                val unified = UnifiedSong(id, source, track.title, track.artist,
                    cover = track.artwork)
                playUnified(unified, listOf(unified))
            }
            LaunchedEffect(page, playing) {
                while (page == 9) {
                    playbackPosition = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                    playbackDuration = controller?.duration?.takeIf { it > 0 } ?: 0L
                    delay(750)
                }
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = lime, background = bg, surface = panel)) {
                Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("RESONANCE", color = lime, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        Spacer(Modifier.weight(1f))
                        if (selected != null || activeUnified != null) {
                            IconButton(onClick = { page = 9 }) {
                                Icon(Icons.Rounded.QueueMusic, "Oynatıcı ve sıra", tint = lime)
                            }
                        }
                        TextButton(onClick = { page = if (page == 2 || page == 3) 1 else 2 }) { Text(if (page == 2 || page == 3) "Ara" else "Kitaplık", color = lime) }
                        IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                            Icon(Icons.Rounded.LibraryAdd, contentDescription = "Müzik içe aktar", tint = lime)
                        }
                    }
                    if (page == 9) {
                        val spotifyPlaying = activeUnified?.source == MusicOrigin.SPOTIFY
                        NowPlayingScreen(
                            title = activeUnified?.title ?: selected?.title.orEmpty(),
                            artist = activeUnified?.artist ?: selected?.artist.orEmpty(),
                            cover = activeUnified?.cover.orEmpty(),
                            source = activeUnified?.source?.name ?: "Resonance",
                            isPlaying = if (spotifyPlaying) spotifyEngine.playing else playing,
                            positionMs = if (spotifyPlaying) spotifyEngine.positionMs.toLong()
                                else playbackPosition,
                            durationMs = if (spotifyPlaying) spotifyEngine.durationMs.toLong()
                                else playbackDuration,
                            queue = playbackQueue,
                            onBack = { page = 1 },
                            onToggle = {
                                if (spotifyPlaying) spotifyEngine.toggle()
                                else controller?.let { if (it.isPlaying) it.pause() else it.play() }
                            },
                            onPrevious = unifiedPrevious,
                            onNext = unifiedNext,
                            onSeek = { milliseconds ->
                                if (spotifyPlaying) spotifyEngine.seek(milliseconds.toInt())
                                else controller?.seekTo(milliseconds)
                            },
                            onRepeat = { playbackQueue = playbackQueue.withRepeat(it) },
                            onShuffle = { playbackQueue = playbackQueue.withShuffle(it) },
                            onMove = { from, to ->
                                playbackQueue = playbackQueue.move(from, to)
                            },
                            onRemove = { at ->
                                val removedCurrent = at == playbackQueue.currentIndex
                                playbackQueue = playbackQueue.remove(at)
                                if (removedCurrent) playbackQueue.current?.let { launchUnifiedAudio(it) }
                            },
                            onSelect = { index ->
                                playbackQueue = playbackQueue.copy(currentIndex = index)
                                playbackQueue.current?.let { launchUnifiedAudio(it) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    } else if (page == 2 || page == 3) {
                        Box(Modifier.weight(1f)) {
                            SpotifyScreen(this@MainActivity, playExternal, onPremiumPlay = { track ->
                                val item = UnifiedSong(track.uri, MusicOrigin.SPOTIFY,
                                    track.title, track.artist, track.durationSeconds, track.cover)
                                playUnified(item, listOf(item))
                            }, onPlayQueue = playUnified)
                        }
                        NavigationBar(containerColor = panel) {
                            listOf(
                                Triple("Ara", Icons.Rounded.Search, 1),
                                Triple("Müzik", Icons.Rounded.Album, 5),
                                Triple("Radyo", Icons.Rounded.Radio, 4),
                                Triple("Yerel", Icons.Rounded.Home, 0),
                                Triple("Kitaplık", Icons.Rounded.LibraryMusic, 2)
                            ).forEach { (label, icon, n) ->
                                NavigationBarItem(selected = page == n,
                                    onClick = { page = n }, icon = { Icon(icon, null) },
                                    label = { Text(label) })
                            }
                        }
                    } else if (page == 6) {
                        SpotifyPremiumHostScreen(this@MainActivity, pendingSpotifyPremium,
                            onBack = { page = 1 }, modifier = Modifier.weight(1f))
                    } else if (page == 1) {
                        UnifiedSearchScreen(
                            activity = this@MainActivity,
                            onPlay = playUnified,
                            onAdd = { item ->
                                if (item.source == MusicOrigin.SPOTIFY) {
                                    pendingAddSpotify = item.spotifyTrack()
                                } else {
                                    pendingExternal = item.external()
                                }
                            },
                            playingTitle = activeUnified?.title ?: selected?.title,
                            isPlaying = if (activeUnified?.source == MusicOrigin.SPOTIFY)
                                spotifyEngine.playing else playing,
                            onToggle = {
                                if (activeUnified?.source == MusicOrigin.SPOTIFY)
                                    spotifyEngine.toggle()
                                else controller?.let { if (it.isPlaying) it.pause() else it.play() }
                            },
                            onPrevious = unifiedPrevious,
                            onNext = unifiedNext,
                            onOpenPlayer = { page = 9 },
                            initialLink = incomingYouTubeUrl,
                            modifier = Modifier.weight(1f)
                        )
                        NavigationBar(containerColor = panel) {
                            listOf(
                                Triple("Ara", Icons.Rounded.Search, 1),
                                Triple("Müzik", Icons.Rounded.Album, 5),
                                Triple("Radyo", Icons.Rounded.Radio, 4),
                                Triple("Yerel", Icons.Rounded.Home, 0),
                                Triple("Kitaplık", Icons.Rounded.LibraryMusic, 2)
                            ).forEach { (label, icon, n) ->
                                NavigationBarItem(selected = page == n, onClick = { page = n },
                                    icon = { Icon(icon, null) }, label = { Text(label) })
                            }
                        }
                    } else if (page == 4 || page == 5) {
                        if (page == 5) {
                            AudiusScreen(this@MainActivity, startAudius, Modifier.weight(1f),
                                onAddExternal = { pendingExternal = it })
                        } else {
                            RadioScreen(this@MainActivity, startRadio, Modifier.weight(1f))
                        }
                        selected?.let { track ->
                            Row(Modifier.fillMaxWidth().background(panel).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.MusicNote, null, tint = lime)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(track.title, color = Color.White, maxLines = 1)
                                    Text(track.artist, color = muted, fontSize = 12.sp)
                                }
                                IconButton(onClick = { controller?.seekToPreviousMediaItem() },
                                    enabled = controllerReady) {
                                    Icon(Icons.Rounded.SkipPrevious, "Önceki", tint = lime)
                                }
                                IconButton(onClick = {
                                    controller?.let { if (it.isPlaying) it.pause() else it.play() }
                                }, enabled = controllerReady) {
                                    Icon(if (playing) Icons.Rounded.PauseCircleFilled else Icons.Rounded.PlayCircleFilled,
                                        contentDescription = if (playing) "Duraklat" else "Çal", tint = lime)
                                }
                                IconButton(onClick = { controller?.seekToNextMediaItem() },
                                    enabled = controllerReady) {
                                    Icon(Icons.Rounded.SkipNext, "Sonraki", tint = lime)
                                }
                            }
                        }
                        NavigationBar(containerColor = panel) {
                            listOf(
                                Triple("Müzik", Icons.Rounded.Album, 5),
                                Triple("Radyo", Icons.Rounded.Radio, 4),
                                Triple("Yerel", Icons.Rounded.Home, 0),
                                Triple("Ara", Icons.Rounded.Search, 1),
                                Triple("Kitaplık", Icons.Rounded.LibraryMusic, 2)
                            ).forEach { (label, icon, n) ->
                                NavigationBarItem(selected = page == n, onClick = { page = n },
                                    icon = { Icon(icon, null) }, label = { Text(label) })
                            }
                        }
                    } else {
                    Text(
                        when(page) { 0 -> "Müzik senin dünyan."; 1 -> "Keşfet"; else -> "Kütüphanen" },
                        Modifier.padding(horizontal = 22.dp), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    if (page == 1) {
                        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                            singleLine = true, label = { Text("Yerel şarkılarında ara") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) })
                    }
                    if (page == 1) {
                        OutlinedTextField(streamUrl, { streamUrl = it }, Modifier.fillMaxWidth().padding(horizontal = 22.dp), singleLine = true, label = { Text("İzinli HTTPS radyo / ses akışı URL") })
                        Button(onClick = {
                            runCatching {
                                val uri = Uri.parse(streamUrl.trim())
                                require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "Geçerli HTTPS adresi gir" }
                                val active = controller ?: error("Oynatıcı henüz hazır değil")
                                active.setMediaItem(MediaItem.fromUri(uri))
                                active.prepare()
                                active.play()
                                selected = Song(uri, "Çevrimiçi yayın", "Bağımsız ses kaynağı")
                                playing = true
                                error = null
                            }.onFailure { error = it.localizedMessage }
                        }, modifier = Modifier.padding(horizontal = 22.dp)) { Text("Yayını çal") }
                    }
                    if (songs.isEmpty()) {
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Headphones, null, tint = lime, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("İlk parçanı ekle", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("Telefonundaki müzik dosyalarını seç.", color = muted)
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Müzik ekle") }
                        }
                    } else {
                        Text(
                            if (page == 2) "Beğendiklerin ve tüm parçalar" else "Yerel koleksiyon",
                            Modifier.padding(horizontal = 22.dp, vertical = 12.dp), color = muted, fontSize = 13.sp
                        )
                        val visible = songs.filter {
                            (page != 1 || it.title.contains(search, true) || it.artist.contains(search, true)) &&
                            (page != 2 || search != "favorites" || it.uri.toString() in favorites)
                        }
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                            items(visible) { track ->
                                Row(
                                    Modifier.fillMaxWidth().padding(bottom = 7.dp)
                                        .background(panel, RoundedCornerShape(14.dp))
                                        .clickable { startSong(track) }.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.GraphicEq, null, tint = lime, modifier = Modifier.size(38.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(track.artist, color = muted, fontSize = 12.sp, maxLines = 1)
                                    }
                                    IconButton(onClick = {
                                        pendingExternal = ExternalPlaylistItem.fromLocal(
                                            track.uri, track.title, track.artist)
                                    }) {
                                        Icon(Icons.Rounded.PlaylistAdd,
                                            contentDescription = "Resonance listesine ekle", tint = lime)
                                    }
                                    IconButton(onClick = {
                                        val key = track.uri.toString()
                                        favorites = if(key in favorites) favorites - key else favorites + key
                                        storage.edit().putStringSet("favorites", favorites).apply()
                                    }) {
                                        Icon(if(track.uri.toString() in favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                            null, tint = if(track.uri.toString() in favorites) lime else muted)
                                    }
                                }
                            }
                        }
                    }
                    if (error != null) Text("Oynatma hatası: $error", Modifier.padding(12.dp), color = Color.Red)
                    selected?.let { track ->
                        Column(Modifier.fillMaxWidth().background(panel).padding(horizontal = 16.dp, vertical = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.MusicNote, null, tint = lime)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(track.artist, color = muted, fontSize = 12.sp, maxLines = 1)
                                }
                                IconButton(onClick = {
                                    runCatching {
                                        controller?.let { if (it.isPlaying) it.pause() else it.play(); playing = it.isPlaying }
                                    }.onFailure { error = it.localizedMessage }
                                }) { Icon(if(playing) Icons.Rounded.PauseCircleFilled else Icons.Rounded.PlayCircleFilled, null, tint = lime) }
                            }
                        }
                    }
                    NavigationBar(containerColor = panel) {
                        listOf(Triple("Müzik", Icons.Rounded.Album, 5), Triple("Radyo", Icons.Rounded.Radio, 4), Triple("Yerel", Icons.Rounded.Home, 0),Triple("Ara", Icons.Rounded.Search, 1), Triple("Kütüphane", Icons.Rounded.LibraryMusic, 2)).forEach { (label, icon, n) ->
                            NavigationBarItem(selected = page == n, onClick = { page = n }, icon = { Icon(icon, null) }, label = { Text(label) })
                        }
                    }
                    }
                }
                pendingExternal?.let { track ->
                    SpotifyPlaylistPicker(this@MainActivity, external = track,
                        onDismiss = { pendingExternal = null })
                }
                pendingAddSpotify?.let { track ->
                    SpotifyPlaylistPicker(this@MainActivity, spotifyTrack = track,
                        onDismiss = { pendingAddSpotify = null })
                }
            }
        }
    }

    private fun song(uri: Uri): Song {
        var title = uri.lastPathSegment?.substringAfterLast('/') ?: "Bilinmeyen parça"
        var artist = "Bilinmeyen sanatçı"
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(this, uri)
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: title
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: artist
            }
        }
        return Song(uri, title, artist)
    }
    override fun onDestroy() { controller?.release(); super.onDestroy() }
}
