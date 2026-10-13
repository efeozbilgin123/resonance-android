# Resonance Android v0.7.1 — unified music search (beta)

Resonance is a Kotlin/Jetpack Compose Android music player with a Rust
librespot Premium engine, Media3/ExoPlayer playback and optional Spotify Web API
metadata/playlist support.

## One search, multiple independent sources

The **Ara** tab is now the main screen. Start typing an artist or song title:
Spotify (if logged in), YouTube Music-style music results and Audius appear in
**one relevance-ranked list**. Each result has a **source label**; similarly
named tracks from different services are **not silently merged**. Search results
are progressive; one slow/offline provider should not hide the others.

The search ranks name and performer matches, boosts exact song titles and
demotes live/remix/cover versions unless requested. This is a heuristic;
incorrect or missing matches remain possible.

### Audio playback

- **YouTube Music/YouTube**: Spotube-inspired **unofficial** local NewPipe
  Extractor v0.26.5 searches YouTube Music's Songs and Music Videos categories
  directly and resolves open audio sources on demand. If local extraction fails,
  Resonance falls back to Piped (`/search?filter=music_songs`, `/streams/:id`)
  or Invidious
  (`/api/v1/search`, `/api/v1/videos/:id`) metadata + audio source.
  Resonance resolves an HTTPS audio URL on demand and streams through
  **Android Media3/ExoPlayer**, without a video view, browser or MP3 upload.
  It tries configured servers and can retry a different server on playback
  errors. Results can include regular YouTube videos when using an
  Invidious fallback.
- **Audius**: Official public creator-uploaded catalog and native audio stream.
- **Spotify Premium**: Optional native Rust **librespot** decoder with a JNI
  PCM sink into Android AudioTrack. A second official Web Playback SDK option
  remains as an experimental fallback. This is **not** a finished, tested
  Spotify Connect replacement.
- **Spotify Free**: Spotify Web API metadata can be shown when permitted;
  Spotify audio is **never** streamed from Spotify for Free users.
  The user can instead choose a separately identified available YouTube
  or Audius recording.

The player uses Android Media3 for YouTube/Audius, including native Android
audio output, background playback, Bluetooth/notification media controls and
automatic queue progression. YouTube source URLs expire; only stable video IDs
are kept in local playlists and URLs are re-resolved for playback.

**YouTube is not officially supported for this third-party audio-only use.**
NewPipe Extractor is distributed under GPL-3.0-or-later; its upstream source is
https://github.com/TeamNewPipe/NewPipeExtractor and Resonance's GPL license
applies to this integration.
There is no official YouTube Music public raw-audio streaming API for such a
client. Piped/Invidious services can go offline, return 403 responses, serve
incorrect results, or fail in some countries. Audio-only/background playback
may violate YouTube terms. Resonance does not claim YouTube authorization or
guaranteed availability. It does not bypass DRM, logins or memberships.
The gear icon in the search screen permits choosing a custom HTTPS API server.

## Playlists

Use **+** next to any search result:
- A real **Spotify track** is written to an editable Spotify playlist via the
  official Spotify Web API, so it appears in the Spotify app too.
- A **YouTube or Audius track** is attached to the same Spotify-linked list
  **locally in Resonance**. It will **not** appear in Spotify or be uploaded
  to Spotify's server, since the official Spotify playlist API cannot accept
  arbitrary external audio. It will stream again when played.
- JSON export/import saves the local sidecar playlist entries for a specific
  Spotify account. Local Android file URIs aren't portable between devices.

Spotify OAuth uses a **user-created Client ID** and the redirect
`http://127.0.0.1:43892/callback`. Developer Mode access/allowlists,
permissions and quotas still apply.

## Build and limitations

The GitHub Actions `Build Resonance Android APK` workflow compiles the
Android ARM64 Rust JNI shared library, runs Rust and Android unit tests,
and packages a debug APK.

To build locally use an Android SDK, NDK 27.2.12479018, Java 17, Rust with
`aarch64-linux-android` and Gradle. The resulting `app-debug.apk` is a
debug-signed application, not a Play Store release.

The following require real-device testing: Spotify Premium playback/login,
live YouTube/Piped/Invidious streams and background networking. A successful
build does not prove that a specific audio provider currently works.

Spotify's developer platform rules restrict unauthorized clone clients and
integrations with third-party music services; you may need Spotify's
permission for broader distribution. Respect each source's licensing and
terms. The original YouTube video embed/player and manual import UI from
v0.6 were intentionally removed in v0.7.
