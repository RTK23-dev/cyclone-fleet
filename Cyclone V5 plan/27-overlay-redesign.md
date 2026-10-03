# 27 — The overlay, redesigned (in-between update after alpha.42)

**Status:** built in **alpha.43** (5.0.0-alpha.43.dev1, version code 185, 2026-09-26), after the owner agreed the
design in the Overlay Studio rounds and asked for a new alpha. It follows the studio's final round, which superseded
§2's first sketch:
- tilt-lit glass instead of the fingerprint whorls;
- the one-word pill stays above the island;
- the app logos lead the card.

Not built:
- the fly-into-pill motion (the phone goes home and the stack folds instead);
- the PC `planes.journal` view (the reason shows on the phone).

Parallel sessions move to alpha.44 and Drive to alpha.45–46.

**The owner's ask** (with two Pixel screenshots of a running Mind task in WhatsApp):
- The working card sits too close to the Ask Cyclone bar. It needs natural separation.
- The background/foreground switch should be a small pill with one short word, stacked above the working card: a
  third, smaller capsule.
- Switching to the background still does not work on the Pixel. It should be a smooth move back to the home screen.
  Today the switch button and the working card stay where they are.
- Drag the working card down once: the work shrinks into the Ask pill's size and shows its live status there. Drag
  down again: only the live notification remains.
- Every overlay should share the Ask bar's material: the fingerprint texture and the soft, round voice button
  instead of the working card's hard edges.
- A full redesign, a visual masterpiece. Plan it first; the owner gives the green light.

---

## 1. What the screenshots show, traced to the code

| # | What the owner sees | Why (code) |
|---|---|---|
| O1 | The working card touches the Ask bar. | `SignatureOverlayDrawer` puts `composer()` directly under the clipped work panel, with no spacer. The only gap is the 24 dp drag handle, and it sits *above* the panel. |
| O2 | The switch is a square tab glued to the top right of the card. | `PlanePill(Modifier.fillMaxWidth())` is a 58×40 dp symbol-only capsule, placed *inside* the scrolling work column (`ComposerPanel`), right above the task card. |
| O3 | The working card looks harder and heavier than the Ask bar. | The Ask bar is `CycloneSignatureGlass` (textured = fingerprint dots, 30 dp radius, round 38 dp buttons). The work area is `CycloneConversationPanel`, clipped with a 30 dp rounded rectangle, with an inner card that has its own edges. These are two materials with two rims. |
| O4 | Tapping the switch seems to do nothing. | The switch runs on a thread (`MissionPlanes.request` → `ownerSwitch` → `switchTo`). **Every non-success is silent.** A refusal or a rollback only sets `note`, and `note` is shown only when the owner long-presses the pill (`explained`). Possible reasons on this Pixel: background not ready (`blocker`), WhatsApp remembered as needing the screen (`compat.needsScreen`), the step gate not pausing in time (`PAUSE_TIMEOUT_MS`), or a failed verification leading to a rollback. The switch journal (`Cyclone Brain/Planes/switches.jsonl`) has the answer, but no gateway op or Glass view reads it. |
| O5 | Even when a switch commits, the overlay stays open over the old app. | A committed background switch only updates `PlaneUi`. Nothing tells the overlay chrome to collapse, and nothing takes the owner home. The screen shows whatever task Android puts next on display 0. |
| O6 | Dragging down has one level only. | `onDragEnd` → `settle(0f) { collapse() }` → `MINIMIZE`: the work panel is hidden but the full Ask bar stays. There is no "work in a pill" state, and no "notification only" state. |

The dotted/hex "Trace Field" behind the screenshots is Cyclone's working visual. It stays; the redesign only makes the
chrome sit on top of it more calmly.

---

## 2. The design: one material, four heights

### 2.1 One material: Signature Glass

Every surface Cyclone floats over another app uses **the Ask bar's glass**:
- Fingerprint texture (the fine dot field), a soft teal rim and a deep backing.
- Capsule corners: radius = half the height, up to 30 dp for tall cards.
- **No inner cards with their own edges.** Content sits directly on the glass, with sections separated by space
  and a 0.5 dp hairline at 8 % white.
