# Cyclone V5 Alpha 43 dev2 — Glass inside the app

Developer alpha for owner testing, built on Alpha 43 (Glass overlay), which it includes. Mobile is
`5.0.0-alpha.43.dev2` (version code 186). Glass stays `1.0.0-alpha.26`. The Windows companion is not part of this
release: keep Cyclone PC Companion `1.6.0-alpha.43` installed.

This is the first release through the fast lane: only Android (and Glass, when it changes) is built and published.

## What changed

**The Cyclone app now wears the overlay's glass.** The Ask page and the home screen's current task use the same
tilt-lit glass as the overlay:
- **The task:** the one-word plane pill sits above the working card, with the app logos in the header. The card's
  collapse button folds it into the island; tap the island to open it again. When a task needs you, the owner card
  takes the card's place.
- **The Ask bar:** "Ask Cyclone" on a soft pill between **+** and the voice button. The voice button turns into the
  glowing orb while voice mode is open. The folded composer uses the same bar.
- **The on-screen task:** a task running on your screen shows the same glass card.
- **Buttons:** pause, stop and the owner's answers still go through Task Kit, exactly as on the overlay.

## Validation and limits

Tests that pass: Android unit tests (1 838), lint, the release build and the CI guards (119), including the product
guard for the new glass composer.

**Physical Pixel 8 acceptance is UNVERIFIED.** The in-app screens have not been seen on the phone yet.

Honest limits:
- **No drag inside the app:** the app folds the card with its collapse button; the drag-down gesture is overlay-only.
- **Everything from Alpha 43's limits still applies** (no fly-into-pill animation, Android-drawn notification buttons).

Install the APK as an update; do not uninstall. It is signed with the rotated Cyclone release key (the alpha.34 /
39 / 40 / 41 / 42 / 43 signer).

Suggested checks:
1. Open the Cyclone app and ask for something: the task card on the Ask page should look like the overlay's.
2. Tap the card's collapse button (island), then tap the island to open it.
3. Tap the microphone in the app's Ask bar: it should turn into the glowing orb.
