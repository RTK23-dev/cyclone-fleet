# Cyclone V5 Alpha 65: tasks at the same time

Developer alpha for owner testing. It builds on Alpha 64 (the App Manual's abilities and navigator) and includes it.
- **Mobile:** `5.0.0-alpha.65.dev1` (version code 210).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.65.dev1.exe` (runtime `5.0.0-alpha.65.dev1`).
- **Glass:** `1.0.0-alpha.39` (unchanged).

Until now a second task while one was running waited as "Runs next". Now it can start right away, **behind your
screen**, on a background screen of its own, while the first keeps going.

## What changed

**1. One task in front, up to two behind it.**
- **The front task:** it has your screen, the overlay, the pill and the task card, as before.
- **Tasks behind it:** each works on a background screen of its own with its own app.
  - They never see or touch your screen, not even to begin.
  - You keep using your phone.
- **How many:** 2 behind on phones with 8 GB of memory or more, 1 from 6 GB, none below.
- **When it starts behind:** ask for a clearly separate task while one runs ("also order oat milk") and it starts
  behind when the phone can. Otherwise it waits as "Runs next", as before.
- **Settings → AI → Tasks at the same time** turns it off.

**2. A task behind the screen that needs your screen waits its turn.**
- **What counts as needing your screen:** a sign-in, a camera, a protected screen, an app that must run on your
  screen, or handing you the phone.
- **What happens:** the task waits and says so on its notification ("Waiting for your screen").
- **When the front task ends:** the oldest task behind comes to the front (a task that already has your screen goes
  first). Its card moves to the overlay and the task notification, and it goes on where it was.
- **Nothing is taken from you** without the usual prompt.

**3. One question at a time, clearly named.**
- **One queue:** all tasks share one list of questions and approvals. You see them one at a time, oldest first.
- **Named:** a question from a task behind the screen starts with its name: *For “Order oat milk”: Cyclone wants to:
  tap Pay*.
- **Answer from anywhere:** the overlay card, the task's own notification (Approve / Decline) or Glass. The answer goes
  to the task that asked.

**4. A notification per task behind the screen.**
- **Shows:** what the task does now, "needs you" when it waits for you, and how it ended.
- **Stop:** its Stop button stops only that task.
- **Task Kit:** every button goes through it, like every task button in Cyclone.
- **On the phone:** the chat page lists them under **Behind your screen**, with Stop.

**5. One Cyclone task per app.** Two tasks never work in the same app at once. The second waits until the first leaves
the app ("Waiting for WhatsApp: another Cyclone task uses it"), and the front task never pulls an app off a background
screen.

**6. The Command Center sends a phone up to three tasks.**
- **Idle phones first:** a phone with no task is still preferred.
- **Busy phones:** a phone already working can take the next task behind its current one.
- **The phone decides:** when it cannot take one, the task waits for the next try, as before.
- **Accounts:** the one-phone-per-account lock is unchanged.

## Safety

- **The owner's screen is theirs.** A task behind it cannot read, tap, type, scroll, open links or settings, or go Home
  there. With no screen of its own it may only open its app (on its own background screen) or set a timer or alarm.
  (CI-guarded)
- **It never takes the phone's controller,** and only the front task moves the pill. (CI-guarded)
- **Approvals are unchanged.** Pay, send, delete, permission and sign-in still ask, whichever task asks. The "Share
  needs your OK" gate of a posting task now stays on while any posting task runs, and starting another task can no
  longer turn it off (this was found and fixed during this build). (tested, CI-guarded)
- **Lab runs always work alone.** Nothing starts next to a measurement. (CI-guarded)
- **Stop and every answer go through Task Kit,** for tasks behind the screen too. (CI-guarded)

## Validation and limits

Tests that pass:
- **Phone:**
  - `ParallelSessionsTest`, 6 tests:
    - slots by memory;
    - the full admission table (front, behind, queue: memory, setting, background not ready, a task that needs your
      hands, a Lab run, slots full);
    - what a task behind the screen may not do;
    - one inbox with one open request per task, named, answered by id, withdrawn per task;
    - Task Kit reaching tasks behind the front one;
    - the Share gate with several tasks.
  - The Command Center adapter, sealed delivery and Owner Moments tests pass with the new seams.
  - The full phone suite: 2047 tests, 0 failures.
- **Gateway:** the dispatcher sends up to three tasks per phone, keeps the account lock, and asks a phone that said
  "busy" only once per round (2 tests). The full gateway suite passes.
- **CI guards:** a new `test_parallel_sessions_guard.py` (6 guards); all 201 pass.

Limits:
- **Physical: UNVERIFIED.** On the Pixel with background work on:
  1. Start a task, then ask for a second separate one.
  2. Check that it appears under **Behind your screen** with its own notification while you keep using the phone.
  3. Answer its approval from its notification.
  4. Stop it alone.
  5. Let the front task end and see the other come to the front.
- **Needs background work.** A phone without it (below Android 15, or Shizuku off) runs tasks one after another, as
  before.
- **A task behind the screen does no screen work until it opens an app.** Its first step is always opening its app.
- **Not yet:** the Lab concurrency suite (two measured missions at once) and per-task pills on the overlay. Tasks behind
  the screen show in their notifications and on the chat page for now.
