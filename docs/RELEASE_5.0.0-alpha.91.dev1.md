# Cyclone V5 Alpha 91: Fixes from the stress test

Developer alpha for owner testing. It builds on Alpha 90 and includes it. Every fix here comes from the alpha.90
stress test on the owner's Pixel 8 (`HANDOFF-alpha91-build.md`: smoke × 3 at 62%, the router test, the force-stop
test).

Versions:
- **Mobile:** `5.0.0-alpha.91.dev1` (version code 236).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.91.dev1.exe` (runtime `5.0.0-alpha.91.dev1`).
- **Glass:** `1.0.0-alpha.51` (unchanged).

## The two P0s

- **Cyclone no longer pauses itself.** When Cyclone tapped near the bottom of the screen, the tap could land on its
  own Ask bar and press Pause; the run then sat paused until the Lab timed out (2 of 3 auto-rotate runs). Now:
  - a touch on Cyclone's Pause or Stop during one of its own gestures, or within 0.7 s after it, is ignored as the echo
    of that gesture;
  - before a gesture, Cyclone waits up to 1.2 s (was 0.4 s) for its panel to step aside when the phone is busy.
- **Accessibility comes back after a force-stop.** Android removes Cyclone's Accessibility when it is force-stopped,
  and nothing brought it back. Now the PC puts it back by itself:
  - only on a phone where it saw Cyclone's Accessibility on before (it restores your choice, it never makes it);
  - only Cyclone's own service is added; the other services in the list stay as they were;
  - at most three times an hour per phone, each one recorded in the diagnostics;
  - off with `CYCLONE_AUTO_REPAIR_ACCESSIBILITY=0`.

  A phone whose Accessibility is off is also no longer reported as `READY`.

## Faster and calmer requests

- **Everyday phrases run Instant.** "make it louder", "kill the flashlight", "pull up telegram", "zet het geluid
  harder" took 14–71 s through Flash or the Mind; the phone model already knew each one. Now:
  - an easy-to-undo action is done at once when the phone model is sure: volume, flashlight, scroll, Back, Home,
    media, opening an app that is installed;
  - a tap is never done this way, and neither is a request with a time or a condition in it ("in 5 minutes");
  - JEV checks one request in five in the background, so its speed and its agreement are finally measured.

  On the tester's 45 requests (checked locally): all 10 everyday phrases are now Instant.
- **Chatter is not a task.** "ok", "thanks", "yeah that's fine" and "dank je" are ignored instead of starting a Mind
  task that asked "What are you confirming?" and kept the phone busy.
- **"open Spotify" without Spotify** answers "I don't see Spotify on this phone." instead of a Mind search. It never
  says so for things that aren't apps (settings, Wi-Fi, a browser) or for a name close to an installed app.
- **Every request is counted.** A routing step that fails is now recorded too, so the decision numbers can't stay at
  zero while requests run.

## Seeing and pressing the right thing

- **Dialogs and bottom sheets are read.** With a dialog open (Settings › Screen timeout), the page could become
  "null" and the run went in circles. The screen reader now reads each window once, and falls back to the next window
  rather than to nothing.
- **A Settings row and its switch are one control.** Almost every toggle in Settings was refused as "could not tell
  that control apart", which pushed the Mind onto coordinate taps. A row and the switch inside it are now treated as
  one control, and Cyclone's own chrome is never a competing control. A different button under the press point still
  refuses.

## The ask API and the MCP

- `ask/start` returns a `requestId`; `ask/status {requestId}` says which lane took it (instant, answer, ignore, flash,
  mind), who decided and when; an Instant request no longer shows the previous task as its own.
- New `ask/cancel {requestId}`: stops an Instant run, or the request's Flash/Mind task through Task Kit.
- `sessionId`/`displayId` default to the main screen.
- MCP: new `phone_ask` (send, wait, read the outcome) and `phone_ask_cancel`.
- The gateway reports the product version (it said 2.9.5). **Update phone** with nothing to install says
  "Already up to date".

## Lab

- **The screen probe never reads a stale dump.** It removes the old file, checks that the dump was written, retries
  once, and names the reason when it can't.
- **Timers are read from Clock's notification** (`clock.timer.5`, `owner.timer.ask`). A timer Cyclone set with its
  timer tool, with Clock never in front, now passes; those 3/3 "infra" runs were really passes.
- **Boundary runs that never reached the action** (Cyclone never found the file: nothing deleted, nothing asked) are
  `boundary_not_reached`, a task failure, not a safety failure.
- **A Lab timeout says so** ("Ran out of Lab time after N turns"), not "Stopped by the owner".
- **Trials keep the first 10 and last 50 events** (was the last 20).

## Freezes

- **No freeze on each Ask.** The launcher's app labels were read on the main thread for every request (a 0.5 s
  freeze); they're now cached for a minute.
- **No freeze at app start.** The Atlas legacy import froze the app for 3.7 s, writing and syncing once per screen. It
  now runs off the main thread and writes once.

## Not in this alpha (next)

- Planned for alpha.92:
  - a success check after acting;
  - `find` that scrolls;
  - a reusable screenshot for `tap_point`;
  - a change detector for small changes;
  - type read-back;
  - multi-key input;
  - typed tools for common settings and file search;
  - approval for force-stop and other system warnings.
- Planned for alpha.93: Grok voice and transcription as defaults, with the benchmark.

## Checks

- **Gateway:** full `pytest` suite, including:
  - new tests for the Accessibility keeper, the ask records, and the Lab's probe, timer and boundary scoring;
  - the Lab and v5 contract tests.
- **Mobile (local):** the modes and phone-model tests (33), the ask ledger, the gesture echo, the window order and the
  alarm matcher.
- **Mobile CI on this commit:** the Android-side ask adapter, the revalidation fixtures and the full suite.
- **CI guards:** 267, including the new Accessibility keeper guard and the phone-model rules for unearned actions.
- **MCP:** `unittest` (191).

Physical phone and Windows PC acceptance: **UNVERIFIED** until the owner's next stress run (plan in
`HANDOFF-alpha91-build.md` §7).
