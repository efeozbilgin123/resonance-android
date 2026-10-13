package com.resonance.player

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val green = Color(0xFFC6F17B)
private val darkTile = Color(0xFF171D19)

/**
 * One Spotify + YouTube Music + Audius search surface.
 *
 * Metadata is requested independently and rendered as each source finishes.
 * Spotify and YouTube tracks remain distinguishable so the user chooses the
 * correct recording/source; similarity is NOT treated as proof of same song.
 */
@Composable
fun UnifiedSearchScreen(
    activity: Activity,
    onPlay: (UnifiedSong, List<UnifiedSong>) -> Unit,
    onAdd: (UnifiedSong) -> Unit,
    playingTitle: String?,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit = {},
    initialLink: String? = null,
    modifier: Modifier = Modifier
) {
    val catalog = remember(activity) {
        UnifiedMusicCatalog(activity, SpotifyClient(activity))
    }
    val youtubeProvider = remember(activity) { YouTubeAudioProvider(activity) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Tümü") }
    var spotify by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var youtube by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var audius by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var spotifyLoading by remember { mutableStateOf(false) }
    var youtubeLoading by remember { mutableStateOf(false) }
    var audiusLoading by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var customServer by remember { mutableStateOf(youtubeProvider.preferredServer) }
    var settingsError by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var linkPreview by remember { mutableStateOf<UnifiedSong?>(null) }

    LaunchedEffect(initialLink) {
        val id = initialLink?.let(YouTubeLinks::parse) ?: return@LaunchedEffect
        // Android Share directly produces a playable YouTube Music row.
        val song = try {
            withContext(Dispatchers.IO) {
                val info = YouTubeInfo.resolve(id)
                UnifiedSong(id, MusicOrigin.YOUTUBE_MUSIC, info.title, info.channel,
                    cover = info.thumbnail)
            }
        } catch (_: Exception) {
            UnifiedSong(id, MusicOrigin.YOUTUBE_MUSIC, "YouTube Music videosu",
                "YouTube Music", cover = "https://i.ytimg.com/vi/$id/hqdefault.jpg")
        }
        linkPreview = song
    }

    // LaunchedEffect cancels stale searches whenever text/provider changes.
    // Each provider publishes results as they arrive instead of blocking others.
    LaunchedEffect(query, refresh) {
        spotify = emptyList()
        youtube = emptyList()
        audius = emptyList()
        errors = emptyMap()
        val q = query.trim()
        if (q.length < 2) {
            spotifyLoading = false
            youtubeLoading = false
            audiusLoading = false
            return@LaunchedEffect
        }
        delay(380)
        spotifyLoading = true
        youtubeLoading = true
        audiusLoading = true
        launch {
            try { spotify = withContext(Dispatchers.IO) { catalog.searchSpotify(q) } }
            catch (e: Exception) {
                errors = errors + ("Spotify" to (e.localizedMessage ?: "Arama hatası"))
            } finally { spotifyLoading = false }
        }
        launch {
            try { youtube = withContext(Dispatchers.IO) { catalog.searchYouTube(q) } }
            catch (e: Exception) {
                errors = errors + ("YouTube Music" to (e.localizedMessage
                    ?: "Kaynak sunucuları yanıt vermedi"))
            } finally { youtubeLoading = false }
        }
        launch {
            try { audius = withContext(Dispatchers.IO) { catalog.searchAudius(q) } }
            catch (e: Exception) {
                errors = errors + ("Audius" to (e.localizedMessage ?: "Arama hatası"))
            } finally { audiusLoading = false }
        }
    }

    val all = remember(query, spotify, youtube, audius) {
        MusicRelevance.rank(query, spotify + youtube + audius)
    }
    val filtered = remember(all, filter) {
        when (filter) {
            "Spotify" -> all.filter { it.source == MusicOrigin.SPOTIFY }
            "YouTube Music" -> all.filter { it.source == MusicOrigin.YOUTUBE_MUSIC }
            "Audius" -> all.filter { it.source == MusicOrigin.AUDIUS }
            else -> all
        }
    }

    Column(modifier.background(Color(0xFF080B0A))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Keşfet", color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Rounded.Tune, "YouTube sağlayıcı ayarları", tint = green)
            }
        }
        Text("Tek arama · Spotify, YouTube Music, Audius",
            modifier = Modifier.padding(start = 17.dp, end = 17.dp, bottom = 8.dp),
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("Şarkı, sanatçı veya albüm ara") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = if (query.isNotBlank()) {
                { IconButton(onClick = { query = "" }) {
                    Icon(Icons.Rounded.Close, "Temizle")
                } }
            } else null,
            singleLine = true, shape = RoundedCornerShape(16.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            listOf("Tümü", "Spotify", "YouTube Music", "Audius").forEach { option ->
                FilterChip(selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(
                        if (option == "YouTube Music") 1.55f else 0.92f
                    ))
            }
        }

        val loading = spotifyLoading || youtubeLoading || audiusLoading
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (errors.isNotEmpty()) {
            Text(errors.entries.joinToString(" · ") { (provider, why) ->
                "$provider: $why"
            }.take(320), color = Color(0xFFFFA6A6),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp))
        }
        if (linkPreview != null) {
            val shared = linkPreview!!
            Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                .background(darkTile, RoundedCornerShape(14.dp))
                .fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Paylaşılan YouTube videosu", color = green,
                        style = MaterialTheme.typography.labelMedium)
                    Text(shared.title, color = Color.White)
                }
                IconButton(onClick = { onPlay(shared, listOf(shared)) }) {
                    Icon(Icons.Rounded.PlayArrow, "Çal", tint = green)
                }
                IconButton(onClick = { onAdd(shared) }) {
                    Icon(Icons.Rounded.PlaylistAdd, "Listeye ekle", tint = green)
                }
                IconButton(onClick = { linkPreview = null }) {
                    Icon(Icons.Rounded.Close, "Kapat", tint = Color.LightGray)
                }
            }
        }
        if (query.isBlank() && linkPreview == null) {
            Column(Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.Headphones, null,
                    modifier = Modifier.size(55.dp), tint = green)
                Spacer(Modifier.height(12.dp))
                Text("Her şey tek bir aramada", color = Color.White,
                    fontWeight = FontWeight.SemiBold)
                Text("Bir şarkı ara; uygun kaynağı seçip direkt dinle.",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(
                start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (!loading && filtered.isEmpty() && query.isNotBlank()) {
                    item {
                        Text("Eşleşen şarkı bulunamadı. Sağ üstten YouTube sunucusunu değiştirebilirsin.",
                            color = Color.LightGray,
                            modifier = Modifier.padding(12.dp))
                    }
                }
                items(filtered, key = { it.stableKey }) { track ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(darkTile).clickable { onPlay(track, filtered) }
                        .padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (track.cover.isNotBlank()) {
                            AsyncImage(model = track.cover, contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp)
                                    .clip(RoundedCornerShape(10.dp)))
                        } else {
                            Box(Modifier.size(56.dp).background(Color(0xFF2B3930),
                                RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.MusicNote, null, tint = green)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = Color.White, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, color = Color.LightGray,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(when(track.source) {
                                    MusicOrigin.YOUTUBE_MUSIC -> "YouTube Music"
                                    MusicOrigin.SPOTIFY -> "Spotify · Premium"
                                    MusicOrigin.AUDIUS -> "Audius"
                                    MusicOrigin.LOCAL -> "Yerel"
                                    MusicOrigin.STREAM -> "Ses yayını"
                                }, style = MaterialTheme.typography.labelSmall, color = green)
                                if (track.duration > 0) {
                                    Text("  ·  ${track.duration / 60}:${(track.duration % 60)
                                        .toString().padStart(2,'0')}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.LightGray)
                                }
                            }
                        }
                        IconButton(onClick = { onAdd(track) }) {
                            Icon(Icons.Rounded.PlaylistAdd,
                                "Resonance / Spotify listesine ekle",
                                tint = green)
                        }
                        Icon(Icons.Rounded.PlayArrow, null, tint = green)
                    }
                }
            }
        }
        if (!playingTitle.isNullOrBlank()) {
            Row(Modifier.fillMaxWidth().background(darkTile)
                .clickable { onOpenPlayer() }.padding(
                horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(playingTitle, color = Color.White, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text("Resonance oynatıcı", color = green,
                        style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Rounded.SkipPrevious, "Önceki", tint = green)
                }
                IconButton(onClick = onToggle) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (isPlaying) "Duraklat" else "Oynat", tint = green)
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Rounded.SkipNext, "Sonraki", tint = green)
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("YouTube Music ses kaynağı") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Resonance, video görüntüsü olmadan ses URL'sini Piped/Invidious "
                        + "sunucularından çözümlemeyi dener. Bu gayriresmî yöntem "
                        + "YouTube hizmet koşullarıyla uyuşmayabilir ve sunucular kapanabilir.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(customServer, { customServer = it },
                        label = { Text("Özel HTTPS API sunucusu (opsiyonel)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Boş bırakırsan varsayılan sunucular sırayla denenir.",
                        style = MaterialTheme.typography.bodySmall)
                    if (settingsError.isNotBlank()) {
                        Text(settingsError, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        youtubeProvider.preferredServer = customServer
                        refresh++
                        showSettings = false
                        settingsError = ""
                    } catch (e: Exception) {
                        settingsError = e.localizedMessage.orEmpty()
                    }
                }) { Text("Kaydet") }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("Vazgeç") }
            }
        )
    }
}
