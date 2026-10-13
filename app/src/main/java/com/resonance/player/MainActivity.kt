package com.resonance.player

import android.content.Context
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

private val lime = Color(0xFFC6F17B)
private val bg = Color(0xFF080B0A)
private val panel = Color(0xFF171D19)
private val muted = Color(0xFF9BA39B)
private data class Song(val uri: Uri, val title: String, val artist: String)

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            var page by remember { mutableStateOf(5) }
            var controllerReady by remember { mutableStateOf(false) }
            var repeatMode by remember { mutableIntStateOf(0) }
            var shuffle by remember { mutableStateOf(false) }
            var queueIndex by remember { mutableIntStateOf(0) }
            var search by remember { mutableStateOf("") }
            var streamUrl by remember { mutableStateOf("") }
            var favorites by remember { mutableStateOf(storage.getStringSet("favorites", emptySet()).orEmpty()) }
            var error by remember { mutableStateOf<String?>(null) }
            var pendingExternal by remember { mutableStateOf<ExternalPlaylistItem?>(null) }
            DisposableEffect(Unit) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                        item ?: return
                        val location = item.localConfiguration?.uri ?: return
                        selected = Song(location,
                            item.mediaMetadata.title?.toString() ?: "Ses yayını",
                            item.mediaMetadata.artist?.toString() ?: "Resonance")
                    }
                    override fun onPlayerError(playerError: PlaybackException) {
                        error = "Ses kaynağı açılamadı: ${playerError.localizedMessage}"
                        playing = false
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
            val playExternal: (ExternalPlaylistItem) -> Unit = { track ->
                runCatching {
                    val active = controller ?: error("Oynatıcı henüz hazır değil")
                    val item = MediaItem.Builder().setUri(track.playbackUri)
                        .setMediaMetadata(MediaMetadata.Builder()
                            .setTitle(track.title).setArtist(track.artist).build())
                        .build()
                    active.setMediaItem(item)
                    active.prepare()
                    active.play()
                    selected = Song(Uri.parse(track.playbackUri), track.title, track.artist)
                    playing = true
                    error = null
                }.onFailure { error = it.localizedMessage }
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = lime, background = bg, surface = panel)) {
                Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("RESONANCE", color = lime, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { page = if (page == 3) 5 else 3 }) { Text(if (page == 3) "Geri" else "Spotify hesabı", color = lime) }
                        IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                            Icon(Icons.Rounded.LibraryAdd, contentDescription = "Müzik içe aktar", tint = lime)
                        }
                    }
                    if (page == 3) {
                        SpotifyScreen(this@MainActivity, playExternal)
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
