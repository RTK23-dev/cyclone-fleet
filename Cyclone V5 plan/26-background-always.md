# 26 — Background that always works (plan for alpha.42)

**Status:** built in **alpha.42** (2026-09-26): A42-1 to A42-9. A42-0 (device evidence) and the Pixel acceptance bars
are still owed; the still-page frame rule was changed before measuring it. Follows Planes (plan 25) and Hands (plan 21).
Proposed as **alpha.42**, with parallel sessions in **alpha.43**. Drive (plan 24) moves to alpha.44–45: a voice
assistant in the car must be able to rely on the background.

**The owner's ask:**
- Scope exactly how to build alpha.42 from here.
- Say what is still missing for background work that always works and is very easy to switch on.
- Add a setting for "always start on my screen" or "always start in the background".
- Handle the case where the owner is using an app and asks for another task in the background, including the same
  app.
- Say how a company serving a billion people would make this reliable.

---

## 1. Where we are (from the code, not from memory)

Alpha.40 gave a mission one plane at a time, a switch transaction with rollback, the pill, Automatic start, app
memory and a watchdog. Alpha.41 made typing work on the main screen. Reading the background path again shows what
still breaks, or would break, for ordinary people.

| # | Gap | Evidence | Effect |
|---|---|---|---|
| G1 | **Setup is expert-only and does not survive a reboot.** Background needs Shizuku, started through Wireless debugging, and nothing restarts it after a reboot. | `BackgroundSetupActivity` steps `INSTALL_SHIZUKU → START_SHIZUKU → AUTHORIZE → ACCESSIBILITY → NOTIFICATIONS`. No boot receiver. The Shizuku user service is `daemon(false)`. | Most owners never switch it on; those who do lose it at the next reboot without noticing. |
| G2 | **An app in Recents cannot start in the background.** Launching refuses any task of the package on display 0, including an invisible one in Recents. | `WorkspaceUserService.launch`: `require(tasks.none { it.packageName == packageName && it.displayId == 0 })`; `BackgroundSetup.failure`: "close it on your main screen, including its recent task". | WhatsApp, Gmail and Chrome almost always have a recent task, so Automatic quietly falls back to the screen. |
| G3 | **Typing in the background is weaker than on screen.** Only empty fields; no read-back, no paste, no focused typing. | `PhoneToolExecutor` workspace branch: `"phone.type" -> … editable && text.isBlank() … else error("UNSUPPORTED…")`. | Hands (alpha.41) does not apply in the background; replies and edits escalate to the screen. |
| G4 | **Gestures and intents are limited in the background.** Vertical swipes only; no links, Settings pages or intents on the background display. | Workspace branch: `"phone.swipe" … require vertical`; `open_link` / `open_settings` switch to the screen (`MissionPlanes.before`). | Many ordinary steps pull the task to the screen. |
| G5 | **Frame freshness may block input on a still screen.** Input requires a frame younger than 750 ms, but a virtual display only sends frames when content changes. | `WorkspaceRuntime.authorizeTouchLocked` → `check(LiveVisionRuntime.healthy(...))` (`ageMs in 0..750`); frames come from `ImageReader.setOnImageAvailableListener`. | Suspected: on a static page the next tap fails with `FRAME_STREAM_STALLED`. **Verify on the Pixel first** (Phase 0). |
| G6 | **One mission at a time.** | `MindMissions.launch`: `if (worker?.isAlive == true) return false`. The older workspace queue holds 8 requests but only for the step agent. | "Do this in the background too" while a mission runs is refused. |
| G7 | **The same app cannot be in two places.** An ordinary app has one task; moving it to the background takes it from the owner. | Planes design (plan 25 §8). Only `OWNER_OPENED_APP` handling exists (escalate to the screen). | The owner's own use of an app and a Cyclone task in that app collide. |
| G8 | **Approvals in the background are blind.** Pay/delete approvals use the normal card without showing the background screen. | Plan 25 status; `SEE_TO_APPROVE` is never raised. | The owner approves something they cannot see. |
| G9 | **The background screen is a fixed small tablet-ish screen.** | `WorkspaceRuntime.open`: `ImageReader.newInstance(720, 1280, …)`, density 240. | Some apps pick a different layout than on the phone, so learned maps and skills do not match. |
| G10 | **No start preference beyond Automatic / On screen / In the background; no per-app choice; no "if background can't" choice.** | `PlaneMode` has three values; `AppPlaneCompat.allow` is the only per-app control. | The owner cannot say "banking always on my screen" or "wait for me instead of taking my screen". |
| G11 | **No proof on real phones.** No Lab planes suite, no background metrics in Glass. | Plan 25 status. | We cannot tell whether a change helped. |

