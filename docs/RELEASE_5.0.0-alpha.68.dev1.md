# Cyclone V5 Alpha 68: steer, queue, parallel and plan diversions

Developer alpha for owner testing. It builds on Alpha 67 (memory v2) and includes it.
- **Mobile:** `5.0.0-alpha.68.dev1` (version code 213).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.68.dev1.exe` (runtime `5.0.0-alpha.68.dev1`).
- **Glass:** `1.0.0-alpha.40` (unchanged).

While Cyclone works, you decide what a new message is: a change to this task, the next task, or a task at the same
time. Cyclone changes course on its own when a route is blocked, shows you what changed, and still asks before
anything serious. Plan 38.

## What changed

**1. The Ask bar while Cyclone works.**
- **Typed text + send** opens a short list above the bar (overlay and Ask page):
  - **Steer:** change this task.
  - **Queue:** do it after this.
  - **Parallel:** do it at the same time, behind your screen.
- **Answer** replaces Steer when the task is waiting on a question.
- **Parallel is greyed** when it can't start now, with the reason ("Needs your screen", "No free slot on this
  phone", "A Lab run works alone"…). Tapping it shows the reason; it never runs.
- **A hint, never a choice:** Cyclone highlights the likely row (Queue when it reads like a new task, otherwise
  Steer). Nothing happens until you tap one. Tapping the text field closes the list and keeps your text.
- **The task you are viewing:** on the overlay, the task on the card. In the Ask page, tap a task under
  **Behind your screen** to steer that one; tap it again to go back to the front task.
- **Empty bar:** the button is **Pause / Resume**, with the existing stop gesture (tap twice, or hold two seconds).

**2. A real pause.**
- **Pause holds at the next step, never mid-action:** no AI call, no tap. The screen and app stay as they are, and you
  can use the phone.
- **Not working time:** a paused task never runs out of time; it stays paused until you tap **Resume** or **Stop**.
- **Take over is separate:** it still hands you the phone.
- The Ask page's mission card also has **Pause / Resume**.

**3. Steer changes the goal.**
- **Goal versions:** a steer becomes goal v2 (v3…) and the AI is told to re-plan right away. Your earlier words stay
  readable.
- **Answers first:** if the task is waiting on a question, the steer answers it.
- **One reminder:** if the AI tries to finish without following your change, the first "done" gets one reminder. A
  second "done" is always accepted.

**4. Cyclone changes course on its own, and shows it.**
- **The AI decides:** when a step is blocked (an app that isn't there, DMs closed, a repeated failure) it changes
  route, app or channel and says why (`plan_update` with `divert`).
- **Every change is a plan version:** it records what triggered it (your steer, a declared change, or dropped steps),
  who decided and why.
- **Always on:** plan versions work in every mission, with or without the workspace setting.
- **Only serious actions confirm,** at their existing approval: send, pay, delete, grant, sign in, post. After a
  change the AI made, that approval starts with what changed: `Changed course: "Instagram DM" → "WhatsApp" (her DMs
  are closed).` A change never adds a stop of its own.

**5. The branch on the plan card.**

```
Plan v2 · Changed course
✓ Read Louella's address
✕ DM her on Instagram            (struck through, dimmed)
└→ Message her on WhatsApp       (accent)
   ⓘ Her Instagram DMs are closed
○ Confirm it's delivered
                        See v1  ›
```

- **Ask page:** the full plan with the branch; **See v1** opens the earlier plan.
- **Overlay:** under the work card, only the change (header, struck step, new step). Nothing shows until the plan
  changes.
- **After your steer,** the label is **You changed this**. The status line says "You changed the task: …" and
  "Plan updated for your change".

**6. Lab: the `divert` suite** (6 missions).
- **Mid-task steers:** another note text, another search, another timer length. The lab sends the steer after a
  scripted number of turns, the same Steer as the Ask bar.
- **Blocked routes:** an app that isn't installed (Opera, Calculator Plus).
- **A diverted send:** Outlook isn't there, and the Gmail send must still wait for approval (the lab declines).
- **Metrics per arm:**
  - diversions per run;
  - steered vs decided by the AI;
  - success after a diversion;
  - serious actions after a diversion that went out without approval (must be 0).

## Safety and privacy

- **Every choice is a Task Kit command:** Steer, Queue, Parallel, Pause and Resume. No surface reaches a mission
  directly. (CI-guarded)
- **A diversion never adds a stop, never skips an approval,** and never asks you anything on its own. (tested, CI-guarded)
- **Parallel only when the phone can:** Parallel is offered only when the crew rules allow it. (CI-guarded)
- **The lab can steer but never approve:** a lab steer is refused if it contains a secret. (tested)
- **Nothing new is stored beyond the mission record:** the plan versions and your steer text sit in the mission's own
  record, like the goal. They are not sent to memory.

## Validation and limits

Tests that pass:
- **Phone:**
  - `PlanVersionsTest` (8 tests):
    - the first plan and its progress are not a change;
    - a reworded step is not a change, but a changed number ("5 minutes" → "3 minutes") is;
    - a declared change draws the struck step right above the branch, with its reason;
    - dropping unfinished steps is a change even when the AI doesn't say so;
    - a steer versions the goal and reminds once before finishing;
    - after a steer, the label is "You changed this" and there is no approval note;
    - struck steps stay visible while the plan moves on;
    - a change declared in the very first plan still counts.
  - `AskWhileWorkingTest` (5 tests): the row order, the hint, Answer for an open question, greyed Parallel with its
    reason, and every choice as a Task Kit command.
  - `MindLoopTest` (+3): a steer becomes goal v2 at the next step; a pause holds with no AI call and isn't working
    time; Stop while paused ends it.
  - `PhoneMindToolboxTest` (+2): a declared change becomes plan v2 with the branch; a steer that is never planned
    for gets one finish reminder.
  - `TaskKitTest`: Steer, Queue and Parallel only parse with text.
  - The full phone suite: 2096 tests, 0 failures.
- **Gateway:** `test_lab.py` (+4): the divert suite and its validation, the lab steering once after the scripted
  turn, diversion metrics per arm, and the contract refusing a steer with a secret.
- **MCP and Glass:** unchanged; their suites pass.
- **CI guards:** a new `test_steer_divert_guard.py` (7 guards); all 222 pass.

Limits:
- **Physical: UNVERIFIED.** On the Pixel:
  1. "Set a timer for 5 minutes." While it works, type "make it 3 minutes" and send. Pick **Steer**; the card should
     show "Plan v2 · You changed this" and the timer should be 3 minutes.
  2. While a task runs, send "what's the weather tomorrow" and pick **Queue**. It should run after.
  3. With an empty bar, tap **Pause**, use the phone, then **Resume**.
  4. "Search for cyclone tides in the Opera browser" (without Opera): it should switch browsers and show the branch.
- **Not in this release:**
  - done checks re-derived from a steered goal;
  - the branch in the Glass run inspector, and Glass charts for the divert metrics (the Lab API returns them);
  - the "Tap again to stop" red state (the stop gesture is the existing one: tap twice or hold);
  - the island and notification show the change as a status line, not a dedicated one-line row;
  - recipes and traps from past runs (plan 37 W4) move to alpha.69; fleet health to alpha.70.
