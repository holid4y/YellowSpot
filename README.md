# YellowSpot — Spotify GeckoView Android app

**Target:** Xiaomi POCO X7 Pro / HyperOS (ARM64). Native Android Java project; no Compose or UI frameworks.

## Features implemented

- Full GeckoView browser, mobile user agent/viewport, Spotify web player as home page.
- Cookies, site data, and login session persist in Gecko's regular app profile. HTML/JS Spotify login runs in Gecko. Popup windows can open in a secondary GeckoSession; Android back closes the popup.
- **Two-layer ad blocking (0.2.0)**:
  - **Real uBlock Origin**: on first launch Gecko downloads the Mozilla-signed extension from Mozilla Add-ons, checks `uBlock0@raymondhill.net`, and persists it. A tap on the shield icon displays its status or retries failed installation. There is no fake filter-list substitute.
  - **Bundled YellowSpot ad guard** (`app/src/main/assets/adguard/`): a built-in WebExtension content script that handles Spotify's *audio ads at the player level*. Network blocking alone wedges the Spotify web player — the ad slot starts, its blocked requests never complete, and playback freezes at 0:00 until the page is reloaded by hand (a long-standing uBlock-Origin-on-Spotify failure mode). The ad guard instead lets the ad session run but silences the ad audio, fast-forwards it to its end (or cuts its dead source when it was already blocked), clicks any Skip button, restores audio when the ad is over, and nudges/resumes playback automatically. A watchdog detects wedged ad slots and stalled streams and, as a last resort, reloads the page itself (at most once per 3 minutes) — the same workaround a user would perform manually, automated and rate-limited. All interventions are strictly capped per ad session so the guard can never wedge the player itself.
- Yellow circle / dark three-curves app icon (Spotify-inspired custom recoloring).
- Android `MediaSession` / `PlaybackState` + `Notification.MediaStyle` with track, artist, art, seek, play/pause, previous and next, Bluetooth/media keys, HyperOS control center/lock-screen card (system appearance depends on HyperOS).
- `mediaPlayback` foreground service and audio focus handling during playback.
- 4x1 home-screen media-control widget with cover art and transport buttons.
- Firefox fallback launch button for playback/login situations blocked by custom embedder compatibility.
- API 26+, target API 35. ABI restricted to **arm64-v8a** to cut the engine footprint (POCO X7 Pro is ARM64).

## How the ad layers divide the work

| Layer | Blocks | Why |
| --- | --- | --- |
| uBlock Origin | Trackers, ad banners, third-party ad requests in general | General-purpose, auto-updated filter lists |
| YellowSpot ad guard | Spotify free-tier audio ad breaks (the "song ends → ad starts → stuck at 0:00" case) | Player-level handling; network-blocking ad requests is exactly what freezes the web player |

The ad guard detects ads through locale-independent markers first (`data-testadtype` on the player bar, `context-item-info-ad-subtitle`, `/ad` context links). Localized aria-label text ("Advertisement", "Propaganda", …) is only a *weak* signal: it mutes but never seeks, so a real song whose title happens to contain an ad word can never be skipped. Skip-button matching requires both a skip word and an ad word (or a `skip` testid), so transport buttons like "Skip to next" are never clicked.

## Community-inspired features in 0.3.0

Surveyed the open Spotify-userscript/extension community and adopted the ideas that are robust, client-side only, and don't fight the ad guard or the media integration:

- **AMOLED pure-black theme** — the most-requested community creation for OLED phones (from the Spotifuck → SpotiwebJS / SpotiVibe theme lineage). Forces Spotify's own background CSS variables to true `#000000`, so it survives DOM redesigns and saves battery on the POCO X7 Pro's AMOLED panel. Album-art tinting of hero areas is left intact.
- **Premium-nag removal** — "Explore Premium" / upgrade links hidden by `href` (locale-independent; Button-Remover / SpotiVibe idea) and the useless "Install app" header button hidden via a scoped, display-only pass (`uiclean.js`) that never touches content buttons.
- Considered but **not** included (need external APIs, tokens, or risk playback integrity): multi-provider lyrics popups (Spotify Web Lyrics+/Genius scripts), play-count/bitrate indicators, crossfade, friend activity. These belong in a follow-up behind user settings.

## Fixes in 0.2.0

- Ad breaks no longer freeze the player between songs (bundled ad guard, above).
- Gecko content-process crash/kill (`onCrash`/`onKill`) now recreates the session and reloads Spotify instead of leaving a dead black screen until the app is force-closed.
- Notification/widget could show a *previous* track's artwork if its bitmap fetch resolved late; artwork updates are now generation-guarded.
- Fixed a service race where pausing exactly as the service was winding down could stop the media notification from ever coming back.
- The shield retry button can no longer fire overlapping uBlock Origin installs.
- Widget commands are validated against a whitelist; play/pause icon always redraws on state change instead of being throttled.
- Stale web `MediaSession` access after a crash no longer crashes transport controls (notification/widget buttons).

