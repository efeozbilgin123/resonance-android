package com.resonance.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Experimental Spotify Premium audio, straight from Spotify's official Web Playback SDK.
 * IMPORTANT: Android WebView is NOT on Spotify's guaranteed list of browsers.
 * The SDK needs EME DRM support; some WebViews will never create a player.
 *
 * The locally packaged player uses https://appassets.androidplatform.net.
 * It has no JavascriptInterface: token is injected only into the trusted main
 * document via evaluateJavascript; iframe subdocuments cannot call a native
 * token bridge. No tokens are transmitted to Audius or other audio providers.
 */
@Composable
fun PremiumSpotifyWebScreen(
    activity: Activity,
    requestedTrack: SpotifyTrack?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val client = remember { SpotifyClient(activity) }
    var view by remember { mutableStateOf<WebView?>(null) }
    var readyForToken by remember { mutableStateOf(false) }
    var deviceId by remember { mutableStateOf("") }
    var sdkStatus by remember { mutableStateOf("Spotify SDK yükleniyor...") }
    var taskStatus by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(view, readyForToken) {
        if (view == null || !readyForToken || !client.authorized) return@LaunchedEffect
        while (isActive) {
            try {
                val token = withContext(Dispatchers.IO) { client.webPlaybackToken() }
                view?.evaluateJavascript(
                    "window.resonanceSetToken(" + JSONObject.quote(token) + ")", null)
            } catch (e: Exception) {
                taskStatus = "Spotify erişim bilgisi alınamadı: ${e.localizedMessage}"
            }
            delay(4 * 60 * 1000L)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            view?.let {
                it.stopLoading()
                it.loadUrl("about:blank")
                it.destroy()
            }
        }
    }

    Column(modifier.background(Color(0xFF080B0A)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Geri", tint = Color.White)
            }
            Text("Spotify Premium · Deneysel", color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(top = 11.dp))
        }
        Text("Spotify'ın resmî Web Playback SDK'sı. Spotify uygulaması gerekmez; Premium ve DRM destekli bir WebView gerekir.",
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        if (!client.authorized) {
            Text("Önce Spotify hesabı ekranından bağlan, sonra buraya dön.",
                color = MaterialTheme.colorScheme.error)
            Button(onClick = onBack) { Text("Hesaba dön") }
        } else {
            Text(sdkStatus, color = Color(0xFFC6F17B),
                style = MaterialTheme.typography.bodySmall)
            if (taskStatus.isNotBlank()) Text(taskStatus,
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    val assets = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                        .build()
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                val msg = consoleMessage.message()
                                when {
                                    msg.startsWith("RESONANCE_SDK_READY:") -> {
                                        val id = msg.substringAfter(':')
                                        if (id.matches(Regex("[A-Za-z0-9_-]{6,256}"))) {
                                            deviceId = id
                                            sdkStatus = "Spotify Web SDK hazır. Cihazı etkinleştir."
                                        }
                                    }
                                    msg.startsWith("RESONANCE_SDK_ERROR:") -> {
                                        sdkStatus = msg.substringAfter(':').take(250)
                                    }
                                    msg.startsWith("RESONANCE_SDK_OFFLINE:") -> {
                                        deviceId = ""
                                        sdkStatus = "Spotify Web SDK bağlantısı kesildi."
                                    }
                                }
                                return super.onConsoleMessage(consoleMessage)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                v: WebView, request: WebResourceRequest
                            ): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
                            override fun shouldOverrideUrlLoading(
                                v: WebView, request: WebResourceRequest
                            ): Boolean = request.isForMainFrame &&
                                request.url.host != "appassets.androidplatform.net"
                            override fun onPageFinished(v: WebView, url: String) {
                                readyForToken = true
                            }
                        }
                        loadUrl("https://appassets.androidplatform.net/assets/spotify_premium.html")
                        view = this
                    }
                }
            )
            if (submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = {
                if (deviceId.isBlank()) {
                    taskStatus = "Önce web görünümündeki 'Oynatıcıyı etkinleştir' düğmesine bas."
                } else {
                    submitting = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val transfer = JSONObject()
                                    .put("device_ids", JSONArray().put(deviceId))
                                    .put("play", false)
                                client.put("/me/player", transfer)
                                val uri = requestedTrack?.uri
                                if (uri != null && uri.startsWith("spotify:track:")) {
                                    client.put("/me/player/play?device_id=$deviceId",
                                        JSONObject().put("uris", JSONArray().put(uri)))
                                }
                            }
                            taskStatus = if (requestedTrack == null) "Spotify Web Player etkinleştirildi."
                                else "Spotify parçası Spotify Web Player'a gönderildi."
                        } catch (e: Exception) {
                            taskStatus = "Oynatma başarısız: ${e.localizedMessage ?: "Spotify izin/DRM hatası"}"
                        } finally { submitting = false }
                    }
                }
            }, enabled = !submitting) {
                Text(if (requestedTrack == null) "Spotify Web Player'ı etkinleştir"
                    else "Seçili Spotify şarkısını çal")
            }
        }
        OutlinedButton(onClick = {
            val id = requestedTrack?.uri?.substringAfterLast(':') ?: ""
            val destination = if (id.matches(Regex("[A-Za-z0-9]+")))
                "https://open.spotify.com/track/$id"
                else "https://open.spotify.com/"
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
        }) {
            Icon(Icons.Rounded.OpenInBrowser, null)
            Spacer(Modifier.width(8.dp))
            Text("Spotify Web Player'ı tarayıcıda aç")
        }
        Text("WebView başarısız olursa Chrome/Firefox gibi Spotify'ın desteklediği bir mobil tarayıcı kullan. Bu APK kendi başına Spotify ses akışını çözdüğünü iddia etmez.",
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    }
}