- **Round controls everywhere:** 38 dp circles at 8 % white, like the mic and pause buttons. The working card's
  chevron, the stop button, owner-card buttons and the pill's symbol all use this "soft button".

A `CycloneGlassTokens` object holds these values (radii, rim, texture density, the soft-button fill and size, gaps).
The Ask bar, the work card, the plane pill, the owner/approval card, the secrets card, status pills and the tools
sheet all read it. Nothing draws its own edge again.

### 2.2 The stack (expanded, while working)

```
                          ╭────────────╮
                          │ ▣ Background│   ← plane pill: 34 dp tall, one word, right-aligned
                          ╰────────────╯
        ↕ 10 dp
╭──────────────────────────────────────────────╮
│ ◎ Cyclone Mind                     ◌ Working  (⌄)│   ← work card: same glass as the Ask bar,
│ Checking the current page                     │     title + live step + progress hairline,
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━──────────── │     earlier steps one scroll up
╰──────────────────────────────────────────────╯
        ↕ 14 dp   (natural separation: Ask bar and card never touch)
╭──────────────────────────────────────────────╮
│ +   Ask Cyclone                      🎙   ⏸  │   ← Ask bar (unchanged, the reference material)
╰──────────────────────────────────────────────╯
```

- **The plane pill** leaves the work column and becomes the third capsule of the stack, above the card and aligned
  to its right edge. It has a 16 dp symbol (the two stacked screens, kept) and **one word**:

  | State | Word | Tap |
  |---|---|---|
  | On your screen | **Screen** | Move to the background |
  | In the background | **Background** | Show on my screen |
  | Moving | **Moving** (the light slides between the screens) | — |
  | Waiting for the owner | **Waiting** | Start now |
  | Not possible here | **Screen** with the slashed symbol | Shows why, inline |

  The word says where Cyclone works now; the tap moves it. Long-press still explains (plan 25 §4.5). The pill sizes
  to its word (min 88 dp, 34 dp tall) and never covers the card.
- **Gaps:** pill → card 10 dp, card → Ask bar 14 dp. The drag handle moves *into* the card's top edge (a 32×3 dp
  grabber inside the glass), so there is no invisible 24 dp strip.
- **Outcome line:** every switch that does not commit shows, for 4 s, one line under the pill on its own small glass:
  *"Couldn't move WhatsApp: it stayed on your screen. Tap to see why."* Silence is gone (O4).

### 2.3 Four heights, one gesture

The overlay has four resting heights. The owner moves between them by dragging down, or up, on any part of the stack
that is not a button.