## Important limitations

1. **This is not the official Spotify client.** Spotify lists Firefox/Chrome/Samsung Internet on Android as supported, **not arbitrary GeckoView embedding applications**. Protected playback (Widevine/EME), OAuth providers, audio, or the Media Session API may be rejected or impaired. Android integration cannot make unsupported protected audio play. Test on the POCO device.
2. UI metadata, album art, and transport controls only appear when the site exposes a working web `MediaSession` and audio actually starts. The Android service doesn't pretend music is playing.
3. uBlock Origin may block network requests; if Spotify has trouble, consider adjusting its rules. No Spotify service, DRM, or server-side ads are bypassed by the native application. Add-on requires internet at initial install. Host-read ads baked into podcast audio cannot be blocked by anything.
4. Spotify periodically changes its DOM markers; if ad handling degrades, the selectors live at the top of `adguard.js`.
5. This doesn't connect to proprietary Xiaomi HyperIsland APIs; it uses the supported Android media APIs. HyperOS may display a different media widget than official Spotify.
6. GeckoView is a complete browser engine (~86 MB AAR download before APK packaging); lighter than a general-purpose browser app in UI, but **not** comparable in size to a tiny system WebView wrapper. It is needed to support Firefox WebExtensions.
7. Widget transport controls require the browser process / active session. After the app process is killed, tapping one opens the app; it cannot restore audio playback without a fresh webpage session.
8. The Android home screen widget isn't Spotify's proprietary widget; it is a small local media widget with standard app commands.

## Build an installable APK in Android Studio

1. Open this folder as a Gradle project in a current Android Studio (AGP 9.4.x support, JDK 17).
2. **SDK requirement:** GeckoView 157's AAR metadata requires **compileSdk 37.1** (`platforms;android-37.1`, `build-tools;37.0.0`). The project pins AGP 9.4.1 + Gradle 9.8.1 for exactly this chain; older AGP cannot build this GeckoView (its AAR check rejects anything below 37.1).
3. Allow Gradle to sync and download dependencies from Google, Maven Central and Mozilla (GeckoView is a ~90 MB AAR).
4. Select **Build > Build APK(s)**. Find `app/build/outputs/apk/debug/app-debug.apk`.
5. Transfer the APK to the POCO X7 Pro and install it, allowing trusted-app installation as prompted.
6. Launch with internet connected and tap the ◈ shield icon to check the add-on status. Log into Spotify normally.

Alternatively, this repository's **Actions > Build YellowSpot APK** workflow downloads dependencies and publishes `YellowSpot-POCO-X7-Pro-APK` under the workflow run's Artifacts area on every push to main.

### HyperOS permissions

In Settings > Apps > YellowSpot: allow notifications; permit background activity / set Battery saver to *No restrictions* if HyperOS suspends the browser when screen-off. The exact menu names differ between HyperOS revisions. The media card is generated by Android after playback begins.

## Files

- `YellowSpotApp.java`: Gecko engine, real extension installation, ad-guard/uBlock status, web MediaSession observer, crash recovery.
- `MainActivity.java`: compact fullscreen host, tab-style OAuth popup handling, retry/reload/Firefox fallback.
- `PlaybackService.java`: Android native MediaSession, media notification, audio focus.
- `MediaWidget.java`: 4×1 widget transport + metadata.
- `app/src/main/assets/mobile`: native bundled mobile CSS content script.
- `app/src/main/assets/adguard`: bundled Spotify ad-guard WebExtension (ad-audio content script, AMOLED + anti-nag cosmetic CSS, header-clean script `uiclean.js`).
- `.github/workflows/build-apk.yml`: creates downloadable debug APK in CI.

## Verification status

- The Java sources compile type-clean against Android SDK Platform 35 and GeckoView 157.0.20261005135250 (the exact dependency pinned in `app/build.gradle`).
- The ad-guard state machine passes a behavioral test suite covering normal playback, strong/weak ad signals, blocked-ads-stuck-at-0:00 recovery, give-up convergence, skip-button matching, and the content-stall watchdog.
- The cosmetic `uiclean.js` pass passes its behavioral suite (header-scoped install-button hiding in pt-BR/en, no false positives on Home/profile/content/oversized labels, idempotent injection).
- **The APK builds end to end**: `assembleDebug` on AGP 9.4.1 / Gradle 9.8.1 / JDK 17 / platform 37.1 produces a signed ~190 MB arm64-only `app-debug.apk` (versionCode 3, `0.3.0`) containing the full GeckoView engine, both bundled extensions and all four ad-guard files.
- Still to be tested on physical Xiaomi hardware; test on the device.

> Blocking ads on Spotify's free tier is a local, client-side convenience layer; Spotify's own terms of service apply. Nothing here touches Spotify's servers, DRM, or account data.
