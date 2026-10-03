# Cyclone Mobile 4.7.6 — Human Gesture V0.3

Android versionCode 136, based on published 4.7.5 (`ed46e176`). Sealed on `release/cyclone-mobile-v4.7.6`.

4.7.6 keeps the 4.7.5 keyboard-aware Ask bar, automatic task-progress opening, and unified live notifications. It adds Android-authoritative Human Gesture V0.3 as a surgical port of the frozen 4.2.0 engine onto this line. It does not merge `integration/human-gesture-v0.3` and does not rewind GATE, MutationGrounding, Fast Path Unchanged, or `nodeAtTaskPath`.

Callers may send `humanize=auto|off|light|normal` on `phone.click`, `phone.long_press`, `phone.swipe` and `phone.scroll`. Omission is AUTO. Unknown values fail closed before mutation. AUTO is LIGHT for taps and NORMAL for swipes. OFF keeps the legacy straight path. Semantic `ACTION_CLICK` / `ACTION_SELECT` / `ACTION_LONG_CLICK` / `ACTION_SCROLL_*` still run first; only a grounded coordinate fallback synthesizes in-bounds cubic motion. Named virtual displays stay endpoint+duration. The PC may not send Bezier controls, polylines, RNG or sampled trajectories. `PhoneToolExecutor` remains the sole mutation authority. Fast Path still settles 300ms then +500/+1000; Unchanged is not a second click.

Instagram stock `phone.swipe` is pinned `humanize=off` until Pixel smoke.

## Pre-release review

Engineering review of the 4.7.5 overlay plus this Human Gesture port is **GO for publication**. Overlay IME, task-progress, and live-notification sources are identical to 4.7.5. GATE still decides before any Human Gesture dispatch. Invalid `humanize` fails closed before cache lookup and mutation. Duplicate suppression ignores the humanize field so a profile change cannot become a second click. Workspace backends honestly report endpoint+duration rather than claiming cubic fidelity.

Physical Pixel 8 (`3B171FDJH0061G`) Human Gesture smoke remains **UNVERIFIED**. CI does not substitute for an on-device check.

## Verification

The release requires repository guards, Android unit tests, lint and release assembly in exact-source Mobile CI. The publisher checks artifact provenance and signing continuity with 4.7.5. Gateway, MCP and Human Gesture lab Python tests cover the transport boundary, fail-closed profiles, and evidence projection. Source-order guards keep GATE, MutationGrounding and semantic actions ahead of Human Gesture dispatch.
