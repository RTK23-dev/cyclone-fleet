# 25 — Planes: background work that just works, and one tap to switch it (plan)

**Status:** first step built in **alpha.40** (2026-09-26): the switch transaction with journal and rollback, the Mind on
planes (step gate, rebind), Automatic start with the *Where Cyclone works* setting, escalation to the screen, app
compatibility memory, the watchdog and recovery ladder, the pill, notification actions and Task Kit commands. Still to
come: approvals that bring the app to the screen first, frame-stall detection with a frame heartbeat, return to the
background after an escalation, the Lab planes suite, and Glass's Phone toggle and plane lane in run replay. Drive
(plan 24) builds on it. What remains is scoped in plan 26 (alpha.42); Drive is alpha.44–45.

**The owner's ask:**
- Make background work rock solid.
- Let Cyclone decide by itself when a task should run in the background.
- Switching a running task between the background and the screen must be reliable and effortless.
- Add a small, beautiful **pill switch** in the same design as the Ask Cyclone overlay: just a symbol, and one tap
  moves the task to the background or back to the screen.

---

## 1. How Apple and Google design this (principles, from public behaviour and platform APIs)

Neither company treats "where a task runs" as a property of the task. They treat it as a **place the task currently
is**, which can change.

| Principle | Apple | Google / Android | What it means for Cyclone |
|---|---|---|---|
| **A task has a stable identity; its surface can change** | A Live Activity survives moving between the Lock Screen, Dynamic Island and the app. | An Android `Task` keeps its id while it moves between displays (`move-stack`, `ActivityOptions.setLaunchDisplayId`); app streaming (VirtualDeviceManager / Companion Device) runs real app tasks on virtual displays. | The mission is the stable thing. The **plane** (main screen or background display) is a field on it that can change. |
| **One owner of input at a time, always visible** | System UI makes clear who has focus. | Android 15 gives a virtual display its **own focus** (`OWN_FOCUS`, `STEAL_TOP_FOCUS_DISABLED`), so background input never steals your screen. | Every plane has exactly one input owner (you or Cyclone), with a generation number that expires old actions. |
| **Changes happen as transactions** | Handoff completes or visibly falls back. | Task moves are verified by the system; failure leaves the task where it was. | A switch is *pause → move → verify → commit*, or it is rolled back. It never leaves a task half-moved. |
| **Always a fallback that works** | Features degrade to the plain app. | Features are gated on a capability check and fall back. | The main screen is the universal fallback. Background is an upgrade when available, never a requirement. |
| **Per-app compatibility is data** | — | Android's compatibility framework, ARC/DeX app compatibility lists. | Cyclone learns, per app and version, whether it works in the background, and uses that the next time. |
| **Glanceable status, one-tap control** | The Dynamic Island: a small pill, tap to expand, long-press for controls. | Android 16 **Live Updates** (promoted ongoing notifications as a status-bar chip) and ongoing notifications with actions. | The pill, the same state on the notification (and its chip), and Glass. |
| **Measure it** | — | Reliability targets (SLOs), staged rollouts, kill switches. | Switch success rate and latency are measured in the Lab; a setting can turn background off entirely. |

Apple also runs work headless through App Intents with no UI at all. The lesson we already follow is: **use the
direct route when there is one** (intents, `open_app`, the map) and treat UI automation as plane-agnostic.

## 2. What Cyclone already has (evidence)

The background engine exists and is careful. It is just not connected to the Mind, and not switchable.

**Isolated background screens** (`runtime/background/WorkspaceUserService.kt`, through Shizuku):
- A private virtual display with `TRUSTED | OWN_DISPLAY_GROUP | OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED |
  OWN_CONTENT_ONLY | DESTROY_CONTENT_ON_REMOVAL` (`WorkspaceDisplayPolicy`).
- Android 15+ only.
- The app is launched with `am start --display N`.
- Every input carries an explicit non-zero display id, so nothing can land on your screen.

**Moving a task between screens:**
- `handoff` moves the task to the main screen for **you** (the agent loses input).
- `resume` moves it back (`am display move-stack`).
- Both verify the exact task id afterwards.

**Frames and liveness** (`WorkspaceRuntime.kt`):
- The background screen renders into an `ImageReader`, which provides fresh frames and a "stream stalled" check.

