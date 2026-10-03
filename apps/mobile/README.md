# Cyclone Mobile

Cyclone Mobile is the Android app at the center of Cyclone. It combines an on-device agent runtime with Android Accessibility tooling, verification/recovery, learned routines, Brain diagnostics and the persistent Aurora entry point.

## Current baseline

- Source sprint: Cyclone Mobile 4.2.0 / versionCode 81 (publication disabled; published base 4.1.0)
- Package: `com.cyclone.mobile`
- Launcher: `.MainActivity`
- Minimum Android: 13 / API 33 (isolated background displays: Android 15+)
- Compile/target SDK: 35
- UI: Home, Profiles, AI / Ask Cyclone, Routines, Brain and Settings
- Ask Cyclone: chat-style task composer with model selection
- Brain: recent run history with sanitized downloadable diagnostics
- Aurora: bottom-center compact activation overlay

## Build

Requirements: JDK 17 and Android SDK 35.

```bash
cd apps/mobile
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Release artifacts are produced by GitHub Actions so the source SHA, metadata and checksum stay tied together.

## Architecture

See [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md). Repository-wide development rules live in [`../../AGENTS.md`](../../AGENTS.md).
