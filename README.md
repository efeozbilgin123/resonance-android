# Resonance Android v0.6 (beta)

Independent Android audio player in Kotlin / Jetpack Compose, Media3, and Rust JNI.

> **Not a full Spotify replacement.** Spotify does not expose arbitrary master
> audio streams to independent Android apps. Spotify Free cannot be made to
> stream Spotify's full catalogue through the Web API. The official Spotify
> Web Playback SDK requires Premium and compatible DRM-enabled browser support.
> Third-party clients that mimic Spotify and combine its content with another
> service can violate the Spotify Developer Policy.

## Supported functionality

- **YouTube/YouTube Music links:** Paste video links or share videos via Android's\n  Share -> Resonance menu. Resolves public video titles via YouTube oEmbed,\n  persists a local YouTube library, and attaches links as *Resonance-only*\n  entries to Spotify-linked playlists. Playback uses YouTube's official\n  embeddable video player within WebView (visible video + original controls),\n  **not extracted audio**, and is not guaranteed to continue in the background.\n- **Independent music:** Browse, search, and play on-demand tracks from
  [Audius](https://docs.audius.co/api/), with artwork and favorites.
- **Radio:** Browse free live radio stations via Radio Browser, with favorites.
- **Local files:** Import permitted audio using the Android storage picker.
- **Spotify account (optional):** PKCE OAuth using your own Client ID;
  read your playlists, browse Spotify songs, create Spotify playlists,
  **add Spotify catalogue tracks** to editable Spotify playlists. Those
  Spotify catalogue additions **are persisted to your Spotify account**.
- **Linked external extras:** Attach YouTube, Audius or local songs to a Spotify playlist
  in a **separate local section** of Resonance. These entries **are not
  stored on Spotify servers** and **will not appear in the Spotify client**.
  Every entry is keyed to a specific Spotify account and playlist ID.
- **Spotube-inspired discovery:** On demand, display possible Audius versions
  for a Spotify song using title, performer, duration. Candidate matching is
  heuristic, may be wrong, never auto-plays, and the user chooses the external
  audio source. Spotify audio is never extracted by this mechanism.
- **Portable sidecar backups:** Export/import the on-device external playlist
  extras in JSON. Backup contains playlist metadata, not Spotify credentials.
  Local file URIs are **not portable** to another phone.
- **Spotify Premium audio (experimental, ARM64):** Rust **librespot-core 0.8.0**\n  and **librespot-playback 0.8.0** plus a JNI sink stream PCM through Android\n  AudioTrack at 44.1 kHz stereo, 16-bit. The player authenticates with an\n  OAuth token issued to the user's connected Spotify Developer app. Native\n  playback is the default Premium tab and DOES NOT require installing Spotify.\n  Unlike Spotifast on desktop, this Android implementation is not yet a\n  Spotify Connect receiver, and media-session/background/headset controls for\n  native Spotify playback need separate integration. A Premium account is\n  required; runtime login and decoding have **not been tested on a real account**.\n- **Spotify Web SDK fallback (experimental):** Embedded official Web Playback SDK
  served from a trusted local HTTPS WebViewAssetLoader origin. This *might not
  work* on Android WebView if EME DRM is unavailable. There is a button to
  open the **official Spotify Web Player in a supported Android browser**.
  No Spotify app is needed for the browser route. The older Spotify Connect
  control is also provided but requires an active Spotify playback device.
- **Android Media3:** Independent audio with background MediaSessionService,
  headset/lockscreen controls, and Rust queue-policy library.

## Spotify authorization

Create your app in [Spotify Developer Dashboard](https://developer.spotify.com/dashboard).
Select Web API and register the *exact* redirect URI:

`http://127.0.0.1:43892/callback`

Enter the Client ID in Resonance > Spotify. New playlist modification and Web
Playback SDK features need additional OAuth scopes:
`playlist-modify-public`, `playlist-modify-private`, `streaming`.
**Use Spotify izinlerini yenile / hesap değiştir** to sign in again and
authorize any newly requested scopes. Developer Mode permissions, approved
users, subscriptions, markets and API access restrictions still apply.

## Important Spotify limitation

Spotify's official API accepts Spotify track/episode URIs for playlist insertion
and **cannot add local or Audius files as real Spotify playlist items**.
Spotify Desktop can add local files manually in its own UI, but this is not
supported as a playlist-insertion operation by the Web API.
Resonance NEVER marks locally attached audio as synced to Spotify.

Spotify also prohibits unauthorized Spotify core-app clones and integration
with other services under its [Developer Policy](https://developer.spotify.com/policy).
Permission from Spotify may be necessary for broader deployment of the
metadata matching and multi-source use cases.

## Build an APK

CI compiles Rust JNI shared libraries for arm64-v8a, armeabi-v7a and x86_64,
runs Rust tests and Kotlin unit tests, then builds `app-debug.apk`.

Check [GitHub Actions](../../actions) and select a successful
**Build Resonance Android APK**
run. Download its artifact ZIP and extract `app-debug.apk`.

Debug builds are not production-signed Google Play releases. Compilation success
does not establish real-device audio/DRM/OAuth success.

## Device test steps for v0.6

1. Install the ARM64 debug APK. If an old debug APK is signed with a different
   key, back up any local Resonance favorites before uninstalling it.
2. In the YouTube tab paste `https://youtu.be/dQw4w9WgXcQ`, save it and open
   the video. On YouTube, video availability and embedded-playback restrictions
   may vary by account and location.
3. Connect a Spotify account via your registered Client ID and redirect URI.
   If upgrading from earlier versions, reconnect to grant the `streaming` scope.
4. Open a Spotify playlist; choose **YouTube / YouTube Music bağlantısı ekle**,
   paste a link and confirm. The YouTube entry appears only in Resonance's
   local extras section, not in the Spotify app.
5. On a Premium account select a real Spotify catalog track, choose **Premium
   çal**, then **Rust · librespot**, connect and play. Observe any login/decoder
   error. This feature remains experimental until tested with real Premium credentials.
6. If Rust login/playback fails, use the **Web SDK yedek** tab. The fallback
   also needs Premium and might fail on Android WebView DRM.

The APK contains native ARM64 code and is not currently packaged for 32-bit
Android or x86 Android emulators. The SDK/YouTube/Spotify network services and
third-party policies can change. Spotify's Developer Policy limits Spotify
clones and integration with other content services, and publishing this app
may require Spotify's permission.