**Safety on the background screen:**
- Screen lock pauses the work.
- A GATE approval pauses it into `BACKGROUND_NEEDS_HANDOFF`.
- A stale input generation is refused.
- The state machine is `WorkspaceLifecycle`: `BACKGROUND_OK`, `PAUSED`, `BACKGROUND_NEEDS_HANDOFF`,
  `FOREGROUND_REQUIRED`, `WAITING_FOR_CONFIRMATION`, `SECURE_CONTENT_UNAVAILABLE`, …

**The one mutation path is session-aware:**
- `PhoneToolExecutor` routes observe and act by `sessionId` + `displayId`.
- It refuses mismatches (`DISPLAY_SESSION_MISMATCH`).
- `CycloneAgentEnvironment` already takes an `ExecutionContext` and handles background sessions.

**Readiness check** (`BackgroundSetup.read`): Android version, Shizuku installed/running/authorized, accessibility,
notifications.

## 3. The gaps (why it does not "just work" today)

1. **The Mind only works on the main screen.**
   - `MindMissions` builds its environment with the default context.
   - Background tasks run the **old classic agent** (`WorkspaceTaskService` → `OpenRouterAdaptiveAgent`).
   - So the best engine, the map, skills and Learn never run in the background.
2. **Deciding the plane is a word match.** `ExecutionTargetResolver` chooses background only if the sentence says "in
   the background".
3. **Switching is one-way and human-only.**
   - `handoff` gives the task to *you*.
   - There is no "move to the screen and **keep working**".
   - There is no way to move a task that started on the main screen into the background.
4. **One app cannot be on both screens.**
   - Launch refuses when the app is already open on the main screen ("close this app on your main screen").
   - Right to be careful, wrong as a user experience: Cyclone should **move** that task instead.
5. **Shizuku is fragile.** It stops after a reboot and needs wireless-debugging pairing to start again. Nothing
   watches it during a task.
6. **No per-app knowledge.** Secure screens (`FLAG_SECURE` shows black on a virtual display), apps that close themselves
   on secondary displays, and games are only discovered by failing.
7. **No control on screen.** There is no pill, and no "move to background" or "show on screen" action anywhere.

## 4. The design

### 4.1 One task, one current plane

```
Mission (stable id, conversation, trail, Owner Moments)
  └── plane: Foreground(display 0) | Background(workspace session, display N)
        └── input owner: Cyclone | You      (generation-checked)
```

**`runtime/plane/TaskPlane.kt`:**
- `sealed class TaskPlane { Foreground; Background(sessionId, displayId) }`.
- Stored on the mission and in its journal, so a restart knows where the task was.

**The Mind becomes plane-aware:**
- `MindMissions` builds the environment with the plane's `ExecutionContext`.
- `PhoneMindToolbox` gets a `rebind(plane)` that clears references and observations.
- After a switch, the next tool result carries a harness line: "The task moved to the background. The screen is
  unchanged; continue."
- Every step already re-observes, so the Mind decides against the screen as it really is.

**New background tasks run on the Mind.** The classic `WorkspaceTaskService` path remains only for tasks started
before the upgrade, then retires.

### 4.2 A switch is a transaction

**`runtime/plane/PlaneSwitch.kt`** is a pure state machine with an Android port, and journals every phase to disk.

1. **Request.** From the pill, the notification action, Glass, Drive or the policy (§4.3). It is recorded as
   `requested(from, to, reason)`.
2. **Pause at a step boundary.**
   - The Mind loop checks for a pending switch **between tool calls**, never during one.
   - Typing (plan 21 Hands) is one atomic step.
   - At most one in-flight action is waited out (target under 2 s).
   - Input authority is revoked on the source plane (the generation goes up), so a late action from before the switch
     cannot land.
3. **Move.**
   - **Background → screen:**
     1. `am display move-stack <rootTask> 0`.
     2. Verify the **same task id and package** is top and focused on display 0.
     3. The background display stays warm and empty for 60 s (fast return), then is released.
   - **Screen → background:**
     1. Find the exact task Cyclone is working in on display 0 (the top task of the mission's app, never Cyclone or
        the launcher).
     2. Create a background display, or reuse a warm one.
     3. `move-stack` the task onto it.
     4. Verify the task is on display N.
     5. Bring back the app you were using before the mission started ("return app", recorded when the mission began),
        or leave whatever was underneath.
4. **Verify health on the new plane.**
   - The first fresh frame (background) or accessibility window (screen) appears within 1.5 s.
   - A fresh observation succeeds.