What cannot change: below Android 15 there are no private background displays for a normal app, and without Shizuku
(or a privileged install) Cyclone cannot create one. The design has to treat the background screen as an **upgrade**,
never as the only way.

---

## 2. How a company serving a billion people would build this

1. **A floor that always works, then upgrades.** Every phone gets a working path with zero setup. Better paths are
   used when they are available, and the owner never has to know which one ran. Here that means three tiers, tried in
   order for every step:
   - **Tier 0, no screen at all:**
     - intents: timers, alarms, calendar, share, dial and maps already work this way;
     - notification actions and direct replies (reply to a message from its notification);
     - app functions, where apps expose them.

     Fastest, invisible and needs no setup. It is how the big assistants do most of their work: through an API, not
     by driving the screen.
   - **Tier 1, a private background screen:** Android 15+ with Shizuku.
   - **Tier 2, the owner's screen:** with the owner's consent, the pill and a clean way back.
2. **One switch, one answer.** "Background work: On". If something is missing, the phone says exactly one next step.
   Status is always visible: in the pill's long-press, a Quick Settings tile and the notification.
3. **Setup survives the phone's life.** Reboots, app updates, Shizuku updates, OEM battery killers. Everything that can
   restart itself does. Everything that cannot tells the owner with one tap to fix it, before a task needs it and not
   in the middle of one.
4. **Resources are scheduled like an operating system does.** Apps are resources; sessions hold leases on them;
   conflicts have rules; nothing is taken from the owner without asking; waiting is a first-class outcome, not a
   failure.
5. **Every path is measured, per device and per app version.** Success rates by tier, switch success, escalations per
   100 tasks, time to first action. A capability that regresses on some phones is switched off there automatically.
   With the owner's opt-in, an anonymous compatibility list (package, version, outcome; never content) could be shared
   so that the next owner gets a known-good path. Cyclone keeps this local-only until the owner decides otherwise.
6. **Visible, reversible, owner first.** The owner can always see what Cyclone is doing, take it back in one tap, and
   Cyclone never works in an app the owner is using without saying so.
7. **Honest limits.** A third-party app cannot be as seamless as an assistant built into the phone. The lasting path to
   "everyone, always" is a privileged install (OEM preload or system permission) or the platform's own agent APIs.
   Cyclone's design keeps those as drop-in tiers: the `PlanePort` stays the same, and only the implementation behind
   it changes.

---

## 3. The start preference (the setting the owner asked for)

**Settings → AI → Where Cyclone works**

| Choice | Behaviour |
|---|---|
| **Automatic** (recommended) | Tier 0 when the step allows it. Otherwise background when you are using your phone or the task is long; your screen when you are not using it or the task needs your hands. |
| **Always start in the background** | Every task starts on a background screen. Steps that need you (sign-in, camera, protected screens, approvals you should see) still come to your screen, and go back when done. |
| **Always start on my screen** | Tasks run where you can watch. The pill can still move one to the background. |

**When background isn't possible right now** (shown for the two choices that can use it):
- **Use my screen** (default for Automatic);
- **Wait until I'm done with the app** (default for Always in the background);
- **Ask me each time.**

