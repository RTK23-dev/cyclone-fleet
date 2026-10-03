# Cyclone V5 Alpha 40 — Planes: Cyclone works on your screen or behind it

Developer alpha for owner testing, built on Alpha 39 (one map, grounded skills and routines), which it includes.
Mobile is `5.0.0-alpha.40.dev1` (version code 182), the Windows companion is `1.6.0-alpha.40`, and the bundled Glass
web app is `1.0.0-alpha.23`. This is the first step of plan 25 (Planes), the base that Cyclone Drive builds on.

## What changed

**One task, one place to work, and a pill to move it.** A Cyclone Mind mission now works on a *plane*: your screen, or
a private background screen that only Cyclone sees (Android 15+ with Shizuku). While a mission runs, a small glass pill
with a two-screens symbol sits on the Ask overlay and on the mission card:
- the front screen lit: Cyclone works on your screen;
- the back screen lit: Cyclone works behind your screen while you keep using your phone;
- the light moving between them: the task is being moved;
- dimmed with a line: background work is not available (long-press says why).

Tap moves the task to the other plane, on the same page, with the same app state (the app's task is moved, not
restarted). Long-press explains where Cyclone works and why; when Cyclone held an app back from the background, it
offers **Always in background**. The task notification has the same move (**Work in background** / **Show on screen**)
next to Stop. Every press goes through Task Kit.

**A switch never leaves a task half-moved.** Moving is a transaction: Cyclone finishes the action in hand and pauses
(a switch never cuts an action in half), moves the app, checks it is healthy where it landed, and only then continues
there. If anything fails it puts the task back where it was and says why on the pill. Every step is journaled, so a
restart in the middle closes the switch against where the app really is. The Mind is told once that it moved and looks
again before acting.

**Automatic: Cyclone decides where to work.** Settings → AI → *Where Cyclone works*: **Automatic** (default), **On
screen**, **In the background**. In Automatic, when a mission opens its first app while you are using another app,
Cyclone opens it on a background screen and leaves your screen alone; otherwise it works on your screen where you can
watch. Tasks that need your hands (sign-in, camera, scanning) start on your screen.

**Knowing when the screen is needed.** A background task comes to your screen, by itself, when:
- the app shows a protected screen (it looks black in the background);
- the step opens another app through a link, Settings, a timer or an alarm, or a notification;
- the background refuses the step (for example typing into a field that already has text);
- you take over, or you open the same app yourself.

Changing apps in the background opens the next app on a fresh background screen. Cyclone remembers per app and
version what worked, so an app that needs the screen is a one-time discovery.

**A watchdog keeps the background honest.** Every second while a task is in the background, Cyclone checks Shizuku,
its background service, the app and the lock screen. A locked phone waits. A broken background climbs a recovery
ladder: reconnect, rebuild the background screen and reopen the app, then continue on your screen (or pause when you
chose *In the background*). Every start, switch, rollback and health step appears in the run record.

**When the mission ends,** a task you moved to the background comes back to your screen where Cyclone left it; a task
that started in the background closes its background screen.

## Validation and limits

Android unit tests (the switch transaction, journal and restart recovery, policy, app memory, health and recovery
ladder, the step gate, the toolbox moving between planes, the pill's states and actions, notification actions, Task
Kit parsing and routing), lint, gateway and MCP suites, Glass and companion tests, and the CI guards (including new
Task Kit rules for plane moves) pass. **Physical Pixel 8 acceptance is UNVERIFIED.** Plan 25's device acceptance:
50 forced switches across Settings, Clock, WhatsApp and Chrome; one protected app (expected: comes to the screen); a
restart with Shizuku stopped (expected: *Unavailable*, then the screen).

Honest limits of this first step:
- Background work needs Android 15+ and Shizuku; on other phones the pill shows *Unavailable* and everything runs on
  your screen as before.
- Approvals for pay/send/delete in the background use the existing approval card; they do not bring the app to your
  screen first yet.
- Frame-stall detection is off (a quiet background screen sends no frames); the watchdog judges the service, the
  app's task and the lock screen.
- A background start opens the app at its first screen (only a move keeps the page).
- Lab runs always stay on the screen. Glass's Phone toggle and a plane lane in run replay come next.

Install the APK as an update; do not uninstall. It is signed with the rotated Cyclone release key and its signing
lineage (the alpha.34 / re-signed alpha.39 signer), so it installs over alpha.34 and alpha.39 and over alpha.33 or
older. It does not install over alpha.35–38 as published before the re-sign. The Windows installer is not
Authenticode-signed.

Suggested checks:
1. Shizuku running, Settings → AI → *Where Cyclone works* → Automatic. Open Chrome, then ask Cyclone "set a 5 minute
   timer in Clock". Clock should open behind your screen; keep using Chrome. Tap the pill: the timer page comes to
   your screen on the same page; tap again to send it back.
2. Ask something in WhatsApp while on the Home screen: it runs on your screen. Tap the pill mid-task: WhatsApp moves
   behind; the Mind continues there.
3. Open a banking app in the background: it should come to your screen by itself (protected screen) and the next
   time start there.
