package com.resonance.player

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val searchAccent = Color(0xFFC6F17B)
private val searchPanel = Color(0xFF171D19)

/**
 * One search field, one relevance-ranked list across independent providers.
 *
 * The user selects a source explicitly:
 * - Spotify Premium -> existing Rust/librespot player
 * - YouTube audio -> Media3 background player; no WebView/embedded video.
 * Spotify data and YouTube audio are not merged into a Spotify API write.
 */
@Composable
fun UnifiedSearchScreen(
    activity: Activity,
    onYouTubePlay: (UnifiedMusicTrack, List<UnifiedMusicTrack>) -> Unit,
    onSpotifyPlay: (SpotifyTrack) -> Unit,
    onExternalAdd: (ExternalPlaylistItem) -> Unit,
    onSpotifyAdd: (SpotifyTrack) -> Unit,
    modifier: Modifier = Modifier,
    incomingUrl: String? = null,
    consumeIncomingUrl: () -> Unit = {}
) {
    val client = remember { SpotifyClient(activity) }
    val keyboard = LocalSoftwareKeyboardController.current
    var input by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf("Hepsi") }
    var loading by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf(UnifiedSearchResult(emptyList(), emptyList(), client.authorized)) }
    var importVideo by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf("") }
    var requestKey by remember { mutableIntStateOf(0) }
    var premiumAccount by remember { mutableStateOf<Boolean?>(null) }
    var choosingAlternativeFor by remember { mutableStateOf<UnifiedMusicTrack?>(null) }
    var alternatives by remember { mutableStateOf<List<UnifiedMusicTrack>>(emptyList()) }
    var alternativesBusy by remember { mutableStateOf(false) }
    var alternativeError by remember { mutableStateOf("") }

    LaunchedEffect(client.authorized) {
        if (!client.authorized) return@LaunchedEffect
        premiumAccount = withContext(Dispatchers.IO) {
            runCatching { client.get("/me").optString("product") == "premium" }.getOrNull()
        }
    }

    LaunchedEffect(choosingAlternativeFor?.id) {
        val original = choosingAlternativeFor ?: return@LaunchedEffect
        alternativesBusy = true
        alternativeError = ""
        alternatives = emptyList()
        try {
            val results = withContext(Dispatchers.IO) {
                YouTubeMusicProvider.search("${original.title} ${original.artist}")
            }
            alternatives = results.map { record ->
                UnifiedMusicTrack(record.videoId, record.title, record.artist,
                    record.durationSeconds, record.artwork, CatalogSource.YOUTUBE,
                    record.musicLikelihood, record.musicLikelihood > 0.9)
            }.sortedByDescending { RelevanceRanker.crossSourceScore(original, it) }.take(10)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            alternativeError = e.localizedMessage ?: "YouTube Music araması başarısız"
        } finally {
            alternativesBusy = false
        }
    }

    fun playSelected(track: UnifiedMusicTrack) {
        if (track.source == CatalogSource.YOUTUBE) {
            onYouTubePlay(track, data.tracks)
            return
        }
        if (premiumAccount == true) {
            track.spotifyItem()?.let(onSpotifyPlay)
            return
        }
        val best = data.tracks
            .asSequence().filter { it.source == CatalogSource.YOUTUBE }
            .map { it to RelevanceRanker.crossSourceScore(track, it) }
            .maxByOrNull { it.second }
        if (best != null && best.second >= 0.78) {
            onYouTubePlay(best.first, data.tracks)
        } else {
            // Never silently substitute an unverified cover or unrelated song.
            choosingAlternativeFor = track
        }
    }


    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrBlank()) {
            importVideo = incomingUrl
            consumeIncomingUrl()
        }
    }

    LaunchedEffect(submitted, requestKey) {
        val query = submitted.trim()
        if (query.length < 2) return@LaunchedEffect
        loading = true
        warning = ""
        try {
            data = UnifiedCatalog.search(query, client, client.authorized)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warning = "Arama başarısız: ${e.localizedMessage ?: "İnternet bağlantısını kontrol et"}"
        } finally {
            loading = false
        }
    }

    fun submit() {
        val raw = input.trim()
        if (raw.isBlank()) return
        val linked = YouTubeLinks.parse(raw)
        if (linked != null) {
            importVideo = raw
        } else {
            submitted = raw
            if (submitted == raw) requestKey++
            keyboard?.hide()
        }
    }

    Column(modifier.fillMaxWidth().background(Color(0xFF080B0A))) {
        Text("Tek arama. Tüm müzikler.", Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold, color = Color.White)
        Text("Spotify kataloğu + YouTube müzik kayıtları · alaka düzeyine göre",
            Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
            style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f), singleLine = true,
                label = { Text("Şarkı, sanatçı veya YouTube bağlantısı") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() })
            )
            Spacer(Modifier.width(7.dp))
            FilledTonalIconButton(onClick = { submit() }, enabled = input.isNotBlank()) {
                Icon(Icons.Rounded.Search, "İki katalogda ara")
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Hepsi", "Spotify", "YouTube").forEach { option ->
                FilterChip(
                    selected = sourceFilter == option,
                    onClick = { sourceFilter = option },
                    label = { Text(option) }
                )
            }
        }
        if (!client.authorized) {
            Text("Spotify hesabın bağlı değil. YouTube sonuçları yine aranır; " +
                "Spotify sonuçları için sağ üstteki Spotify hesabına giriş yap.",
                Modifier.padding(horizontal = 18.dp),
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (warning.isNotBlank()) Text(warning, Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.error)
        data.warnings.forEach {
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
                color = Color(0xFFFFA59B), style = MaterialTheme.typography.bodySmall)
        }
        val filtered = data.tracks.filter {
            when (sourceFilter) {
                "Spotify" -> it.source == CatalogSource.SPOTIFY
                "YouTube" -> it.source == CatalogSource.YOUTUBE
                else -> true
            }
        }
        if (!loading && filtered.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Headphones, null, Modifier.size(48.dp), tint = searchAccent)
                Spacer(Modifier.height(12.dp))
                Text(if (submitted.isBlank()) "Bir şarkı veya sanatçı ara."
                    else "Bu aramada eşleşme bulunamadı.",
                    color = Color.LightGray)
                if (submitted.isNotBlank()) {
                    TextButton(onClick = { requestKey++ }) { Text("Yeniden dene") }
                }
            }
        } else {
            Text("${filtered.size} sonuç · Karışık alaka sıralaması",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(filtered, key = { it.stableId }) { track ->
                    val youtube = track.source == CatalogSource.YOUTUBE
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                        .background(searchPanel)
                        .clickable { playSelected(track) }
                        .padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).clip(RoundedCornerShape(9.dp))) {
                            if (track.cover.startsWith("https://")) {
                                AsyncImage(
                                    model = track.cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Rounded.Album, null,
                                    Modifier.fillMaxSize().padding(10.dp),
                                    tint = searchAccent)
                            }
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = Color.White,
                                fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(track.artist, color = Color.LightGray,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                val label = when {
                                    track.source == CatalogSource.SPOTIFY -> "Spotify"
                                    track.verifiedMusic -> "YouTube Music"
                                    else -> "YouTube"
                                }
                                Text(label, color = if (youtube) Color(0xFFFFB0A9) else searchAccent,
                                    style = MaterialTheme.typography.labelSmall)
                                val duration = track.duration
                                if (duration > 0) Text(
                                    "· ${duration / 60}:${(duration % 60).toString().padStart(2, '0')}",
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        IconButton(onClick = {
                            if (youtube) track.externalItem()?.let(onExternalAdd)
                            else track.spotifyItem()?.let(onSpotifyAdd)
                        }) {
                            Icon(Icons.Rounded.PlaylistAdd,
                                "Seçili parçayı listeye ekle", tint = searchAccent)
                        }
                        IconButton(onClick = { playSelected(track) }) {
                            Icon(Icons.Rounded.PlayArrow,
                                if (youtube) "YouTube müziğini çal"
                                else if (premiumAccount == true) "Spotify Premium'dan çal"
                                else "YouTube Music'te doğru kaydı eşleştir",
                                tint = searchAccent)
                        }
                    }
                }
            }
        }
    }
    choosingAlternativeFor?.let { original ->
        AlertDialog(
            onDismissRequest = { choosingAlternativeFor = null },
            title = { Text("YouTube Music kaydını seç") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${original.title} — ${original.artist}",
                        style = MaterialTheme.typography.titleSmall)
                    Text("Spotify Premium bağlantısı bulunamadı. Aynı şarkının YouTube Music karşılığını seç; canlı/cover kayıtlar otomatik seçilmez.",
                        style = MaterialTheme.typography.bodySmall)
                    if (alternativesBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (alternativeError.isNotBlank()) {
                        Text(alternativeError, color = MaterialTheme.colorScheme.error)
                    }
                    LazyColumn(Modifier.heightIn(max = 350.dp)) {
                        items(alternatives, key = { it.id }) { candidate ->
                            val score = RelevanceRanker.crossSourceScore(original, candidate)
                            TextButton(onClick = {
                                onYouTubePlay(candidate, listOf(candidate) + alternatives
                                    .filterNot { it.id == candidate.id })
                                choosingAlternativeFor = null
                            }) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(candidate.title, maxLines = 1)
                                    Text("${candidate.artist} · Eşleşme %${(score * 100).toInt()}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    original.spotifyItem()?.let(onSpotifyPlay)
                    choosingAlternativeFor = null
                }) { Text("Spotify Premium dene") }
            },
            dismissButton = {
                TextButton(onClick = { choosingAlternativeFor = null }) { Text("Vazgeç") }
            }
        )
    }
    importVideo?.let { url ->
        YouTubeImportDialog(initialUrl = url,
            onSave = { entry ->
                importVideo = null
                onExternalAdd(entry.asExternal())
            },
            onDismiss = { importVideo = null }
        )
    }
}
