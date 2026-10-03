# Cyclone 3.9.11 — Live Context + Composer Polish

Built on published v3.9.10 (`553688ca8d1180adb4f8cbc3b19764923404612a`). Android versionCode 65; optional PC components remain 3.8.4.

## Changes

The floating composer keeps attachment and model selection inside its existing Compose window. Plus opens File, Photo and Share screen above the bar; Tune opens the existing model preference, with advanced settings explicitly available. The draft survives Android pickers; editor focus is restored when the external window returns. An attachment chip shows when a reference is queued.

Screen sharing has a separate observable lifecycle: permission, starting, live, stopping, error and revocation. Live requires an accepted frame. Stop is available in the composer, a compact status pill while minimized, and the Android notification. Missing initial frames fail clearly; unchanged screens wait for a new buffer without making old frames fresh; resizing clears old frames before accepting new dimensions. The assistant composer and confirmation windows are secure to exclude their contents from capture.

Frames are sampled before expensive bitmap copies: about 3 fps normally, 2 fps on stable screens and up to 10 fps briefly after a foreground action. Freshness limits remain unchanged. Original capture resolution is retained for small text; this is a sampled screenshot stream, not continuous provider video.

Ordinary Share screen uses Android's app-or-screen choice. Its frames are read-only model reference context with a separate non-executable session; they never substitute for the chosen execution session's control evidence. Tune → Share for cross-app control explains and requests whole-display consent, feeding the existing foreground screenshot path. Background Shizuku workspaces keep their own frame/input routing; this release does not claim to finish the wider background-control roadmap.

## Validation and limits

Repository guards and 40 Python CI tests passed locally. New JVM regressions cover lifecycle generations, service cleanup, stop/retry races, resize state, sampling and attachments. Local Android tests could not start because Gradle distribution access is unavailable; exact-source Mobile CI must pass unit tests, lint and assembly before publication.

Physical-device acceptance: NOT RUN. Pixel keyboard/IME behavior, app-choice capture, secure-region rendering, rotation, picker/camera return, system revoke/lock and a 15-minute memory/thermal run remain unverified. The in-tree accessory changes do not assert a measured sub-100ms opening time. Cross-device/background observe-act-verify acceptance remains outstanding.

The existing full-release publisher now derives version identity from validated release metadata and reuses the exact successful Mobile CI artifact. It preserves the protected release environment, immutable tags and signer continuity checks. No published 3.9.10 artifact is modified.