**Per app** (a list built from what Cyclone has learned, editable): *Always on my screen* / *Always in the background*
/ *Cyclone decides*. Banking, camera and game apps start as *Always on my screen*.

The setting lives in `PlaneMode` plus two new fields, `PlaneFallback` and a per-app `PlaneOverride` (stored in
`AppPlaneCompat`). The pure `PlanePolicy.start` gains the tier-0 check, the per-app override and the fallback. It stays
one pure function with a decision table test.

---

## 4. The owner is using an app and asks for a task (the hard case)

Every app has at most one **lease holder**: the owner (it is on their screen) or one Cyclone session. `AppLeases`
(new, pure) records who holds what; the Mind asks for a lease before its first action in an app.

| Situation | What happens |
|---|---|
| **Different app** (you're in Chrome, ask for a WhatsApp reply) | Tier 0 if possible (direct reply from the notification, with GATE as always for sending). Otherwise a background session for WhatsApp. If WhatsApp is only in Recents, its task is **adopted** into the background (fixes G2) and keeps its state. You keep Chrome. |
| **Same app, and the app supports a second window** (Chrome, Docs and others that declare multiple instances) | A second instance opens on the background screen. You keep yours. Detected per app and version at run time and remembered in `AppPlaneCompat`. |
| **Same app, single-window app** (you're in WhatsApp, ask Cyclone to message someone else in WhatsApp) | Tier 0 first (direct reply, share intent with a draft). If that is not possible, one line on the overlay: **"Cyclone needs WhatsApp. When you're done / Now on your screen / Take it to the background."** The default follows your *When background isn't possible* setting. *When you're done* waits until WhatsApp leaves your screen or the screen turns off, then starts by itself. The task card says "Waiting for WhatsApp" with **Start now**. |
| **You open an app Cyclone is using in the background** | You win: the task pauses at the next step boundary (existing `OWNER_OPENED_APP`), the app comes to you on the same page, and the pill shows "Paused: you have WhatsApp". When you leave the app, Cyclone takes it back and continues. It never types while you hold the lease. |
| **A second task while one runs** | alpha.42: it is queued ("runs next", visible on the task card, cancellable). alpha.43: it runs at the same time on its own background screen when the phone has the memory (up to 2 background sessions on 8 GB+, 1 below) and the apps do not collide; otherwise queued. |

What must never happen: two sessions typing into one app; Cyclone taking an app from the owner without a visible
prompt; a queued task starting while the phone is locked.

---

## 5. Alpha.42: exactly what to build

Nine work items, each with where, tests and an exit check. The order matters: 0 and 1 unblock everything else.

### A42-0 Evidence first (Phase 0, one day)
- On the Pixel: a background session on a static page, with 20 taps 5 s apart. Record whether any fail with
  `FRAME_STREAM_STALLED` (G5).
- Capture the workspace layout of 5 apps at 720×1280/240 dpi against the phone's own size (G9).
- **Exit:** G5 and G9 confirmed or ruled out, with numbers in the plan.

### A42-1 Switch it on once, keep it on
Where: `runtime/background/BackgroundSetup*`, a new `BackgroundCapability`, a new `BackgroundBootReceiver`, a new
`CycloneBackgroundTile` (a Quick Settings `TileService`), Settings, the gateway and the Glass Phone page.
- `BackgroundCapability.read()` returns one of the following, with **one** next action each:
  - `READY`;
  - `NEEDS_START` (Shizuku installed but stopped);
  - `NEEDS_SETUP` (a named missing step);
  - `UNSUPPORTED` (below Android 15).
- Settings gets one switch, **Background work**, and a Quick Settings tile with the same state. Turning either on with
  something missing opens the guided setup at the missing step.
- **After a reboot** (`BOOT_COMPLETED`), if the owner turned background on and Shizuku is not running: Cyclone posts
  one quiet notification, "Background work is paused: tap to resume". It opens Shizuku's start screen and comes back
  with the state re-read.
- **Make it permanent (from the PC).** Glass → Phone → *Keep background work on*. The gateway runs one allowlisted,
  typed step over the existing Lab ADB channel:
  - it grants Shizuku's non-root start-on-boot permission (`WRITE_SECURE_SETTINGS` for Shizuku), so it restarts
    itself after reboots;
  - Glass then shows the result.

  The op name must follow the gateway rule, for example `setup.background_permanent`. No generic shell for the model.
- **Before a task needs it:** a daily check. If the capability dropped, the owner is told once, not during a task.
- **Tests:** the capability state machine (pure); boot receiver decisions (pure); the gateway op contract and
  allowlist.
- **Exit:** on the Pixel, after 5 reboots with *Keep background work on*, background is `READY` within 60 s every time,
  with no taps.

### A42-2 Start from Recents (G2)
Where: `WorkspaceUserService`, `WorkspaceRuntime`, `MissionPlanes`.
- If the package has a task on display 0 that is **not visible**, adopt it (the alpha.40 `adopt` path) instead of
  refusing. If it is the visible top task, it is the owner's, so apply §4.
- A new service call, `visibility(packageName)`, answers `NONE`, `RECENTS` or `VISIBLE` from `am stack list` plus the
  accessibility windows.
- **Tests:** a start-plan decision table (pure); service behaviour with a fake task list.
- **Exit:** Automatic starts WhatsApp, Gmail and Chrome in the background from Recents 20/20.

### A42-3 Background parity (G3, G4, G5, G9)
Where: `PhoneToolExecutor` (workspace branch), `CycloneAccessibilityService.typeEditableOnDisplay`,
`WorkspaceDisplayPolicy`, `WorkspaceRuntime`.
- **Typing:** the alpha.41 ladder on the background display: set-text on any ordinary field, read-back, then paste
  with the display's input focus, then clipboard restore. `type_text focused=true` works there too. Secret fields stay
  on the Secrets Card path.
- **Gestures:** horizontal swipes (carousels, tabs), guarded like on screen.
- **Links and intents on the background display:** `am start --display <id>` with a view intent for http(s),
  `market:`, `geo:` and Settings actions, in the same session. They only leave the background when the target is a
  different app that the owner holds (§4).
- **Frames:** if Phase 0 confirms G5, authorize input when the last frame exists and the accessibility fingerprint is
  unchanged since that frame (nothing to redraw means nothing stale), and re-enable the frame-stall watchdog for real
  stalls (no frame and a changed tree).
- **Screen size:** the background screen copies the phone's own width, height and density (scaled to at most 1080 px
  wide for memory), so apps choose the same layout as on the phone and maps and skills keep matching.
- **Tests:** executor workspace-branch unit tests for each new tool; engine ladder with a display host fake.
- **Exit:** the Hands suite run in the background passes ≥ 90%. Escalations drop by half against alpha.41 on the Lab
  planes suite.

### A42-4 The start preference and per-app choices (G10)
Where: `PlanePolicy`, `PlaneMode` / `PlaneFallback` / `PlaneOverride`, `AppPlaneCompat`, the settings UI and the pill
long-press.
- The setting from §3, the per-app list (editable, seeded, learned).
- "Always start in the background" also works for tasks that do not open an app first: a background session starts at
  the first app the Mind needs.
- **Tests:** the full decision table (mode × fallback × per-app × ready × owner busy × tier-0 available).
- **Exit:** every row of the table is covered by a test.

### A42-5 Leases and the same-app case (G7)
Where: new `runtime/plane/AppLeases.kt` (pure), `MissionPlanes`, overlay card (Owner Moments family), Task Kit
commands `StartNow` / `WhenImDone` / `TakeToBackground`, `WorkspaceTasks` state "waiting for an app".
- The rules in §4. The owner lease follows the foreground package: accessibility window changes, screen on/off.
- Multi-instance: detect the app's declared support for a second window. Open the instance on the background display
  with a new-document launch. Record the outcome per version; if it fails, fall back to the single-window row.
- The "When you're done" trigger survives process death. It uses the mission journal (alpha.27 survival).
- **Tests:** a lease state machine including races (the owner opens the app during adoption); card choices through
  Task Kit (guard); the waiting-trigger logic.
- **Exit:** on the Pixel, "message X in WhatsApp" while the owner is in WhatsApp:
  - *When you're done* starts within 3 s of leaving WhatsApp, 10/10;
  - opening WhatsApp mid-task pauses Cyclone at the next step, 10/10.

### A42-6 Tier 0 (first cut)
Where: `PlanePolicy` (tier choice), `PhoneMindToolbox` (a `reply_notification` tool), `AndroidMindDevice`
(notification actions with `RemoteInput`).
- A messaging reply can go through the message's own notification reply action, with no screen at all. This is still
  a *send*, so GATE (and later Drive's readback) confirms it every time.
- Existing intent tools (timers, alarms, links, Settings) count as tier 0 in Automatic.
- **Tests:** tool-level tests with fake notifications. A secret-looking reply is refused. Approval is always required.
- **Exit:** "reply 'on my way' to Louella's last WhatsApp" works from any screen with no background screen, and asks
  for approval, 10/10.

### A42-7 See what you approve (G8)
Where: the approval card (`CycloneOwnerCard` / overlay), `LiveVisionRuntime.preview`.
- A pay/delete/send approval for a background session shows a live thumbnail of the background screen in the card,
  with **Show on my screen** (a Task Kit move). The owner sees what they approve without Cyclone taking their screen.
- **Tests:** the card model with and without a preview; the Task Kit guard.

### A42-8 Queue, not refuse (G6, first step)
Where: `MindMissions`, `WorkspaceTasks.requests`, the task card.
- A second request while a mission runs is queued with its plane choice, shown as "Runs next", and can be started now
  (stopping or pausing the current one) or cancelled. True concurrency is alpha.43 (§6).
- **Tests:** queue order, persistence and cancel, through Task Kit.

### A42-9 Proof: Lab, Glass, metrics (G11)
- **Lab `planes` suite:**
  - 50 forced switches across Settings, Clock, WhatsApp and Chrome;
  - background start from Recents;
  - a protected app;
  - reboot with and without *Keep on*;
  - the same-app conflict;
  - the Hands suite in the background.
- **Glass Phone page:** background capability, *Keep background work on*, and the switch as a Task Kit command.
- **Run replay:** a plane lane (screen / background / waiting / tier 0).
- **Metrics per run and fleet-wide on the PC:** tier used, background start success, switch success, escalations per
  100 tasks, lease waits.
- **Exit (release gate):**
  - switch success ≥ 99% over the 50;
  - no half-moved task;
  - background start ≥ 95% on the four apps;
  - a regression in the core suite blocks the release.

### Order, lanes and size

| Order | Items | Lane |
|---|---|---|
| 1 | A42-0, A42-1 | mobile + gateway + glass |
| 2 | A42-2, A42-3 | mobile (executor and workspace service: one agent) |
| 3 | A42-4, A42-5, A42-8 | mobile (planes, Task Kit) |
| 4 | A42-6, A42-7 | mobile |
| 5 | A42-9, docs, versions, release | gateway + glass + CI |

Versions: mobile `5.0.0-alpha.42.dev1` (184), PC `1.6.0-alpha.42`, Glass `1.0.0-alpha.25`. Signed with the rotated
release key (same publisher as alpha.40/41).

---

## 6. After alpha.42

| Release | Contents |
|---|---|
| **alpha.47 Parallel sessions** (alpha.43 became the Glass overlay, plan 27; alpha.44 the stable background, plan 28; alpha.45–46 direct first and the engine, plan 29) | `MindMissions` becomes a host of missions, each with its own worker, inbox, task card, plane session and lease set. Up to 2 background sessions plus the owner's screen, capped by memory. Per-session notifications and pill. The Lab concurrency suite. |
| **alpha.48–49 Drive** | Plan 24, now standing on tier 0 replies, leases (Maps holds the screen) and a background that stays on. |
| **later: Desk** | Plan 21 Phase 2. |
| **longer term** | A privileged tier (OEM or system-permission install) and platform agent APIs behind the same `PlanePort`, where available. |

---

### 6.1 As built (alpha.65): parallel sessions

- **Front and behind:** `MindMissions` hosts missions (`Run`: worker, cancellation, owner messages, plane session,
  metrics, trail, card). One is in front (overlay, pill, task card, service). Up to two work behind it, by memory
  (`Crew.behindSlots`: 2 from 8 GB, 1 from 6 GB).
- **Admission:** `Crew.admit` decides whether a new task goes to the front, behind or the queue. A behind task needs:
  - background ready;
  - not a Lab run, and no Lab run in front;
  - not a task that needs the owner's hands;
  - a free slot;
  - the setting on.

  Owner text that is a clearly separate task goes behind when admitted (`offer`); the Command Center's `cc.start`
  too.
- **Behind sessions:** `MissionPlanes.beginBehind` starts a plane session with no screen.
  - **Before it has a screen of its own,** `Crew.behindRefusal` refuses every phone tool except open_app, set_timer
    and set_alarm.
  - **Its opening situation** is a fixed text (`Crew.BEHIND_SITUATION`); it never reads the owner's screen.
  - **Any road to the owner's screen** (switchTo, land, owner_takeover, a start plan that is not a background screen)
    waits in `awaitFront` until `MissionPlanes.claimFront` grants the front. Then `MindMissions.promote` moves the
    card, service and overlay to it.
  - **It never sets the phone's controller,** and its plane never reaches the pill.
- **Promotion:** when the front mission ends, the behind mission that already holds the screen, or else the oldest,
  comes to the front.
- **App leases between missions:** `MissionPlanes.hold / heldByOther / waitForApp`. One mission per app on background
  screens, and the front mission never opens an app another mission holds.
- **One inbox:** `OwnerInbox` keeps one open request per mission. `pending` is the oldest, and requests from behind are
  named by `label`. Task Kit answers a task's own request, and the front card also answers the visible one.
- **Task Kit:** `TaskCommandBus` finds tasks behind the front one (`others`). `MindTaskController` stops and answers
  them, and refuses screen commands for them. `BehindNotifications` posts one notification per behind task, with
  Approve / Decline / Stop pending intents.
- **Share gate:** `PublishGate.mark` records posting tasks; `active()` stays on while any of them runs.
- **PC:** the Command Center dispatcher sends a phone up to 3 tasks (`PHONE_TASKS`), prefers phones with fewer, and
  asks a phone that answered ASK_BUSY once per round.
- **Not yet:** the Lab concurrency suite and per-task pills on the overlay.

## 7. Invariants kept

- `PhoneToolExecutor` stays the only mutation path, with session and display on every action.
- GATE for pay/send/delete/permission/sign-in is unchanged on every tier, and tier 0 replies are sends.
- Secrets only through the Secrets Card. The clipboard is written by Cyclone and restored; it is never read into the
  model.
- Task buttons only through Task Kit.
- No generic shell or ADB for the model. The PC setup step is a typed, allowlisted gateway op run by the owner from
  Glass.
- Nothing is taken from the owner without a visible prompt. The owner always wins a lease.

## 8. Honest limits

- Below Android 15, and without Shizuku, there is no background screen. Such phones get tier 0 and the screen, which
  already covers timers, alarms, links, replies and searches.
- *Keep background work on* needs the PC once (or a rooted phone). Without it, a reboot needs one tap.
- OEM battery managers (Samsung, Xiaomi and others) can still stop services. The daily check and the one-tap fix make
  this visible; the Lab device matrix should add one Samsung phone before a public build.
- Single-window apps can serve only one holder at a time. Cyclone waits, asks, or uses tier 0; it never duplicates
  them.
- The anonymous compatibility list across owners is a design option, off and unbuilt until the owner decides.
