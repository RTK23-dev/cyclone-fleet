# 28 — Background that stays working (alpha.44)

**Status:** built in **alpha.44** (2026-09-26). Physical Pixel acceptance is still owed.

**The owner's ask:** "Get back to what we were working on. I want a fully functional, stable background work
release."

Plans 25 and 26 built the background engine: planes, the switch, the pill, Automatic start, Recents adoption, leases,
tier 0 and the Lab planes suite. Plan 26's first step, device evidence (A42-0), was never done, and the owner reported
that switching "still doesn't work" on the Pixel. Alpha.44 therefore does two things:
- **Audit and fix:** read the whole background path again, from the Shizuku service to the Mind's tool hooks, and fix
  what makes a background task stop for reasons that have nothing to do with the task.
- **Evidence:** give the phone a one-tap Background Check, so the next failure names its exact step instead of "does
  nothing".

Parallel sessions move to alpha.48 and Drive to alpha.49–50 (plans 29, 30 and 31 took alpha.45–47).

---

## 1. What the audit found

Every item below comes from the code, not from memory.

| # | Defect | Where | Effect on the owner |
|---|---|---|---|
| S1 | **Any interrupt killed every background screen.** `onInterrupt()` called `WorkspaceRuntime.invalidateAll()`. Android calls `onInterrupt` whenever any app asks accessibility feedback to stop (speech), not only when the service goes away. | `CycloneAccessibilityService.onInterrupt` | Background tasks died at random, with the app closed and the task sent to the screen or stopped. |
| S2 | **A lock left the background screen paused for good.** The touch check paused the session when the phone was locked; nothing resumed it after the unlock. | `WorkspaceRuntime.authorizeTouchLocked` | After the phone went to sleep once, every later action failed ("input authority expired") until the loop breaker ended the task. |
| S3 | **Approvals in the background could never complete.** The background executor checked its own consent store, which the Mind's approval card never fills, then paused the session. The approved retry found the screen paused. | `PhoneToolExecutor.executeWorkspace` (tap and sideways swipe) | "Send", "Pay" and "Delete" in the background always failed after the owner approved, and the task was stuck. |
| S4 | **Every background failure moved the task to the screen.** All background errors carried the same text ("could not complete in its current scope"), and the planes hook moved the task to the screen on that text. | `PhoneToolExecutor` error text; `MissionPlanes.after` | A page that changed under a tap, or a gesture that missed, pulled the whole task onto the owner's screen: background felt like it "does not stay". |
| S5 | **Apps with more than one task broke the session.** Ownership required exactly one task on the background screen and none on the main screen, even an invisible one in Recents. | `WorkspaceUserService.requireTask` | Chrome, Gmail and Docs (a compose window, a second document or a leftover Recents task) failed every status check; the watchdog kept rebuilding the screen. |
| S6 | **Another app on top ended observation.** A permission dialog, share sheet or sign-in page opened by the app has a different package, and observation refused it. | `observeDisplay`, `WorkspaceRuntime.observe` | The Mind could not see the dialog it had to answer, and the task looped. |
| S7 | **A background pause made the Mind wait for the owner.** A paused background screen read as "the owner has the phone"; the Mind then waited for a hand-back that would never come. | `CycloneAgentEnvironment.readinessFailure` | Minutes of waiting, then a failed step. |
| S8 | **No second route for gestures.** When an Accessibility gesture was not delivered to the private display, the action failed. | `PhoneToolExecutor` workspace gestures | On phones that do not route Accessibility gestures to private displays, background could read but never act. |
| S9 | **No evidence.** Nothing on the phone could say which layer failed: helper, service, display, frames, Accessibility or gestures. | — | "It doesn't work" with no next step. |

## 2. What alpha.44 changes

