package com.resonance.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * Native Spotify Premium playback:
 * Rust/librespot -> signed 16-bit PCM -> Android AudioTrack.
 * This does NOT use a Spotify WebView or extract YouTube audio.
 *
 * The engine is experimental until a real Premium device validates
 * authentication, decoding, Android audio routing and long-term stability.
 */
object SpotifyNativeBridge {
    init { System.loadLibrary("resonance_core") }

    @JvmStatic private external fun nativeStart(
        oauthToken: String,
        clientId: String,
        tempDirectory: String,
        sink: SpotifyPcmOutput
    ): Boolean
    @JvmStatic private external fun nativeCommand(
        code: Int, spotifyUri: String, positionMs: Int
    ): Boolean
    @JvmStatic private external fun nativeStop()

    fun start(context: Context, oauthToken: String, clientId: String,
        sink: SpotifyPcmOutput
    ): Boolean = nativeStart(oauthToken, clientId, context.cacheDir.absolutePath, sink)

    fun play(uri: String) = nativeCommand(4, uri, 0)
    fun pause() = nativeCommand(0, "", 0)
    fun resume() = nativeCommand(1, "", 0)
    fun stopTrack() = nativeCommand(2, "", 0)
    fun seek(milliseconds: Int) = nativeCommand(3, "", milliseconds)
    fun shutdown() = nativeStop()
}

/**
 * JNI invokes these methods from the decoder's audio thread.
 * PCM is streamed to Android's native audio device, not exposed to any
 * external streaming service. Playback stays inside this application.
 */
class SpotifyPcmOutput(
    private val onStatusChanged: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioTrack: AudioTrack? = null
    private val attributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .build()

    fun onStatus(status: String) {
        mainHandler.post { onStatusChanged(status.take(400)) }
    }

    @Synchronized
    fun onStart() {
        val existing = audioTrack
        if (existing != null) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                existing.play()
            }
            return
        }
        val minBytes = AudioTrack.getMinBufferSize(
            44_100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).takeIf { it > 0 } ?: 44_100 * 4
        val result = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(44_100)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes * 3, 44_100))
            .build()
        audioTrack = result
        result.play()
    }

    @Synchronized
    fun onPcm(chunk: ByteArray) {
        if (audioTrack == null) onStart()
        val output = audioTrack ?: return
        var offset = 0
        while (offset < chunk.size) {
            val count = output.write(chunk, offset, chunk.size - offset,
                AudioTrack.WRITE_BLOCKING)
            if (count <= 0) {
                onStatus("Android ses çıkışı hatası: $count")
                break
            }
            offset += count
        }
    }

    @Synchronized
    fun onStop() {
        audioTrack?.let { output ->
            if (output.state == AudioTrack.STATE_INITIALIZED) {
                output.pause()
                output.flush()
            }
        }
    }

    @Synchronized
    fun release() {
        audioTrack?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        audioTrack = null
    }
}