5. **Commit.**
   - The plane is saved.
   - Input is given to Cyclone on the new plane (a new generation).
   - The toolbox is re-bound and the pill animates to its new state.
6. **Roll back on any failure.** The task is moved back to its original plane (or left there), with a one-line reason
   ("This app can't run in the background: its screen is protected.") and a compatibility record (§4.4). The mission
   keeps running where it was.

**Properties we test:**
- Idempotent: a repeated request while switching is ignored.
- Crash-safe: the journal replays after a process death and ends either committed or rolled back.
- Never two input owners.
- Never an action on the wrong display.

### 4.3 Deciding the plane (Automatic)

A setting, **Where Cyclone works**, offers: *Automatic* (default), *On screen*, *In the background*. With Automatic,
`PlanePolicy` (pure, tested) decides at mission start and re-decides on events.

**At start, background is chosen when all of these hold:**
- background is ready (Android 15+, Shizuku running and authorised, the watchdog healthy);
- the target app is not known-incompatible;

**and at least one of these holds:**
- you are using another app right now (screen on, touched in the last 20 s, foreground ≠ target);
- Driver mode is on (plan 24: Maps stays on screen);
- the goal spans several apps, or is expected to be long (the map or skill says more than ~6 moves).

**At start, the screen is chosen when:**
- you are on the home screen or in Cyclone and idle (nothing to protect, and it is faster to watch);
- background is unavailable;
- the app is known to need the screen;
- the goal needs your hands (sign in with a password, a CAPTCHA, a camera).

**During a task, it moves to the screen by itself** (Driver mode asks first, by voice) when:
- **secure content:** frames go black while the accessibility tree is present, which means `FLAG_SECURE`;
- the app left or crashed off the background display twice;
- actions stop landing: three verified actions with no screen change;
- an Owner Moment needs your hands (SECRET, HANDOVER), or an approval for **pay / delete** needs you to see it
  (the Owner Moment card appears on screen with the app behind it).

**It returns to the background by itself** when a task that started in the background finished its on-screen moment,
and you are idle for 5 s or tap the pill.

**Conflict:** if you open the same app on your main screen while Cyclone works on it in the background, Android
would pull the task to you.
- Cyclone pauses at once (the task is yours now).
- The pill shows "yours".
- One tap on the pill hands it back, and it continues in the background.

### 4.4 App compatibility memory

**`plane/AppPlaneCompat`** keeps one record per package and version:
- `background: ok | secure | refuses | unstable | unknown`;
- counts and the last reason;
- written by every switch, rollback and escalation.

It is seeded with known classes: banking and payment apps (secure screens), camera, games, DRM video.

**Used by:**
- `PlanePolicy`: known-incompatible apps start on screen;
- skill anchors (a skill remembers "runs in background");
- Glass's fleet, as a column "Background: works / screen only".

### 4.5 The pill

A small glass capsule in the Ask overlay's design language:
- **Size:** 40 dp high, symbol only, 56 dp wide. Same blur, border and gradient as the composer capsule.
- **Placement:** docked next to the working ring of the Ask button (and next to the Drive AI button), on the same
  edge. It moves with the button. It is visible only while a task runs.
- **Symbol:** two stacked rounded rectangles (a screen in front of a screen).

| State | Look | Tap | Long-press |
|---|---|---|---|
| **On screen** | Front rectangle filled | Move to background | Menu: *Always run this app in the background*, *Stop*, *Show progress* |
| **In background** | Back rectangle filled, small dot orbiting (working) | Show on screen | Same menu |
| **Switching** | The rectangles swap places (≈400 ms spring), then settle | Ignored | — |
| **Needs you** | Amber dot | Brings the task to the screen with its card | — |
| **Unavailable** | Dimmed | One-line reason plus **Set up** (Shizuku setup) | — |
| **Yours** (you took over) | Hand badge | Give it back to Cyclone (continues where it was) | — |

**Feedback and access:**
- A haptic tick on commit, and a soft double tick on rollback.
- Accessibility label "Task on screen. Double-tap to move to background." (and the reverse). No motion when reduced
  motion is on.

**Other surfaces with the same action:**
- the ongoing task notification (and its Android 16 Live Update chip): **Show on screen** / **Move to background**;
- Glass Phone: a toggle;
- Drive: the spoken commands "do it in the background" or "show me".

**All of these send a Task Kit command:** `TaskCommand.MoveToBackground` / `MoveToForeground`, guarded by
`test_mobile_task_kit.py`. No surface calls the plane code directly.

