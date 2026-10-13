# Resonance Android v0.7 (beta)

A native Android player written in Kotlin/Jetpack Compose, Android Media3 and Rust JNI.

## What v0.7 changes

**One search, two catalogs.** The **Ara** tab now performs parallel searches of:

- **Spotify Web API** (when the user connects their own Spotify Developer OAuth app): artist, title, album art, durations and Spotify catalog IDs.
- **YouTube Music Songs** through NewPipe Extractor's unofficial YouTube Music search filter; its Music Videos/general YouTube filters are fallbacks. Result parsing includes performers, durations and artwork.

Both appear in **one relevance-ranked results list**, retaining source labels
and the ability to filter Spotify vs YouTube. Ranking considers title/artist
tokens, position, music-recording confidence and unwanted cover/live labels.

**Audio without an embedded video:** YouTube Music selections are represented
by stable `resonance://youtube/{videoId}` queue items. On playback Media3 asks
NewPipe Extractor v0.26.5 to resolve the public video's audio-only source, then
streams audio directly through ExoPlayer. The code never asks the user to import
an MP3 or open a YouTube WebView. Android's MediaSessionService provides
background playback and previous/next/lock-screen support for these streams.

**Spotify Free / Premium choices:** On a Spotify Premium account, selecting a
Spotify catalog song opens the experimental Rust/librespot engine. For Spotify
Free, selecting a Spotify catalog result can play its closest matched YouTube
Music recording when title, performer and duration agree confidently. An
ambiguous match opens a candidate picker, **not** an automatic wrong cover.
The YouTube recording is not Spotify audio. Spotify's own account/playlist
API is still used for real Spotify track changes.

**Mixed playlists:** YouTube Music tracks can be saved as Resonance-only extras
next to Spotify playlist tracks. These extras are stored locally under Spotify
account ID + playlist ID and can be exported/imported as JSON. Spotify API
**does not** upload or store YouTube/Audius/local tracks on its own servers.
Spotify catalog tracks added to the list do sync through the official API.

Other v0.6 features remain: Audius discovery and streaming, live radio, local
MP3/FLAC library, and the experimental native Spotify Premium player with
Spotify Web Playback SDK fallback.

## Spotify sign-in

Create your own app at https://developer.spotify.com/dashboard and register
the exact redirect URI: `http://127.0.0.1:43892/callback`

Enter its Client ID in Resonance's **Spotify hesabı** screen. Login uses PKCE,
never a client secret. Spotify's changing Developer Mode, account, scope and
Premium rules apply. YouTube Music search does not require Spotify sign-in.

## Important limitations and terms

- **Unofficial source:** NewPipe Extractor and audio-only playback are not
  supported by YouTube's official streaming APIs; they may break as YouTube
  changes its website or block streams by country, rights or account.
  YouTube's service terms restrict such uses. This implementation does not
  remove DRM or bypass content protections.
- **Spotify policy:** Spotify restricts imitative full-client apps and the
  combination of its metadata with other services. Distribution may require
  Spotify approval. This prototype is not an authorized Spotify client.
- **Native Premium audio is experimental:** Rust/librespot compiles to an ARM64
  Android library and sends decoded PCM to AudioTrack. A Spotify Premium
  account is needed and actual account/phone playback has **not** been verified.
  Its controls do not yet fully integrate Android's MediaSession. The old
  official Web Playback SDK remains an experimental fallback.
- **Search matching is heuristic:** A YouTube Music upload with the same title
  is not necessarily the same master recording as a Spotify track. Bad matches
  can be rejected by the user.
- **Background playback:** Android Media3 supports it for YouTube direct audio;
  YouTube network/format changes can still cause playback failures. YouTube
  source URLs expire and are freshly resolved per media-open. No download
  or offline caching is implemented.
- **GPL licensing:** NewPipe Extractor is GPL-3.0-or-later. See the repository
  LICENSE and the source at https://github.com/TeamNewPipe/NewPipeExtractor.

## Build and install

The [GitHub Actions workflow](../../actions/workflows/build-android.yml)
builds the Rust ARM64 JNI library, tests Rust and Kotlin, and packages an
Android debug APK. Download the `Resonance-Android-v0.7` artifact ZIP and
extract `app-debug.apk`. Currently Android **ARM64** only.

An APK successfully built in CI is *not* proof of live YouTube Music or Spotify
streaming on a specific device: actual network and audio behavior needs testing.

## Quick device test

1. Install the APK. In **Ara**, search `Daft Punk One More Time`. Expect
   Spotify entries if logged in and YouTube Music song entries regardless.
2. Tap a **YouTube Music** recording. It should play as audio inside Resonance
   without a video WebView. Test backgrounding and previous/next.
3. Use the playlist-plus icon on a YouTube result and select a Spotify-linked
   playlist. It should appear in **Resonance ekleri**, not Spotify's own app.
4. On Spotify Premium, select a Spotify result and choose the Rust player.
   Observe authentication and playback status. On Free, select a Spotify
   result and review the selected YouTube Music match if confidence is low.
5. Test both Wi-Fi and mobile data. If a video is geographically restricted,
   private, DRM-protected or blocked, playback can fail; choose another result.
6. Debug APK signatures can differ between builds. Back up local favorites and
   Resonance playlist extras before uninstalling an older release.

