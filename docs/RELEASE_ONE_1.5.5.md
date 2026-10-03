# Cyclone One 1.5.5 — native camera streaming

Cyclone One **1.5.5** builds on the complete 1.5.4 VMOS / Cloud AI baseline and adds first-class native-aspect phone-camera streaming to Cyclone Settings.

## Streaming camera

- One source Android phone can stream to up to five receiving Cyclone phones at once.
- The source is encoded once with Cyclone's pinned scrcpy 4.0 Camera2 path and fanned out, rather than running five encoders.
- `camera_ar=sensor` is the capture invariant. **High** preserves sensor aspect up to a 1920 long edge; **Native sensor** asks scrcpy for the greatest supported sensor-aspect size without Cyclone imposing a resolution cap.
- 30 fps and 60 fps choices with quality-appropriate H.264 bitrates.
- Source readiness is verified before receiving phones are opened.
- The camera session does not pause merely because the source phone display sleeps.
- Slow or incompatible receivers degrade individually rather than blocking the rest of the fleet.
- Lagging viewers are re-seeded from current stream metadata, codec configuration and the latest keyframe instead of accumulating latency or remaining black.

## Settings experience

The Streaming camera surface in Cyclone One Settings provides source selection, front/back camera, High/Native quality, frame rate, up to five viewer selections, one-tap start/stop, real source dimensions/aspect, and per-phone LIVE/ISSUE status. Controls remain stable during status polling and lock appropriately while a stream is active.

## Security

- The One Gateway remains loopback-only.
- Each receiver gets a per-target ephemeral viewer token and its own ADB reverse tunnel.
- Credentials do not enter the WebSocket URL or Uvicorn access-log path.
- Credentials are delivered to the viewer as Android intent extras and returned only as authenticated WebSocket headers.
- Camera receiving requires Cyclone Mobile 4.4.2 / versionCode 102 or newer.

## Combined baseline

The combined release candidate preserves:

- Cyclone Mobile 4.4.1's full execution foundation and visual-hierarchy upgrade, advanced to Mobile 4.4.2 for the camera receiver.
- Cyclone One 1.5.4's VMOS / Cloud AI hardening, real Gateway-minted sessions, trust verification, secret boundaries, and ChatGPT Attach behavior.

Windows release acceptance builds the NSIS installer, installs it into a clean directory, starts the bundled Gateway, validates authenticated transport onboarding, validates the camera-stream status API and five-viewer/source-aspect contract, and validates Cloud Control health.

Physical multi-phone streaming acceptance remains **UNVERIFIED** until tested on real hardware.
