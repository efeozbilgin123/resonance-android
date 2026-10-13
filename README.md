# Resonance Android v0.5 (beta)

Independent Android audio player in Kotlin / Jetpack Compose, Media3, and Rust JNI.

> **Not a full Spotify replacement.** Spotify does not expose arbitrary master
> audio streams to independent Android apps. Spotify Free cannot be made to
> stream Spotify's full catalogue through the Web API. The official Spotify
> Web Playback SDK requires Premium and compatible DRM-enabled browser support.
> Third-party clients that mimic Spotify and combine its content with another
> service can violate the Spotify Developer Policy.

## Supported functionality

- **Independent music:** Browse, search, and play on-demand tracks from
  [Audius](https://docs.audius.co/api/), with artwork and favorites.
- **Radio:** Browse free live radio stations via Radio Browser, with favorites.
- **Local files:** Import permitted audio using the Android storage picker.
- **Spotify account (optional):** PKCE OAuth using your own Client ID;
  read your playlists, browse Spotify songs, create Spotify playlists,
  **add Spotify catalogue tracks** to editable Spotify playlists. Those
  Spotify catalogue additions **are persisted to your Spotify account**.
- **Linked external extras:** Attach Audius or local songs to a Spotify playlist
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
- **Spotify Premium audio (experimental):** Embedded official Web Playback SDK
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
**Build Resonance Android APK** or **Build Hybrid Resonance Android v0.5**
run. Download its artifact ZIP and extract `app-debug.apk`.

Debug builds are not production-signed Google Play releases. Compilation success
does not establish real-device audio/DRM/OAuth success.
