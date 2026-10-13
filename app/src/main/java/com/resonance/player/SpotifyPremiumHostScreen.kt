package com.resonance.player

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native Spotify engine is app/service-owned, not Compose-owned: navigating to
 * another tab does not disconnect the Premium stream.
 */
@Composable
fun SpotifyPremiumHostScreen(
    activity: Activity,
    requestedTrack: SpotifyTrack?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf("native") }
    Column(modifier.background(Color(0xFF080B0A))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "native", onClick = { mode = "native" },
                label = { Text("Rust · librespot") })
            FilterChip(selected = mode == "web", onClick = {
                SpotifyAudioEngine.pause()
                mode = "web"
            }, label = { Text("Web SDK yedek") })
        }
        if (mode == "native") {
            PremiumNativeControls(activity, requestedTrack, onBack, Modifier.weight(1f))
        } else {
            PremiumSpotifyWebScreen(activity, requestedTrack, onBack,
                modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PremiumNativeControls(
    activity: Activity,
    track: SpotifyTrack?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lime = Color(0xFFC6F17B)
    val client = remember { SpotifyClient(activity) }
    val engine = SpotifyAudioEngine
    var launchError by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }

    suspend fun authenticate() {
        if (engine.ready || engine.connecting) return
        try {
            val token = withContext(Dispatchers.IO) { client.webPlaybackToken() }
            engine.connect(activity, token, client.clientId)
        } catch (e: Exception) {
            launchError = "Spotify bağlantısı: ${e.localizedMessage ?: "Yetkilendirme hatası"}"
        }
    }

    LaunchedEffect(track?.uri, client.authorized, retry) {
        if (!client.authorized) return@LaunchedEffect
        if (track != null && track.uri != engine.trackUri) engine.load(track)
        authenticate()
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Geri", tint = lime)
            }
            Text("Spotify Premium", color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 10.dp))
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171D19)),
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Icon(Icons.Rounded.Headphones, null, tint = lime, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(14.dp))
                Text(engine.title.ifBlank { track?.title ?: "Spotify Premium" },
                    color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(engine.artist.ifBlank { track?.artist ?: "Bir parça seç" },
                    color = Color.LightGray)
                Spacer(Modifier.height(10.dp))
                Text(engine.status, color = lime, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!client.authorized) {
            Text("Spotify Premium için önce hesabını bağla.",
                color = MaterialTheme.colorScheme.error)
            Button(onClick = onBack) { Text("Hesaba dön") }
        } else {
            if (launchError.isNotBlank()) Text(launchError,
                color = MaterialTheme.colorScheme.error)
            if (engine.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!engine.ready && !engine.connecting) {
                Button(onClick = {
                    launchError = ""
                    // The LaunchedEffect below triggers token refresh on demand.
                    retry++
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Spotify Rust bağlantısını yeniden dene")
                }
            }
            if (engine.ready && track != null) {
                Button(onClick = { engine.load(track) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Bu parçayı Spotify'dan çal")
                }
            }
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { engine.onPrevious?.invoke() ?: engine.seek(0) },
                    enabled = engine.ready) {
                    Icon(Icons.Rounded.SkipPrevious, "Önceki", tint = lime)
                }
                IconButton(onClick = { engine.toggle() }, enabled = engine.ready) {
                    Icon(if (engine.playing) Icons.Rounded.PauseCircleFilled
                        else Icons.Rounded.PlayCircleFilled,
                        if (engine.playing) "Duraklat" else "Çal",
                        modifier = Modifier.size(52.dp), tint = lime)
                }
                IconButton(onClick = { engine.onNext?.invoke() }, enabled = engine.ready) {
                    Icon(Icons.Rounded.SkipNext, "Sonraki", tint = lime)
                }
            }
            if (engine.durationMs > 0) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                val value = dragging ?: engine.positionMs.toFloat()
                Slider(value = value.coerceIn(0f,engine.durationMs.toFloat()),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { engine.seek(it.toInt()) }
                        dragging = null
                    }, valueRange = 0f..engine.durationMs.toFloat())
            }
            OutlinedButton(onClick = { engine.stop() }) {
                Text("Spotify oynatmayı kapat")
            }
        }
        Text("Bu motor ses çıkışını Android AudioTrack üzerinden verir ve "
            + "ekranlar arasında açık kalır. Gerçek Premium hesap testi hâlâ gereklidir.",
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    }
}
