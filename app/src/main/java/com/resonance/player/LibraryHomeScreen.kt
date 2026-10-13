package com.resonance.player

import android.app.Activity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val limeLibrary = Color(0xFFC6F17B)
private val panelLibrary = Color(0xFF171D19)

/** Shows the user's music immediately. Advanced account/backup tools are opt-in. */
@Composable
fun LibraryHomeScreen(
    activity: Activity,
    onExternalPlay: (ExternalPlaylistItem) -> Unit,
    onPremiumPlay: (SpotifyTrack) -> Unit,
    onPlayQueue: (UnifiedSong, List<UnifiedSong>) -> Unit,
    modifier: Modifier = Modifier
) {
    val client = remember(activity) { SpotifyClient(activity) }
    val store = remember(activity) { HybridPlaylistStore(activity) }
    val favorites = remember(activity) { ResonanceLikesStore(activity) }
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(client.authorized) }
    var clientId by remember { mutableStateOf(client.clientId) }
    var accountId by remember { mutableStateOf(client.cachedAccountId) }
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var selection by remember { mutableStateOf<SpotifyPlaylist?>(null) }
    var section by remember { mutableStateOf("home") }
    var tracks by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var extras by remember { mutableStateOf<List<ExternalPlaylistItem>>(emptyList()) }
    var order by remember { mutableStateOf<List<String>>(emptyList()) }
    var liked by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var saved by remember { mutableStateOf<Set<String>>(emptySet()) }
    var externalLiked by remember { mutableStateOf(favorites.entries()) }
    var hasMore by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    fun task(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = ""
            try { block() } catch (e: Exception) {
                error = e.localizedMessage ?: "İşlem başarısız"
            } finally { busy = false }
        }
    }

    LaunchedEffect(connected, reload) {
        if (!connected) return@LaunchedEffect
        busy = true
        try {
            val result = withContext(Dispatchers.IO) { client.ensureAccountId() to client.playlists() }
            accountId = result.first
            playlists = result.second
        } catch (e: Exception) { error = "Spotify kitaplığı: " + e.localizedMessage }
        finally { busy = false }
    }
    LaunchedEffect(section, selection?.id, accountId, reload) {
        val playlist = selection
        if (section != "playlist" || playlist == null || accountId.isBlank()) return@LaunchedEffect
        extras = store.entries(accountId, playlist.id)
        order = store.readOrder(accountId, playlist.id)
        busy = true
        try {
            val loaded = withContext(Dispatchers.IO) { client.playlistTracks(playlist.id) }
            tracks = loaded
            saved = withContext(Dispatchers.IO) { client.checkSavedTracks(loaded.map { it.uri }) }
        } catch (e: Exception) { error = "Liste yüklenemedi: " + e.localizedMessage }
        finally { busy = false }
    }
    LaunchedEffect(section, reload) {
        if (section == "external") externalLiked = favorites.entries()
        if (section != "liked" || !connected) return@LaunchedEffect
        busy = true
        try {
            liked = withContext(Dispatchers.IO) { client.savedTracks(0, 50) }
            saved = saved + liked.map { it.uri }
            hasMore = liked.size == 50
        } catch (e: Exception) { error = "Beğenilenler alınamadı: " + e.localizedMessage }
        finally { busy = false }
    }
    val merged = remember(tracks, extras, order) { HybridPlaylistOrder.merge(tracks, extras, order) }
    val queue = remember(merged) { merged.mapNotNull { it.unified() } }
    fun converted(t: SpotifyTrack) = UnifiedSong(t.uri, MusicOrigin.SPOTIFY, t.title,
        t.artist, duration = t.durationSeconds, cover = t.cover)
    fun play(t: UnifiedSong, list: List<UnifiedSong>) =
        onPlayQueue(t, list.ifEmpty { listOf(t) })
    fun heartSpotify(t: SpotifyTrack) {
        val old = t.uri in saved
        task {
            withContext(Dispatchers.IO) { client.setSavedTrack(t.uri, !old) }
            saved = if (old) saved - t.uri else saved + t.uri
            if (old && section == "liked") liked = liked.filterNot { it.uri == t.uri }
        }
    }
    fun heartExternal(t: UnifiedSong) {
        task {
            withContext(Dispatchers.IO) { favorites.toggle(t) }
            externalLiked = favorites.entries()
        }
    }

    if (advanced) {
        Column(modifier.fillMaxSize()) {
            TextButton(onClick = { advanced = false; reload++ }) {
                Icon(Icons.Rounded.ArrowBack, null)
                Text("Kitaplığa dön")
            }
            HybridSpotifyPlaylists(activity, onExternalPlay, onPremiumPlay,
                onPlayQueue, Modifier.weight(1f))
        }
        return
    }
    Column(modifier.fillMaxSize().background(Color(0xFF080B0A))
        .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (section != "home") IconButton(onClick = {
                section = "home"; selection = null; editing = false
            }) { Icon(Icons.Rounded.ArrowBack, "Geri", tint = Color.White) }
            else Icon(Icons.Rounded.LibraryMusic, null, tint = limeLibrary)
            Spacer(Modifier.width(8.dp))
            Text(when(section) {
                "liked" -> "Beğenilen Şarkılar"
                "external" -> "Resonance beğenileri"
                "playlist" -> selection?.name ?: "Çalma listesi"
                else -> "Kitaplığın"
            }, modifier = Modifier.weight(1f), color = Color.White, maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge)
            if (connected) {
                IconButton(onClick = { reload++ }) {
                    Icon(Icons.Rounded.Refresh, "Yenile", tint = limeLibrary)
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, "Diğer", tint = Color.White)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Liste düzenleme ve yedekler") },
                            onClick = { menu = false; advanced = true })
                        DropdownMenuItem(text = { Text("Spotify hesabını değiştir") },
                            onClick = {
                                menu = false; client.logout(); connected = false
                                section = "home"; selection = null; playlists = emptyList()
                            })
                    }
                }
            }
        }
        if (!connected) {
            OutlinedTextField(clientId, { clientId = it },
                label = { Text("Spotify Client ID") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true)
            Text("Redirect URI: http://127.0.0.1:43892/callback",
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && clientId.isNotBlank(), onClick = {
                client.clientId = clientId
                busy = true
                client.login { issue ->
                    busy = false
                    if (issue != null) error = issue else { connected = true; reload++ }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Spotify hesabını bağla") }
        } else {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = Color(0xFFFFA6A6),
                style = MaterialTheme.typography.bodySmall)
            LazyColumn(modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                if (section == "home") {
                    item {
                        LibraryTile("Beğenilen Şarkılar", "Spotify hesabınla eşitlenir",
                            "", Icons.Rounded.Favorite) { section = "liked" }
                    }
                    item {
                        LibraryTile("Resonance beğenileri",
                            "YouTube, Audius ve yerel parçalar · bu cihazda",
                            "", Icons.Rounded.Headphones) { section = "external" }
                    }
                    item {
                        Text("Spotify çalma listelerin", color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium)
                    }
                    items(playlists, key = { it.id }) { p ->
                        LibraryTile(p.name, "Spotify çalma listesi", p.cover,
                            Icons.Rounded.QueueMusic) {
                            selection = p; section = "playlist"; editing = false
                        }
                    }
                } else if (section == "liked") {
                    item {
                        PhonePlayButton("Bu telefonda çal · Spotify Premium",
                            enabled = liked.isNotEmpty()) {
                            val all = liked.map(::converted)
                            all.firstOrNull()?.let { play(it, all) }
                        }
                    }
                    items(liked, key = { it.uri }) { t ->
                        LibraryTrack(t.title, t.artist, t.cover, "Spotify", t.uri in saved,
                            onPlay = {
                                play(converted(t), liked.map(::converted))
                            }, onHeart = { heartSpotify(t) })
                    }
                    if (hasMore) item {
                        TextButton(onClick = {
                            task {
                                val page = withContext(Dispatchers.IO) {
                                    client.savedTracks(liked.size, 50)
                                }
                                liked = (liked + page).distinctBy { it.uri }
                                saved = saved + page.map { it.uri }
                                hasMore = page.size == 50
                            }
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Daha fazla beğenilen şarkı")
                        }
                    }
                } else if (section == "external") {
                    item {
                        Text("Bu beğeniler yalnız Resonance'ta saklanır.",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall)
                        PhonePlayButton("Bu telefonda çal", externalLiked.isNotEmpty()) {
                            externalLiked.firstOrNull()?.let { play(it, externalLiked) }
                        }
                    }
                    items(externalLiked, key = { it.stableKey }) { t ->
                        LibraryTrack(t.title, t.artist, t.cover, t.source.name, true,
                            onPlay = { play(t, externalLiked) },
                            onHeart = { heartExternal(t) })
                    }
                } else if (section == "playlist" && selection != null) {
                    val detail = selection!!
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LibraryArtwork(detail.cover, Modifier.size(72.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(detail.name, color = Color.White,
                                    fontWeight = FontWeight.Bold)
                                Text(merged.size.toString() + " parça · Spotify + Resonance",
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        PhonePlayButton("Bu telefonda çal", queue.isNotEmpty()) {
                            queue.firstOrNull()?.let { play(it, queue) }
                        }
                        TextButton(onClick = { editing = !editing }) {
                            Text(if (editing) "Düzenlemeyi bitir" else "Sırayı düzenle")
                        }
                    }
                    itemsIndexed(merged, key = { _, row -> row.key }) { index, row ->
                        val song = row.unified()
                        LibraryTrack(row.title, row.artist,
                            row.spotify?.cover ?: row.external?.artwork.orEmpty(), row.origin,
                            if (row.spotify != null) row.spotify.uri in saved
                            else song != null && externalLiked.any { it.stableKey == song.stableKey },
                            onPlay = {
                                if (song != null) play(song, queue)
                                else error = "Bu yerel Spotify dosyası çalınamıyor."
                            },
                            onHeart = {
                                if (row.spotify != null && !row.spotify.local)
                                    heartSpotify(row.spotify)
                                else if (song != null && song.source != MusicOrigin.SPOTIFY)
                                    heartExternal(song)
                            },
                            onUp = if (editing && index > 0) {
                                {
                                    val next = HybridPlaylistOrder.swap(merged,index,index-1)
                                    store.saveOrder(accountId, detail.id, next); order = next
                                }
                            } else null,
                            onDown = if (editing && index < merged.lastIndex) {
                                {
                                    val next = HybridPlaylistOrder.swap(merged,index,index+1)
                                    store.saveOrder(accountId, detail.id, next); order = next
                                }
                            } else null)
                    }
                    item {
                        Text("YouTube ve Audius kayıtları Spotify'a yazılmaz; yalnız Resonance'ta görünür.",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (!connected && error.isNotBlank()) Text(error, color = Color(0xFFFFA6A6))
    }
}

@Composable
private fun PhonePlayButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Icon(Icons.Rounded.Smartphone, null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun LibraryArtwork(url: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp))
        .background(Color(0xFF304134)), contentAlignment = Alignment.Center) {
        if (url.startsWith("https://")) {
            AsyncImage(model = url, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else Icon(Icons.Rounded.MusicNote, null, tint = limeLibrary,
            modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun LibraryTile(title: String, caption: String, cover: String,
    icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
        .background(panelLibrary).clickable(onClick = onClick).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(66.dp).background(Color(0xFF304134),
            RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            if (cover.startsWith("https://"))
                LibraryArtwork(cover, Modifier.fillMaxSize())
            else Icon(icon, null, tint = limeLibrary, modifier = Modifier.size(29.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(caption, color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = limeLibrary)
    }
}

@Composable
private fun LibraryTrack(title: String, artist: String, cover: String,
    origin: String, liked: Boolean, onPlay: () -> Unit, onHeart: () -> Unit,
    onUp: (() -> Unit)? = null, onDown: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(panelLibrary).clickable(onClick = onPlay).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        LibraryArtwork(cover, Modifier.size(47.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist + " · " + origin, color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onUp != null) IconButton(onClick = onUp, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Rounded.KeyboardArrowUp, "Yukarı", tint = limeLibrary)
        }
        if (onDown != null) IconButton(onClick = onDown, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Rounded.KeyboardArrowDown, "Aşağı", tint = limeLibrary)
        }
        IconButton(onClick = onHeart) {
            Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (liked) "Beğeniyi kaldır" else "Beğen",
                tint = if (liked) Color(0xFFFF799C) else Color.LightGray)
        }
    }
}
