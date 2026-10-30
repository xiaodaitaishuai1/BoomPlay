# BoomPlay

Native Android music browser and player using the Boomplay API and Audiory B-style dark UI.

## Build

Use JDK 21 and an Android SDK containing API 36. Set `JAVA_HOME` to the JDK and either set `ANDROID_HOME` or create an untracked `local.properties` containing `sdk.dir`.

Debug and Release share the independent `boomplay.jks` signing key. Local credentials live in `app-config.gradle`; both files are ignored by Git. On another machine, restore these two files from your backup, or copy `app-config.gradle.example` and configure your own keystore. Keep the same key to publish updates to an existing installation.

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest lintDebug
.\gradlew.bat connectedDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Features

- Home, collection browsing, artist/album/playlist details and paged song search.
- Media3 background audio, queue, seek, shuffle, repeat and system media controls.
- Shared mini player and B-style full player with always-visible controls.
- Room favorites, recent playback, custom playlists and search history.
- Playback queue and position restore paused after restart.
- Settings → Preferred region: Mainland China (CN, default), Hong Kong (HK), Taiwan (TW). The selected value is persisted and sent to Home/collection detail; the service may still choose content by IP/session.

`LiveMusicFlowTest` deliberately uses the production backend and CDN; it fails if either is unreachable. Other tests use local fixtures or an isolated database. Live tests save page screenshots under the device's Download folder with `boomplay-` prefixes.

API and audio requests use the documented User-Agent. The app does not bypass regional or account restrictions. It has no login, ads, telemetry, downloads, video or lyrics integration. API reference and local verification evidence remain in the untracked `docs/` folder.

The small vector control icons were adapted from the user-provided Audiory project; the app mark, repeat and chevron vectors are local geometry. Confirm asset rights before distributing a release.
