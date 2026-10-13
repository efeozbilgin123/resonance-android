package com.resonance.player

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spotify Premium sound comes from librespot in Rust and Android AudioTrack.
 * Does not require the official Spotify app or a browser engine.
 *
 * Until verified on actual hardware, DRM/login/network reliability is beta.
 * The app preserves the official Web SDK fallback for compatible devices.
 */
@Composable
fun SpotifyPremiumHostScreen(
    activity: Activity,
    requestedTrack: SpotifyTrack?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var engine by remember { mutableStateOf("native") }
    Column(modifier.background(Color(0xFF080B0A))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = engine == "native", onClick = { engine = "native" },
                label = { Text("Rust · librespot") })
            FilterChip(selected = engine == "web", onClick = { engine = "web" },
                label = { Text("Web SDK yedek") })
        }
        if (engine == "native") {
            SpotifyPremiumNativeScreen(activity, requestedTrack, onBack,
                modifier = Modifier.weight(1f))
        } else {
            PremiumSpotifyWebScreen(activity, requestedTrack, onBack,
                modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SpotifyPremiumNativeScreen(
    activity: Activity,
    requestedTrack: SpotifyTrack?,
    onBack: () -> Unit,
    modifier: Modifier
) {
    val lime = Color(0xFFC6F17B)
    val client = remember { SpotifyClient(activity) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Spotify Premium için Rust motoru başlatılmadı.") }
    var busy by remember { mutableStateOf(false) }
    var attached by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableFloatStateOf(0f) }
    val durationMs = (requestedTrack?.durationSeconds ?: 0).coerceAtLeast(0) * 1000f
    val pcm = remember(activity) { SpotifyPcmOutput { status = it } }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { SpotifyNativeBridge.shutdown() }
            // JNI worker stops cooperatively; AudioTrack is released only after
            // it has stopped accepting PCM. Next screen rebuilds the sink.
            // onStop safely discards buffers. Release on activity destruction.
            pcm.onStop()
        }
    }
    Column(modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Spotify listelerine dön", tint = lime)
            }
            Text("Spotify Premium · Native Rust", color = Color.White,
                style = MaterialTheme.typography.titleLarge)
        }
        Text("Spotifast ile aynı temel oynatma kütüphanesi: librespot. "
            + "Spotify uygulaması yüklenmeden Spotify kaynaklı PCM sesi Android AudioTrack'te çalmayı dener.",
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171D19)),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(17.dp)) {
                Text(requestedTrack?.title ?: "Spotify Premium",
                    color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(requestedTrack?.artist ?: "Bir Spotify parçası seç",
                    color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text(status, color = lime, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!client.authorized) {
            Text("Önce Spotify hesabına giriş yap. Hesabın Premium olmalı.",
                color = MaterialTheme.colorScheme.error)
            Button(onClick = onBack) { Text("Hesap bağlantısına dön") }
        } else {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    status = "Spotify OAuth token alınıyor..."
                    scope.launch {
                        try {
                            val token = withContext(Dispatchers.IO) {
                                client.webPlaybackToken()
                            }
                            attached = SpotifyNativeBridge.start(
                                activity, token, client.clientId, pcm)
                            if (!attached) status = "Rust native motor başlatılamadı."
                        } catch (e: Exception) {
                            attached = false
                            status = "Spotify oturumu: ${e.localizedMessage ?: "Token hatası"}"
                        } finally { busy = false }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, null)
                Spacer(Modifier.width(8.dp))
                Text(if (attached) "Bağlantıyı yeniden kur" else "Premium Rust oynatıcıyı bağla")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (requestedTrack != null && attached) {
                Button(
                    enabled = requestedTrack.uri.startsWith("spotify:track:"),
                    onClick = {
                        val ok = SpotifyNativeBridge.play(requestedTrack.uri)
                        if (!ok) status = "Rust oynatıcısına parça gönderilemedi."
                        else playing = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Bu şarkıyı Spotify'dan doğrudan çal")
                }
            }
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = attached, onClick = {
                    SpotifyNativeBridge.stopTrack()
                    playing = false
                }) { Icon(Icons.Rounded.StopCircle, "Durdur", tint = lime) }
                Spacer(Modifier.width(24.dp))
                IconButton(enabled = attached, onClick = {
                    if (playing) SpotifyNativeBridge.pause()
                    else SpotifyNativeBridge.resume()
                    playing = !playing
                }) {
                    Icon(if (playing) Icons.Rounded.PauseCircleFilled
                        else Icons.Rounded.PlayCircleFilled,
                        if (playing) "Duraklat" else "Çal",
                        modifier = Modifier.size(50.dp), tint = lime)
                }
            }
            if (durationMs > 0f && attached) {
                Slider(value = positionMs, onValueChange = { positionMs = it },
                    onValueChangeFinished = {
                        SpotifyNativeBridge.seek(positionMs.toInt())
                    }, valueRange = 0f..durationMs)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("BETA: Bu Rust motorunun Spotify Connect cihazı olarak görünmesi "
            + "ve kilit ekranı kontrolü henüz tamamlanmadı. "
            + "Token yetkisi veya Android ses çıkışı sorun yaşarsa Web SDK yedeğine geç.",
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    }
}