| # | Fix |
|---|---|
| S1 | `onInterrupt` does nothing to background screens. Only `onDestroy` (the service really going away) ends them. |
| S2 | A locked phone refuses the action (`SCREEN_LOCKED`) and changes nothing. The mission already waits for the unlock before its next step (`MindDevice.blocker`), then continues on the same background screen. |
| S3 | For a Mind mission's background screen, approvals use exactly the main screen's flow: the overlay approval card, then a one-shot grant for that action on that control (`workspaceGate`). The background screen stays as it is while the owner decides. The older workspace-task path keeps its own confirmation. |
| S4 | Background failures keep their reason code (never typed text): "Background screen: STALE_OBSERVATION: …". A pure classifier (`BackgroundFailure`) decides what it means. **Only `UNSUPPORTED` moves the task to the screen.** The owner opening the app is the watchdog's to yield, a broken screen is the watchdog's to repair, a lock is waited out, and an ordinary miss is looked at again. |
| S5 | Tasks now carry `visible`, parsed from `am stack list`; unknown counts as visible (fail closed). Ownership (`WorkspaceCommands.ownedTask`) fails only when the app is **visible** on the main screen (the owner opened it). Tasks in Recents do not count. Several tasks on Cyclone's own screen are all Cyclone's, and the top one is used. Moving off the main screen takes the visible task first (`mainTask`). |
| S6 | The display is Cyclone's alone, so its top application window leads the snapshot, whichever app it belongs to. Approvals still apply to every tap there. |
| S7 | A Mind mission's background screen that is paused while no switch or wait runs is taken back (`WorkspaceRuntime.reclaim`), in the environment and in the planes hook. A screen handed to the owner is never taken back this way. |
| S8 | When Android **never queued** an Accessibility gesture, the same tap, long press or swipe goes once through the helper's display-bound input (`input -d <display>`). A cancelled or timed-out gesture may have reached the app, so it is never repeated. |
| S9 | **The Background Check:** see §3. |

## 3. The Background Check

**Where:** Settings → AI → Background work → **Check**. It also runs by itself once after each install or update,
when background is set up and no mission runs.

**How it works:** it opens a harmless app (Settings, else Clock or Calculator, one that is not open on the owner's
phone) on a hidden background screen and runs every layer once, stopping at the first failure:

| Step | Proves |
|---|---|
| Android 15 or later | Private background screens exist |
| Shizuku helper running and allowed | The privileged helper answers |
| Cyclone phone control on | Accessibility is connected |
| Background service starts | The Shizuku user service binds |
| Background screen opens with an app | A trusted private display is created and the app launches on it |
| Background screen shows the app | Frames arrive |
| Cyclone can read the app there | Accessibility sees the private display |
| Cyclone can scroll the app there | A gesture reaches it (Accessibility, else the helper), and the list moves |
| Your screen stays yours | The app stayed behind the owner's screen |
| Background screen closes cleanly | Nothing is left behind |

**What the owner sees:** each step with ✓, ✗ or –. A failed step shows what Android answered and one next step.

**How the result is used:**
- A failure in the engine itself (service, screen, frames, read, act, isolation, close), on this build and within
  7 days, makes Automatic work on the screen and say why.
- Setup failures stay with the capability, which already names the fix.
- The result is kept in `Cyclone Brain/Planes/background-check.json`. It holds step results only, no screen content.

## 4. Proof

- **Unit tests** (`BackgroundStableTest`):
  - task visibility parsing;
  - several tasks on the background screen;
  - Recents leftovers;
  - the owner opening the app;
  - `TASK_GONE`;
  - the failure classifier;
  - the check report (first failure, stored and loaded, blocking rules).
- **CI guard** (`scripts/ci/tests/test_mobile_background_stable.py`): interrupt, lock, approval without a pause, reason
  text, classifier in the planes hook, no repeated gestures, and the check deciding Automatic.
- **Physical Pixel:** UNVERIFIED. The owner's acceptance steps are in the release notes: run the check, then send a
  WhatsApp message in the background with an approval.

## 5. Honest limits

- **The phone must stay unlocked.** Background work still pauses while the phone is locked and continues after the
  unlock. Working with the screen off needs an always-unlocked private display; that comes with a later release,
  behind the check.
- **After a restart, Shizuku must be started again.** Without the PC's *Keep background work on* (plan 26), that is
  one tap in Shizuku.
- **Setup and fixes are not evidence.** The check proves the engine on this phone. It does not prove that every app
  behaves; the per-app memory still learns that.

## 6. Next

| Release | Contents |
|---|---|
| alpha.45 | Direct first (plan 29). |
| alpha.46 | Setup cards (plan 30). |
| alpha.47 | Web-only PC (plan 31). |
| alpha.48 | Parallel sessions (was alpha.44 in plan 26 §6). |
| alpha.49–50 | Drive (plan 24). |