### 4.6 Background health: a watchdog and self-healing

**`plane/BackgroundHealth`** ticks every 2 s while a background task lives. It checks:
- the Shizuku binder;
- the workspace service;
- the display is valid;
- a frame arrived in the last 2 s;
- the task is present on its display;
- the keyguard.

**Recovery ladder (each step once, then the next):**
1. Rebind the workspace service.
2. Recreate the display and relaunch the app, then **walk back to where it was with the map** (`go_to` to the last
   known screen). The map from alpha.37–39 makes this self-healing possible.
3. Move the task to the screen (Automatic), or pause with a notification (On-background-only).

**Shizuku stopped** (for example after a reboot):
- The pill goes to Unavailable.
- A notification offers "Background needs Shizuku — Start", which opens Shizuku.
- New tasks go to the screen until it is back.
- Setup explains the durable options (wireless debugging auto-start, or root) honestly.

**Locked screen:** pause (already the rule), then continue by itself after unlock (the mission survival from alpha.27
already does this for the screen plane).

### 4.7 Audio, notifications, battery

**Audio:** an app in the background still plays sound through the phone.
- While a task is in the background, Cyclone asks the app's media session to pause when it starts playing, and tells
  you once.
- Android cannot set per-display audio from a user app.

**Notifications:** the ongoing notification shows the plane and the current step. Owner Moments work the same on both
planes (they are plane-independent).

**Battery:** the background display renders at 720×1280 and 240 dpi, only while a task runs. It is released 60 s after
the last task.

## 5. Reliability engineering (how we know it works)

**Pure state machines, tested on the JVM:** `PlaneSwitch`, `PlanePolicy`, `BackgroundHealth` and the journal replay.
Every transition, rollback and invariant is covered: one owner, no wrong-display action, idempotent requests.

**Fault injection in the Android port:**
- Shizuku dies mid-switch;
- the screen locks mid-switch;
- the app crashes on the background display;
- rotation;
- process death during the move (the journal replays).

**Lab "planes" suite:**
- The same missions are run with 1, 3 and 5 forced switches at random step boundaries.

| Measure | Target |
|---|---|
| Switch success (committed, or cleanly rolled back) | ≥ 99.5% |
| Switch latency p50 / p95 | < 0.8 s / < 1.5 s |
| Actions lost or duplicated by a switch | 0 |
| Wrong-display actions | 0 |
| Mission success, background vs screen | within 3 points |

**In every run:** plane events are written to run diagnostics (requested, committed or rolled back, why, how long)
and shown on Glass run replay as a plane lane.

**Kill switch:** Settings → Where Cyclone works → *On screen* disables background entirely. The policy also turns
background off by itself after three unrecovered failures in a day, and says so.

## 6. Invariants kept

- PhoneToolExecutor stays the only mutation path, and every action names its session and display.
- GATE approvals are unchanged on both planes. A pay/delete approval brings the task to the screen, so you see what
  you approve.
- Passwords only go through the Secrets Card. Nothing about the background screen is recorded beyond what the run
  record already keeps.
- The background display is `OWN_CONTENT_ONLY`: it never mirrors your screen.
- Surfaces use Task Kit only.

## 7. Release plan

| Release | Contents |
|---|---|
| **alpha.40 Planes** | The Mind on background planes (new background tasks use the Mind), `PlaneSwitch` both directions with journal and rollback, `PlanePolicy` Automatic with the setting, app compatibility memory, the pill (tap / long-press / states), notification actions, Task Kit commands, `BackgroundHealth` watchdog and recovery ladder, the Lab planes suite, Glass Phone toggle and plane lane in run replay. |
| alpha.41 Hands | Plan 21, unchanged. |
| alpha.42–43 Drive | Plan 24, with Automatic planes keeping Maps on screen. |

Physical acceptance for alpha.40 is on the owner's Pixel 8 (Android 15+ with Shizuku):
- 50 forced switches across Settings, Clock, WhatsApp and Chrome;
- one secure app (expected: escalates to screen);
- a reboot with Shizuku stopped (expected: Unavailable, then fallback to screen).

## 8. Honest limits

- **Background needs Android 15+ and Shizuku.** On other phones the pill shows *Unavailable* and everything runs on
  the screen.
- **Some apps will never work in the background:** protected screens, and apps that close on secondary displays. The
  compatibility memory makes that a one-time discovery per app.
- **One app cannot run on both screens at once.** Cyclone moves it rather than duplicating it.
