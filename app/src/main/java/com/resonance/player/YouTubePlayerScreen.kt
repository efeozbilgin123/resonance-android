package com.resonance.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader

/**
 * Authorized YouTube iframe, not an audio-only playback engine.
 *
 * YouTube retains controls, attribution and embedded-video restrictions.
 * Playback is foreground video, with device/user gesture requirements.
 * It is never sent through Android Media3 or the Spotify audio service.
 */
@Composable
fun YouTubePlayerScreen(
    activity: Activity,
    item: ExternalPlaylistItem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val id = item.id.takeIf { item.source == "youtube" &&
        it.matches(Regex("^[A-Za-z0-9_-]{11}$")) }
    val lime = Color(0xFFC6F17B)
    val root = remember(activity) { activity.window.decorView as ViewGroup }
    val overlay = remember(activity) { FrameLayout(activity) }
    val state = remember { YoutubeWebState() }

    DisposableEffect(id) {
        onDispose {
            state.fullscreenCallback?.onCustomViewHidden()
            root.removeView(overlay)
            state.view?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
            state.view = null
        }
    }

    Column(modifier.background(Color(0xFF080B0A)).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Geri", tint = lime)
            }
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Text(item.title, color = Color.White,
                    style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(item.artist, color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (id == null) {
            Text("Geçersiz YouTube kaydı.", color = MaterialTheme.colorScheme.error)
        } else {
            AndroidView(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                factory = { context ->
                    val assets = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                        .build()
                    WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onShowCustomView(
                                view: View, callback: CustomViewCallback
                            ) {
                                root.removeView(overlay)
                                overlay.removeAllViews()
                                overlay.addView(view, FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT))
                                root.addView(overlay, ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT))
                                state.fullscreenCallback = callback
                            }
                            override fun onHideCustomView() {
                                overlay.removeAllViews()
                                root.removeView(overlay)
                                state.fullscreenCallback = null
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                webView: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
                            override fun shouldOverrideUrlLoading(
                                webView: WebView, request: WebResourceRequest
                            ): Boolean {
                                if (!request.isForMainFrame) return false
                                if (request.url.host == "appassets.androidplatform.net") return false
                                val uri = request.url
                                if (uri.scheme == "https") {
                                    activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                                return true
                            }
                        }
                        loadUrl("https://appassets.androidplatform.net/assets/youtube_player.html?v=$id")
                        state.view = this
                    }
                }
            )
            Text(
                "Resmî YouTube gömülü oynatıcısı. YouTube'un reklam, telif ve video "
                    + "kısıtlamaları geçerlidir. Arka planda yalnızca ses çalma vaat edilmez.",
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                activity.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/watch?v=$id")))
            }) {
                Icon(Icons.Rounded.OpenInBrowser, null, tint = lime)
                Spacer(Modifier.width(8.dp))
                Text("YouTube'da aç")
            }
        }
    }
}
private class YoutubeWebState {
    var view: WebView? = null
    var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
}
