# Cyclone Mobile 4.7.8 — Human Gesture completion

Android versionCode 138, based on published 4.7.7 (`ab04025f`). Sealed on `release/cyclone-mobile-v4.7.8`.

4.7.8 keeps the 4.7.7 unified glass conversation panel and the 4.7.6 Human Gesture V0.3 planner. It does not rewind GATE, MutationGrounding, Fast Path Unchanged, `nodeAtTaskPath`, or the 4.7.5 keyboard-aware Ask bar. It does not change cubic profiles, Page Agent tools, or Instagram `humanize=off`.

This cut makes already-authorized host taps and standard navigation reliable:

- Coordinate Human Gesture waits for Android `GestureResultCallback` before returning. Queued is not completed. Cancelled and not-queued fail; a timeout after a successful queue is accepted so Fast Path will not fire a second click.
- Cyclone overlay windows set `FLAG_NOT_TOUCHABLE` only for that stroke, then restore. Overlay buttons still never click host nodes. Stop remains usable around the gesture.
- Fast Path still settles 300ms after the action returns, then +500/+1000. Unchanged is not a second click. Semantic `ACTION_CLICK` / `ACTION_SELECT` / `ACTION_LONG_CLICK` / `ACTION_SCROLL_*` still run first. `phone.back` and `phone.home` remain `GLOBAL_ACTION`.

## Pre-release review

Engineering review of this reliability cut is **GO for publication**. Overlay IME, task-progress, live-notification, and glass-panel sources are identical to 4.7.7 except the host-gesture passthrough flag. GATE still decides before any Human Gesture dispatch. Invalid `humanize` still fails closed. Duplicate suppression is unchanged.

Physical Pixel 8 (`3B171FDJH0061G`) Human Gesture smoke remains **UNVERIFIED**. CI does not substitute for an on-device check.

## Verification

The release requires repository guards, Android unit tests, lint and release assembly in exact-source Mobile CI. The publisher checks artifact provenance and signing continuity with 4.7.7. Source-order guards keep GATE, MutationGrounding, semantic actions, gesture-completion wait, and overlay passthrough ahead of Fast Path settle.