| Height | Shows | Down → | Up / tap → |
|---|---|---|---|
| **H3 Expanded** | Plane pill + work card + Ask bar (the stack above) | H2 | — |
| **H2 Island** | **One capsule the size of the Ask bar** with the live status: spinner, the current step (*"Checking the current page"*), a progress hairline along the bottom rim, the plane word as a small chip, and a round stop button. The Ask bar *becomes* this capsule; there are not two. | H1 | H3 (tap or drag up) |
| **H1 Notification only** | Nothing on screen. The live notification carries the task (§2.5); a small Cyclone mark stays in the status bar. | — | H2 (tap the notification's *Show*, or the status bar chip) |
| **H0 Idle** | No task: the idle halo, as today. | — | Ask bar |

- **Needing the owner** (an approval, a question, a secret) always rises to H3 with the owner card, from any height,
  with a soft haptic. This is the one exception, and it is also the approval boundary.
- **When the task ends** at H2 or H1, the island shows *Done* or the answer's first line for 3 s and then goes to H0.
  The notification updates to its final state.
- The height is remembered for the task, not for ever: the next task opens at H3.

### 2.4 Motion (the part people feel)

All motion uses one spring (damping 0.86, stiffness 380, about 350 ms) and follows the finger. A drag can be let go
at any point and the stack settles to the nearest height.
- **H3 → H2:** the card's content fades up (120 ms). The card shrinks *into* the Ask bar's outline: width stays, height
  and corner radius interpolate. The plane pill slides into the island as its chip. The Ask bar's text cross-fades
  into the live step. It reads as one object folding into itself.
- **H2 → H1:** the island slides down 24 dp and fades as the notification posts (the notification's live update is
  already current, so nothing jumps).
- **Switching to the background (the owner's big moment):**
  1. Tap **Screen**. The pill says **Moving** and gives a light haptic.
  2. Cyclone takes one frame of the app, via the accessibility screenshot the executor already uses
     (`takeScreenshot`), and shows it as
     a full-screen glass layer in the overlay.
  3. Behind that layer the switch runs: adopt the task into the background display, then **go to the home screen**
     (`guidedHome()`, `GLOBAL_ACTION_HOME`).
  4. The frame then **shrinks into the pill** (scale plus corner radius towards the pill, 420 ms spring) while the
     real home screen is revealed underneath. The stack settles to **H2** with the word **Background**.

  The owner sees the app fly into the pill and lands on their home screen, with Cyclone's island still working.
  - **If the switch fails,** the frame springs back open, disappears, and the outcome line says why. The app never
    moves.
- **Show on my screen** is the reverse: the pill grows into a frame of the background app (the glimpse) that expands
  to full screen as the task is handed back to display 0, then fades. The stack goes to H3.
- **Reduced motion** (the system setting): cross-fades only, same timings.

### 2.5 The live notification (H1)

The task notification becomes an Android 16 **Live Update** on the Pixel: a promoted ongoing notification with
`Notification.ProgressStyle`.
- **Contents:** the step text, the progress, and a short status-bar chip (*"Cyclone · 3/7"*, or *"Waiting"*).
- **Actions** (all through Task Kit, as now): **Show**, **Stop**, and the plane action (*Background* / *Screen* /
  *Start now*).
- **Fallback:** on Android 15 and older it stays the current ongoing notification, with the same text and actions.
- It needs `POST_PROMOTED_NOTIFICATIONS` and a check of `canPostPromotedNotifications()`. Without it, the ordinary
  ongoing notification is used; nothing breaks.

---

## 3. Making the switch actually work (O4, O5)

This is engineering, not paint, and it ships in the same update.

1. **See the real reason first (P0).**
   - A `planes.journal` gateway op returns the last 20 switch records: from, to, outcome, reason, timings and the
     package.
   - Glass → Phone gets a *Background switches* list.
   - The owner taps the switch once on the Pixel and the reason is on the PC.
   - No app content or typed text is in the journal; it already stores only these fields.
2. **Never silent.** Every `Refused` or `RolledBack` publishes a `PlaneUi.outcome` that the pill shows inline (§2.2).
   A `blocker` (background not ready) shows the one fix button from alpha.42 (*Resume* / *Set up*) right there.
3. **Go home after a committed move.** `AndroidPlanePort.move` (Screen → Background) sends the owner to the home
   screen once the adopt is verified. The chrome receives a `PlaneMoved(BACKGROUND)` event and settles to H2 (O5).
   Moving back goes to H3.
4. **Gate timing.** An owner tap may arrive in the middle of a long model call.
   - `pause()` then waits `PAUSE_TIMEOUT_MS` and gives up.
   - Instead, the tap is *queued*: the pill says **Moving** at once, and the switch runs at the next step boundary
     (up to 20 s).
   - A second tap cancels the queued move.
5. **The adopt itself on Android 16.**
   - If the journal shows the adopt failing (`move-stack` refused, or `The app did not stay on the background
     screen`), add the fallback that alpha.42 already uses for Recents: `launch` with `FLAG_ACTIVITY_LAUNCH_ADJACENT`
     semantics on the virtual display, restoring the page through the app's own task where possible.
   - Otherwise, record `SWITCH_FAILED` for the app version so Automatic stops trying.
   - Decided by the P0 evidence, not guessed.

---

## 4. Build list

| # | Work | Files |
|---|---|---|
| R0 | `planes.journal` op, Glass *Background switches* list, gateway test | `PhoneToolExecutor.kt` (diagnostic read), `runtime/plane/PlaneSwitch.kt`, `apps/device-gateway/...`, `apps/glass/src/pages/phonePage.ts` |
| R1 | `CycloneGlassTokens`; the soft button; retire `CycloneConversationPanel`'s own rim on overlays | `ui/v32/CycloneSignatureGlass.kt`, `ui/overlay/OverlayAppleLiquidComposer.kt`, new `ui/overlay/CycloneGlassTokens.kt` |
| R2 | The stack: plane pill as third capsule with one word, 10/14 dp gaps, grabber inside the card, outcome line | `ui/overlay/PlanePill.kt`, `ui/overlay/OverlayChrome.kt` (`ComposerPanel`), `ui/overlay/SignatureOverlayDrawer.kt` |
| R3 | Four heights: `OverlayHeight {EXPANDED, ISLAND, NOTIFICATION, IDLE}` in the chrome machine; the island composable; drag between heights; owner moments rise to H3 | `ui/overlay/OverlayChromeMachine.kt`, `OverlayChromeState.kt`, `SignatureOverlayDrawer.kt`, new `ui/overlay/WorkIsland.kt` |
| R4 | Switch motion: frame capture, fly-into-pill, reverse; reduced-motion path | new `ui/overlay/PlaneTransition.kt`, `runtime/plane/MissionPlanes.kt` (events) |
| R5 | Switch fixes: never silent, go home, queued owner tap, adopt fallback per R0 evidence | `runtime/plane/MissionPlanes.kt`, `runtime/plane/PlaneSwitch.kt`, `runtime/workspaces/WorkspaceRuntime.kt` |
| R6 | Live Update notification with ProgressStyle and a status chip; fallback below Android 16 | `task/TaskNotificationProjection.kt`, `TaskProgressNotification.kt`, `AndroidManifest.xml` |
| R7 | Restyle the owner/approval card, secrets card, status pills and tools sheet on the tokens | `ui/v32/CycloneOwnerCard.kt`, `secrets/SecretsCardOverlay.kt`, `ui/overlay/OverlayToolsSheet.kt` |
| R8 | Tests, guards, versions (5.0.0-alpha.42.dev2 / 185, PC and Glass bumps only if R0 ships there), release notes, release | tests below; `release/version.toml`, `apps/mobile/app/build.gradle.kts` |

**Unchanged:**
- Every button still goes through Task Kit (the guard stays).
- Approvals still stop the task and rise to H3.
- `PhoneToolExecutor` stays the only mutation engine; going home uses the existing
  `guidedHome()` (`GLOBAL_ACTION_HOME`), the owner's own navigation.
- No secrets reach the journal or the notification.

---

## 5. Tests and acceptance

**Unit tests (pure models):**
- `PlanePillModel` words for every state.
- `OverlayHeight` transitions: drag down and up, an owner moment rising to H3, a task ending at H2 or H1, the next
  task opening at H3.
- Outcome-line text for refused, rolled-back and blocker outcomes.
- The queued owner switch: queued, cancelled by a second tap, runs at the boundary, expires at 20 s.
- The Live Update projection: chip text, actions, the fallback.
- `CycloneGlassTokens` used by every overlay surface: a source guard like the Task Kit guard. No overlay file may call
  `RoundedCornerShape` with its own rim or `border(` outside the tokens.

**Rendered check:** Compose screenshot tests (Paparazzi-style, JVM) of H3, H2, the pill's five words, the owner card
and the outcome line, in light and dark. They are attached to the release notes so the owner can see the design
before installing.

**Pixel acceptance (the owner's phone), stated honestly as unverified until done:**
- Tap **Screen** in WhatsApp → home screen within 1.5 s, island working, 10/10. Or the outcome line shows the real
  reason, 10/10.
- **Background** → WhatsApp back on screen, on the same page, 10/10.
- Drag H3 → H2 → H1 → back, with no jumps, at 60 fps (checked with frame metrics from the gateway's `debug.snapshot`).
- The Live Update shows on the lock screen and in the status bar chip.

---

## 6. Order and size

1. R0 and R5 first: they make the switch work and tell the owner why when it does not.
2. Then R1 → R2 → R3 → R4 → R6 → R7.

About one focused build day. The risk is R4's frame hand-off timing on Android 16; if it stutters, it falls back to
the reduced-motion cross-fade for the release and gets tuned in alpha.43.
