# Cyclone Mobile 4.4.6 — Observation runtime + visual repair

Mobile **4.4.6 / versionCode 106** combines the published 4.4.5 authoritative-observation runtime with the independently audited Liquid Glass and overlay repair work that was not part of the already-published 4.4.5 APK.

## Visual and interaction repair

- Restores a transparent, content-sized accessibility overlay so Cyclone no longer paints a full-screen white/blue wash behind the floating controls.
- Rebuilds the resting Ask Cyclone overlay as one 66dp Apple-style dark glass composer with a distinct voice/send/stop action, plus a separate tools panel rather than nested glass sheets.
- Keeps backdrop-dependent controls out of popup windows and gives transparent overlay windows safe Material fallbacks instead of crashing when no Kyant backdrop exists.
- Tightens Liquid Glass optics, selection lenses, bottom navigation, segmented controls, Home, Routines, Profiles, Quick Setup, routine detail and AI/model/intelligence surfaces.
- Keeps only one visual owner per interaction region and preserves the prior one-owner/background-task behavior.

## Observation runtime

- Preserves the 4.4.5 authoritative observation-generation work: shared semantic evidence, generation identity, stale-evidence rejection, visual escalation boundaries, capture-change recovery and current-target revalidation.
- Retains overlay exclusion, consent/approval boundaries, secret redaction and the existing Cyclone One 1.5.5 compatibility path.

## Release safety

- Minimum SDK 33, target SDK 35, compile SDK 36.
- Kyant0 Backdrop 1.0.0 + Capsule 2.1.1 remain the Liquid Glass renderer.
- Android SDK setup explicitly requests supported `platform-tools`; the release signer workflow uses the same fix.
- Publication is allowed only from the exact release SHA after Mobile CI tests, lint, release assembly, checksum/provenance verification and signer continuity against published v4.4.5 succeed.

Physical-device visual/interaction acceptance remains **UNVERIFIED** because no Android test device is connected. This release does not claim hardware acceptance that has not occurred.
