# Cyclone V5 Alpha 37 — Runs from the map

Developer alpha for owner testing, built on Alpha 36 (Learn), which it includes. Mobile is `5.0.0-alpha.37.dev1`
(version code 179), Windows companion is `1.6.0-alpha.37`, and the bundled Glass web app is `1.0.0-alpha.20`.

## What changed

**Cyclone now uses what it learned to get places faster.** In an app you have pressed **Learn** on, Cyclone:

- **gets the app's map** the first time it opens it: every screen it knows, with a short name (s1, s2…), and which
  button leads where from each one ("Settings: “Display” → s2, “Network & internet” → s3");
- **can walk there itself with `go_to`.** Instead of reading and deciding at every screen, Cyclone asks once ("go to
  s4 Screen timeout"). The phone then taps the known way, **checking the screen after every step**. Each tap is a
  normal Cyclone tap, so it still settles, still re-reads the screen and still stops for approval where needed. If
  anything is different (the app changed, a button moved), the walk stops right there and Cyclone continues by
  thinking, from the real screen.
- **keeps the map honest.** A route that worked gets stronger. A route that surprised Cyclone gets weaker, and after
  two surprises it is marked out of date and no longer used. Only safe buttons are ever walked: nothing that sends,
  pays, deletes or signs in.

**Measured, not assumed: the Lab A/B.** Cyclone Lab gets a **Run from the map** switch per arm (`useMap`), a
navigation-heavy **map** suite (11 missions: Settings pages, Clock, Calculator, Files, Play Store), and comparisons
that show **turns ×** and **time ×** next to success and cost. The target is at least 30% fewer turns and 30% less
time on these missions, with no drop in success. With the map on, the Lab also learns each mission as it ends, so the
map grows during the experiment. The exact protocol (a warm-up pass, then map-on vs map-off × 4) is Phase 3 in
`docs/LAB_AGENT_BRIEF.md`. Glass → Lab has the switch and shows the new numbers; agent MCP accepts `useMap`.

What is not in this alpha: auto-learn for your own everyday runs. It stays on the Learn button until the A/B shows the
map helps. Saved skills do not yet remember a preferred route. The reliable-typing work from plan 21 (the ChatGPT
reply-box failure) is next, in Alpha 38.

## Validation and limits

Android unit tests (route choice, safe-only doors, stale and flaky moves ignored, walking, stopping at the first
surprise, re-planning, feedback, the map card, `go_to` through the real toolbox, the Lab switch and map-move metrics),
lint, gateway (knob validation, map suite, turns ratio), agent MCP, Glass and companion tests, and the CI guards pass.
**The speed-up itself is not yet measured:** it needs the Lab A/B on a real phone. Physical Pixel 8 acceptance is
UNVERIFIED.

Install the APK as an update; do not uninstall to work around a signing mismatch. The developer-alpha publisher
verifies the historical development certificate against the previous release; that signer is update-compatible but
exposed in repository history and unsuitable for production. The Windows installer is not Authenticode-signed.

Suggested checks:
1. Ask "turn on dark theme" and press **Learn** on the run.
2. Ask "set the screen timeout to 2 minutes". The run should show *Map of Settings* and a `go_to`, with "Map: tapped …"
   steps in the overlay.
3. In Glass → Lab, run the Phase 3 warm-up and then the A/B.
