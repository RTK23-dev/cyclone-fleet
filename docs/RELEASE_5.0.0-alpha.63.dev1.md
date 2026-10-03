# Cyclone V5 Alpha 63: Driver mode works, and the working status shows the app

Developer alpha for owner testing. It builds on Alpha 62 (an AI project manager in the Command Center) and includes it.
- **Mobile:** `5.0.0-alpha.63.dev1` (version code 208).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.63.dev1.exe` (runtime `5.0.0-alpha.62.dev1`, unchanged).
- **Glass:** `1.0.0-alpha.38` (unchanged).

## Fix 1: Driver mode no longer crashes Cyclone

Switching **Driver mode** on crashed Cyclone entirely. This was reported by the owner, and it has been true since
Drive first shipped in Alpha 49.

The Drive button is a Compose view inside a small touch frame, drawn in its own overlay window. Compose looks for its
lifecycle on the window's root view, which is the touch frame. Only the inner view had it, so the button's first
frame threw an exception, and because the overlay runs in Cyclone's own process, the whole app went down.

The unit tests and CI builds of Alpha 49–53 could not catch this: it happens only when the window is really drawn on
a phone. Everything built on Drive (announcements, the car microphone, readbacks) was therefore never reachable on
a phone until now.

**What changed:**

- **The fix:** the touch frame now carries the lifecycle, view-model and saved-state owners, as Cyclone's other
  overlay windows already did. Driver mode switches on and the button draws.
- **A CI guard** (`test_drive_overlay.py`): every Compose view hosted inside another view in Drive's overlay must give
  its host the owners. The guard fails on the Alpha 53 code and passes now.
- **The microphone service** is now started only when the microphone permission is granted. Before, a first tap
  without the permission could make Android refuse the service after it had started, which also crashes an app.

## Fix 2: the working status shows the app, not Cyclone

While Cyclone works, its status now shows **the logo of the app it is working in**. This is what the Alpha 43 overlay
redesign asked for (plan 27, `docs/design/CYCLONE_TILT_GLASS.md` principle 10):
- "Real app logos (the current app in front) replace generic Cyclone branding wherever a task works in an app."
- "Cyclone's own mark appears only when there is no app (idle, a fresh ask)."

Alpha 43 built this for tasks with a record. Tasks on your screen, the in-app task bars and the live notification still
showed Cyclone.

| Where | Before | Now |
|---|---|---|
| **Overlay island** (the folded working bar), on-screen tasks | Cyclone's logo, always | The app on screen that the task works in |
| **Overlay island**, background tasks | Cyclone's logo until the task reported an app | The app the task works in, as soon as it is known |
| **Overlay working card**, on-screen tasks | Cyclone's logo, always | The apps it worked in, as the design's header: the last three, the current one 34 dp in front with a teal ring |
| **In-app island** | As the overlay | As the overlay |
| **In-app task cards** (the task list, the Ask panel, View progress) | Cyclone's own launcher icon whenever the task was on a Cyclone screen | The app the task last worked in |
| **Live notification** (the picture beside the text) | The task's raw package, which could be Cyclone | The app the task works in. The small status-bar icon stays Cyclone's: Android requires the app's own. |

**Rules:**
- Cyclone itself, the system bars and the home screen never count as "the app". Cyclone keeps the last real app it
  saw.
- Cyclone's mark appears only before any app is known, for example in the first second of a new task.
- Apps are remembered in memory only, as before.

## Also checked: the Driver mode crash pattern

I checked the whole app for the Driver mode crash pattern (a Compose view in its own window, inside a host view
that lacks the lifecycle). **The Drive button was the only case.**
- Cyclone's other overlay windows (the Ask bar, the idle bubble, the screen-share pill and the tools sheet) already
  set it correctly.
- Every other screen is an Android activity, which sets it automatically.
- The remaining overlays (the AI trace and the debug sandbox) use plain Android views, with no Compose.

## Validation and limits

- **Passes:**
  - a JVM test of the app choice (`WorkingAppTest`);
  - a new CI guard (`test_working_app_logo.py`): Cyclone's mark on the glass is only ever the fallback, and no task bar
    or notification uses the raw package again;
  - all CI guards;
  - the Mobile CI build and unit tests.
- **Unchanged, as designed:** the idle bubble keeps Cyclone's mark.
- **Home screen:** Cyclone now asks Android which app is the home screen, so the launcher is never shown. This adds
  one manifest `<queries>` entry, not a permission.
- **Physical status: UNVERIFIED.** The project has no on-device UI tests, so the crash fix is proven by the code path
  and the guard, not yet by a phone.
- **Please test in this order:**
  1. switch Driver mode on;
  2. tap the orb and say "set a timer for one minute";
  3. while Cyclone works in WhatsApp, check that the island shows WhatsApp's logo, not Cyclone's;
  4. only then try announcements and the car microphone.
  If anything still closes Cyclone, say what you tapped last.
