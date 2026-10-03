# Cyclone V5 Alpha 34 — Lab handoff and Glass mirror repairs

This developer-alpha candidate is based on Alpha 33. Its intended paired versions are Mobile `5.0.0-alpha.34.dev1` (code 176), Windows Companion `1.6.0-alpha.34`, and the bundled Glass web app `1.0.0-alpha.17`.

## Changes

- A finished or stopped Mind mission releases its temporary phone-control pause. An explicit human takeover still keeps human control until it is handed back. This addresses the `HUMAN_HAS_CONTROL` rejections that halted the Alpha 33 Lab canary after the first scored mission.
- Watching the Glass focus video no longer silently claims gateway input control.
- Glass sizes its live viewer from the actual decoded frame dimensions, so a landscape phone is no longer squeezed into a portrait-shaped black frame. Pointer mapping tests cover orientation changes.
- Multiword screen searches require more than one substantive match, reducing unrelated Settings results during Mind navigation.
- The Agent MCP release check now respects the declared scope of Lab tools: experiment listing, report, and stop do not require a phone ID.

## Acceptance status

The Alpha 33 Pixel 8 run that prompted these fixes did **not** pass: the auto-rotate mission failed its phone-side check, later missions were blocked by control ownership, and landscape Glass viewing was unusable. See [the physical report](ALPHA33_LAB_TEST_REPORT.md) and [the stability analysis](ALPHA33_STABILITY_DEEP_DIVE.md). Automated tests for the patched paths pass locally. A signed Alpha 34 APK, exact-source Windows installer, in-place update, eight-mission canary, portrait/landscape tap grid, and USB reconnect soak have **not** yet passed physical acceptance.

This remains a developer alpha. The phone and gateway still keep separate input-owner states; the durable single phone-acknowledged ownership protocol described in the stability analysis is future work. No claim of AnyDesk-level latency, pixel accuracy, universal device compatibility, or unattended reliability is supported by this candidate.

Install only a signed, provenance-checked paired build. An unsigned local APK cannot update the installed Alpha 33 app; do not uninstall the existing app to work around a signature mismatch.
