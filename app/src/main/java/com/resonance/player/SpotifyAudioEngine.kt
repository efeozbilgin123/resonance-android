package com.resonance.player

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App-owned Spotify audio session. Screen changes no longer stop librespot;
 * a dedicated Android foreground service owns the media notification and
 * audio focus. Spotify is still experimental and requires a Premium account.
 */
object SpotifyAudioEngine {
    var status by mutableStateOf("Spotify Premium oynatıcısı bağlı değil.")
        private set
    var connecting by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var trackUri by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var artist by mutableStateOf("")
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set

    var onEndOfTrack: (() -> Unit)? = null
    var onUnavailable: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    private var pendingTrack: String? = null
    private var output: SpotifyPcmOutput? = null
    private var appContext: Context? = null
    private var generation = 0

    fun connect(context: Context, token: String, clientId: String) {
        if (connecting || ready) return
        val app = context.applicationContext
        appContext = app
        connecting = true
        status = "Spotify Premium bağlantısı kuruluyor..."
        try {
            SpotifyPlaybackService.begin(app)
            val current = ++generation
            // Never release the prior sink while JNI may still be draining it.
            val receiver = SpotifyPcmOutput(
                onStatusChanged = { msg ->
                    if (current == generation) status = msg
                },
                onEngineEventReceived = { code, millis ->
                    if (current == generation) receive(code, millis)
                }
            )
            output = receiver
            val started = SpotifyNativeBridge.start(app, token, clientId, receiver)
            if (!started) {
                connecting = false
                status = "Rust oynatma motoru başlatılamadı."
                SpotifyPlaybackService.finish(app)
            }
        } catch (e: Throwable) {
            connecting = false
            status = "Spotify motoru: ${e.localizedMessage ?: e.javaClass.simpleName}"
            SpotifyPlaybackService.finish(app)
        }
    }

    fun load(track: SpotifyTrack): Boolean {
        if (!track.uri.startsWith("spotify:track:")) {
            status = "Spotify parça kimliği geçersiz."
            return false
        }
        trackUri = track.uri
        title = track.title
        artist = track.artist
        durationMs = track.durationSeconds.coerceAtLeast(0) * 1000
        positionMs = 0
        pendingTrack = if (ready) null else track.uri
        SpotifyPlaybackService.refresh()
        return if (ready) {
            val ok = SpotifyNativeBridge.play(track.uri)
            if (!ok) status = "Spotify motoruna şarkı gönderilemedi."
            ok
        } else true
    }

    fun toggle() {
        if (!ready) return
        if (playing) pause() else resume()
    }
    fun pause() {
        if (ready) SpotifyNativeBridge.pause()
        playing = false
        SpotifyPlaybackService.refresh()
    }
    fun resume() {
        if (ready) SpotifyNativeBridge.resume()
        playing = true
        SpotifyPlaybackService.refresh()
    }
    fun seek(position: Int) {
        if (ready) SpotifyNativeBridge.seek(position.coerceAtLeast(0))
    }
    fun stop() {
        generation++
        runCatching { SpotifyNativeBridge.shutdown() }
        output?.onStop()
        output = null
        connecting = false
        ready = false
        playing = false
        pendingTrack = null
        status = "Spotify Premium oynatıcısı durduruldu."
        appContext?.let { SpotifyPlaybackService.finish(it) }
    }

    private fun receive(code: Int, millis: Int) {
        when (code) {
            1 -> {
                ready = true
                connecting = false
                pendingTrack?.let { uri ->
                    pendingTrack = null
                    if (!SpotifyNativeBridge.play(uri)) {
                        status = "Spotify parçası başlatılamadı."
                    }
                }
            }
            2 -> { playing = true; positionMs = millis }
            3 -> { playing = false; positionMs = millis }
            4 -> { playing = false; onEndOfTrack?.invoke() }
            5 -> { playing = false; onUnavailable?.invoke() }
            6 -> { playing = false; ready = false; connecting = false }
            7 -> positionMs = millis
            8 -> { connecting = false; ready = false; playing = false }
        }
        if (code in 1..6 || code == 8) SpotifyPlaybackService.refresh()
    }
}
