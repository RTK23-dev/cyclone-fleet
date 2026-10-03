# Cyclone V5 Alpha 87: Phone care

Developer alpha for owner testing. It builds on Alpha 86 (Lab tools for agents on the PC) and includes it, plus the
MRZ Studio discovery work from PR 197.

Versions:
- **Mobile:** `5.0.0-alpha.87.dev1` (version code 232).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.87.dev1.exe` (runtime `5.0.0-alpha.87.dev1`).
- **Glass:** `1.0.0-alpha.48`.

This release makes the phone connection dependable. It rests on three reliability builds, all from the PC test pass
on 2026-09-30.

## What changed

### Update the phone from Glass

Under each phone in **Devices**, one line says how Cyclone on the phone is doing:
- **Up to date**: the phone and this PC run the same Cyclone.
- **Update available** with an **Update phone** button. It also shows for a phone that isn't connected yet, because an
  old phone app can be the reason connecting fails.
- **Updating the phone**: Glass shows progress while the update is fetched, checked and installed.
- If Android refuses, Glass says why and what to do in plain words, for example:
  - "A different build is on the phone" (signed by someone else);
  - "The phone is out of space";
  - "The phone didn't allow the install".

How it works:
- The PC installs its own release's phone build. It downloads it from the release and uses it only if its SHA-256 is
  in the release manifest, the same rule as `cyclone update`.
- It runs `adb install -r`: it never downgrades and never uninstalls.
- Only you can start it, from Glass. No agent or model tool can.

### A busy phone is no longer "disconnected"

The test pass found Cyclone freezing its main thread for up to 24 seconds while the phone sat idle in an app. The PC
reported this as `DEVICE_DISCONNECTED` although the cable was fine. Now:
- **Lighter screen-event handling.** For every screen event, the phone used to read Android's window list 3–4 times
  and look up every window's content on the main thread. It now reads the list once per event, and only looks up
  the app in front when the screen's structure changes.
- **A freeze watchdog.** Any freeze longer than half a second is recorded with the Cyclone code it was stuck in.
- **The phone says it's busy.** When its workers are held by slow work, the phone answers `PHONE_APP_BUSY` at once
  instead of going silent. Health and status reads always answer.
- **The PC tells busy from gone.** A connected phone that doesn't answer in time is `PHONE_APP_BUSY` (try again),
  never `DEVICE_DISCONNECTED`. Temporary refusals, such as a screen that changed during a read, are now marked
  retryable. MCP agents are told to wait a second and retry.

### Why Cyclone stopped

The phone now reports Android's own record of why Cyclone's last processes ended: a crash, a freeze Android closed,
memory, an update. For a freeze, it includes the main thread's stack. It also reports the freezes the watchdog caught.
- **On the PC:** the gateway keeps these per phone and writes them into the USB session's diagnostics folder, so a
  debug bundle carries the cause.
- **In Glass:** one line, such as "Cyclone stopped unexpectedly · 1 h ago · It stopped because it stopped responding
  and Android closed it", or "Cyclone froze 3 times today".
- **Behind Details:** versions, recent stops, each freeze's code and the diagnostics folder.

Normal events stay out of the headline, such as Android reclaiming memory from a background app or a short freeze.
The report holds code names, times and sizes only: nothing you typed or saw.

### Also in this release

- **MRZ Studio · Employee ID in Connections (PR 197).** Studio is discovered at startup and every 30 seconds, with a
  pasted-JSON setup path. Connections that match keep their approvals across runtime updates. See
  `docs/GLASS_MRZ_STUDIO.md`.
- **Diagnostics snapshots** record adb's live state, not the cached one, so a snapshot after a disconnect no longer
  contradicts itself.

## Checks

- Gateway: `pytest` (full suite), including 21 phone-care tests (update, errors, health history, verdicts, routes,
  busy vs. gone).
- MCP: `unittest` (191).
- Glass: 266 tests, `npm run build`, `glass_guard`.
- CI guards: `scripts/ci/tests` (254), including the new `test_phone_care_guard.py`.
- Mobile: JVM tests for the freeze log, exit reasons and the busy guard. Mobile CI runs the unit tests and the APK
  build on this commit.

Physical phone and Windows PC acceptance: **UNVERIFIED** until tested. On the Pixel 8, check:
- **Update phone** from Glass, from alpha.84, alpha.85 or alpha.86.
- `phone_observe` after five idle minutes: no `DEVICE_DISCONNECTED` while USB is attached.
- Force-stop or crash Cyclone once: Glass shows why on the next connect.
