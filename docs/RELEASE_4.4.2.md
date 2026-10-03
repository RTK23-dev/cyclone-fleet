# Cyclone Mobile 4.4.2 — native camera streaming

Cyclone Mobile **4.4.2 / versionCode 102** is built directly on the published 4.4.1 execution and visual foundation. It adds the receiving side of Cyclone One 1.5.5 native-aspect camera streaming without replacing the 4.4 task, overlay, workspace, recovery, consent, or visual-hierarchy implementation with older Mobile code.

## Camera streaming

- Dedicated transient full-screen camera viewer for Cyclone One streams.
- Preserves the source camera frame dimensions and aspect ratio end to end. A 4:3 or 3:4 source is contained on the receiving display; it is not stretched or cropped into 16:9 or 9:16.
- Hardware H.264 decoding through Android MediaCodec.
- Keyframe/config-aware low-latency buffering to recover cleanly after queue pressure or codec changes.
- Decoder failures surface explicitly instead of leaving a silently black connected screen.
- Rotation/output-size changes recalculate contain-fit presentation from authoritative stream dimensions.

## Security

- Camera traffic is reachable only through Cyclone One's per-phone ADB reverse loopback tunnel.
- Per-viewer ephemeral credentials never appear in the WebSocket URL.
- Cyclone One passes viewer identity/token as Android intent extras; Mobile sends them only as `X-Cyclone-Viewer-Target` and `X-Cyclone-Viewer-Token` WebSocket headers.
- The viewer rejects URLs with query strings, fragments, user-info, a non-loopback host, or the wrong reverse port.
- Product guards and Gateway tests enforce the header-auth and secret-free URL contract.

## Compatibility

- Receiving phones must run Cyclone Mobile 4.4.2 or newer.
- The source phone camera requires Android 12 or newer because capture uses scrcpy 4.0 Camera2 support.
- Recommended companion: Cyclone One 1.5.5.

## Provenance and validation

- Mobile base: published 4.4.1 SHA `a5d6d5ccc155ea30de8cf2684a155967ba69ed4a`.
- Cyclone One base integrated into the combined candidate: 1.5.4 VMOS / Cloud AI SHA `18d43fef0ae31db8376d1490738322125f654d53`.
- Camera work is forward-ported from PR #105 into the 4.4.1 Mobile tree rather than merging the older 4.3.x Mobile tree wholesale.
- Publication is authorized only from the exact-source CI candidate after tests, lint, release assembly, repository guards, checksum/provenance checks, and signer continuity with 4.4.1 succeed.

Physical multi-phone camera acceptance remains **UNVERIFIED** until tested on real devices. This release does not claim hardware acceptance that has not occurred.
