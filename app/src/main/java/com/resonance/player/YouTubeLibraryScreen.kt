package com.resonance.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private val youtubeLime = Color(0xFFC6F17B)
private val youtubeSurface = Color(0xFF171D19)

@Composable
fun YouTubeImportDialog(
    initialUrl: String,
    onSave: (YouTubeEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var title by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf("") }
    val candidate = remember(url) { YouTubeLinks.parse(url) }

    LaunchedEffect(candidate) {
        if (candidate == null) return@LaunchedEffect
        loading = true
        issue = ""
        try {
            val data = withContext(Dispatchers.IO) { YouTubeInfo.resolve(candidate) }
            title = data.title
            channel = data.channel
        } catch (_: Exception) {
            if (title.isBlank()) title = "YouTube videosu"
            if (channel.isBlank()) channel = "YouTube"
            issue = "Video bilgileri otomatik alınamadı. Adını kendin yazabilirsin."
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("YouTube'dan parça ekle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("YouTube veya YouTube Music paylaşım bağlantısını yapıştır. Video bağlantısı kaydedilir, ses akışı indirilmez.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(url, { url = it }, label = { Text("YouTube bağlantısı") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (url.isNotBlank() && candidate == null) {
                    Text("Geçerli youtube.com, music.youtube.com veya youtu.be bağlantısı gir.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedTextField(title, { title = it },
                    label = { Text("Video / parça adı") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(channel, { channel = it },
                    label = { Text("Sanatçı / kanal") }, modifier = Modifier.fillMaxWidth())
                if (issue.isNotBlank()) Text(issue, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = candidate != null && title.isNotBlank() && !loading,
                onClick = {
                    candidate?.let {
                        onSave(YouTubeEntry(it, title.trim(), channel.trim().ifBlank { "YouTube" }))
                    }
                }) { Text("Resonance'a ekle") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}

/** Reusable, local YouTube bookmarks; each video can also be attached to a Spotify-linked
 * Resonance playlist using the picker. The video is never uploaded to Spotify.
 */
@Composable
fun YouTubeLibraryScreen(
    activity: Activity,
    sharedUrl: String?,
    consumeShare: () -> Unit,
    onPlay: (ExternalPlaylistItem) -> Unit,
    onAddToPlaylist: (ExternalPlaylistItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = remember { activity.getSharedPreferences("youtube_library_v1", Activity.MODE_PRIVATE) }
    var saved by remember {
        mutableStateOf(
            runCatching {
                val list = JSONArray(prefs.getString("videos", "[]") ?: "[]")
                (0 until list.length()).mapNotNull { i ->
                    runCatching {
                        val j = list.getJSONObject(i)
                        val id = j.getString("id")
                        require(id.matches(Regex("^[a-zA-Z0-9_-]{11}$")))
                        YouTubeEntry(id, j.optString("title"), j.optString("channel"))
                    }.getOrNull()
                }
            }.getOrDefault(emptyList())
        )
    }
    var input by remember { mutableStateOf("") }
    var dialogUrl by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            dialogUrl = sharedUrl
            consumeShare()
        }
    }

    fun store(items: List<YouTubeEntry>) {
        saved = items
        val array = JSONArray()
        items.forEach { array.put(org.json.JSONObject()
            .put("id", it.videoId).put("title", it.title).put("channel", it.channel)) }
        prefs.edit().putString("videos", array.toString()).apply()
    }

    Column(modifier.fillMaxWidth().background(Color(0xFF080B0A))) {
        Text("YouTube müzikleri", Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold, color = Color.White)
        Text("YouTube bağlantılarını kaydet; resmî gömülü video oynatıcıyla izle/dinle.",
            Modifier.padding(horizontal = 20.dp, vertical = 7.dp),
            style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f),
                label = { Text("YouTube veya YouTube Music linki") }, singleLine = true)
            IconButton(onClick = { dialogUrl = input }) {
                Icon(Icons.Rounded.AddCircleOutline, "Videoyu ekle", tint = youtubeLime)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { dialogUrl = input }, modifier = Modifier.weight(1f)) {
                Text("Bağlantı ekle")
            }
            OutlinedButton(onClick = {
                activity.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=music")))
            }, modifier = Modifier.weight(1f)) { Text("YouTube'da ara") }
        }
        if (message.isNotBlank()) {
            Text(message, Modifier.padding(14.dp), color = youtubeLime,
                style = MaterialTheme.typography.bodySmall)
        }
        if (saved.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.SmartDisplay, null,
                    Modifier.size(54.dp), tint = youtubeLime)
                Spacer(Modifier.height(10.dp))
                Text("Henüz YouTube videosu eklemedin.",
                    color = Color.LightGray)
                Text("YouTube uygulamasında Paylaş → Resonance da kullanabilirsin.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(saved, key = { it.videoId }) { video ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(youtubeSurface).clickable { onPlay(video.asExternal()) }
                        .padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(video.thumbnail, contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 92.dp, height = 58.dp)
                                .clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(video.title, color = Color.White, maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium)
                            Text(video.channel, color = Color.LightGray,
                                maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { onAddToPlaylist(video.asExternal()) }) {
                            Icon(Icons.Rounded.PlaylistAdd,
                                "Spotify listesine Resonance eki olarak ekle", tint = youtubeLime)
                        }
                        IconButton(onClick = {
                            store(saved.filterNot { it.videoId == video.videoId })
                        }) {
                            Icon(Icons.Rounded.Close, "Kitaplıktan kaldır", tint = Color.LightGray)
                        }
                    }
                }
            }
        }
    }
    dialogUrl?.let { current ->
        YouTubeImportDialog(initialUrl = current,
            onSave = { video ->
                store(listOf(video) + saved.filterNot { it.videoId == video.videoId })
                input = ""
                dialogUrl = null
                message = "Video Resonance'a eklendi. Liste simgesi ile çalma listene bağla."
            },
            onDismiss = { dialogUrl = null })
    }
}
