# AI Daily Radar — Android

A lightweight Android wrapper (WebView) for **[AI Daily Radar](https://macapex.github.io/ai-daily-radar/)**.

<img src="icon/store-512.png" width="128" alt="icon">

## Download
Grab `AI-Daily-Radar.apk` from the [latest release](../../releases/latest) and install it (allow "install unknown apps" for your browser/file manager).

## Features
- Full-screen, edge-to-edge WebView with dark `#07080d` background & splash matching the site
- JavaScript, DOM storage and hardware-accelerated WebGL (for the 3D hero)
- Fresh content when online (`LOAD_DEFAULT` + `Cache-Control: no-cache` revalidation of the page), cached copy when offline
- Pull-to-refresh, and auto-refresh when you return to the app after more than 1 hour
- Back button walks WebView history
- Links outside `macapex.github.io` open in your browser
- Friendly offline page with a Retry button
- Adaptive launcher icon (with Android 13 themed/monochrome layer)
- Opens `https://macapex.github.io/ai-daily-radar/...` links directly

## Build
Requires JDK 17 and the Android SDK (platform 35, build-tools 35).

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
# Release signing: create keystore/keystore.properties with storeFile, storePassword, keyAlias, keyPassword
./gradlew assembleRelease
```

- package: `io.github.macapex.aidailyradar` · minSdk 24 · targetSdk 35
