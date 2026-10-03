# 38 — Steer, queue, parallel, and plan diversions

**Status:** **built in alpha.68** (2026-09-28; see §8 for what shipped and what moved). Revised with the owner's
decisions the same day (steer targets the task being viewed; diversions always on; the model decides, only serious
cases confirm). Builds on the Mind (16), Task Kit (17), parallel sessions (26 §6) and the mission workspace (37).

**The owner's ask:**
> "Make the goals stay fresh if something new comes up that changes the mission, to a standard where it's reliable for
> billions of users: deciding to divert, the implementation, and a nice visual of a plan diversion in the work panel.
> Combine it with how Cyclone handles new messages in the Ask Cyclone bar while it's working: three options, steer,
> queue, parallel work, in a vertical list when the user taps send with a typed message. An empty bar turns the button
> into pause and a two-tap stop. Steer is the change mid-task from a user."

## 0. Decisions

| # | Decision | Why |
|---|---|---|
| D1 | **The owner chooses; Cyclone suggests.** Sending while a task runs shows the options; the likely one is highlighted, never picked for you. | Today a guess (`MissionQueue.isNewTask`) decides between steering and a new task; a wrong guess changes the wrong task. |
| D2 | **Steer is a diversion by the owner.** It updates the goal (v2, v3…), never asks back, and the plan must follow. | The owner's word is the goal. |
| D3 | **The model decides diversions** (§3), like a capable assistant would: it changes route, app, channel or account on its own and shows it. **Only serious cases confirm**, and they confirm at the action itself: the existing approvals for send, pay, delete, permission, sign-in and posting, which now also say what changed. A diversion never adds a separate stop. | Most decisions belong to the model; the owner is asked only where a mistake can't be undone. |
| D4 | **Every change is a plan version** with its trigger, who decided, whether a serious action was flagged, and why. Nothing is overwritten. | What the panel draws, what the Lab measures and what a support person reads. |
| D5 | **All buttons go through Task Kit**, like every task button. | The existing law (AGENTS.md). |
| D6 | **Diversions always work**, whatever the settings (workspace on or off, Lab or owner missions). Plan versions live in the toolbox; the workspace only adds them to its live state. | Core behaviour, not an experiment. |
| D7 | **Steer goes to the task being viewed**: the task on the overlay card or the one open in the Ask page (a task behind the screen when its panel is open), otherwise the front task. | The owner steers what they are looking at. |

## 1. Today (from the code)

- **Send while working:** `MindMissions.offer` guesses. A "clearly separate task" starts behind the front one when a
  slot is free (26 §6) or waits as "Runs next"; anything else steers (`steer` → `ownerMessages`, read at the next
  turn, or answers the open question).
- **Pause:** `TaskCommand.Pause` hands the phone to the owner (takeover). There is no pause that holds the mission.
- **Stop:** one tap on the overlay's stop.
- **Diversions (alpha.66, workspace only):** the model may call `plan_update(divert=…)`; a status line shows it;
  an approval adds "You asked for X; this is Y". A steer does not refresh the goal or the done checks.

## 2. The Ask bar while Cyclone works

**Empty bar:** the send button becomes two buttons.
- **Pause ⏸** holds the mission at its next step boundary (never mid-action): no model call, no tap. The button
  becomes **Resume ▶**. The screen and app stay as they are; the owner may use the phone. After 30 minutes paused,
  the mission stays paused and its card says so (it never times out into a stop).
- **Stop ■, two taps.** The first tap turns it red with "Tap again to stop" for 3 seconds; the second stops. No dialog.

**Typed text + send:** a vertical list opens above the send button (a sheet on the overlay, a menu in the Ask page):

```
┌──────────────────────────────────────────┐
│ ● Steer        Change this task          │  ← highlighted when the text reads like a change
│ ○ Queue        Do it after this          │
│ ○ Parallel     Do it at the same time    │  ← greyed with the reason when not possible
└──────────────────────────────────────────┘
```

- **Answer** replaces Steer as the first row when the task is waiting on a question ("Answer: Which account?").
- **Parallel** is greyed with its reason from `Crew.admit`: "Needs your screen", "No free slot on this phone",
  "Turn on background work", "A Lab run works alone". Tapping a greyed row shows the reason, never runs it.
- **Highlight, not choice:** `MissionQueue.isNewTask` only picks which row is highlighted (Queue when it reads like a
  new task, Steer otherwise). Tapping outside closes the list and keeps the text.
- **One tap each:** Steer → the task card shows "Changed: …"; Queue → "Next: …"; Parallel → the new task appears under
  **Behind your screen**.

**Task Kit:** new commands `Steer(text)`, `Queue(text)`, `Parallel(text)`, `Resume`; `Pause` becomes the real pause
(the takeover stays as **Take over**). The overlay, the Ask page, notifications and Glass all send these.

## 3. Diversions: when, who decides, how

**Three triggers:**
1. **Steer (the owner):** the goal gets a new version ("Goal v2: … — you, 14:02"), appended to the brief (v1 stays
   readable). The model is told: "The owner changed the task. Update the plan now (plan_update with divert)". Done
   checks are re-derived from the new goal. The next finish is refused once if the plan was not updated.
2. **Blocked:** two surprises on one step, a hard wall (not installed, DMs closed, sign-in, sold out), or a refused
   action. The harness tells the model to divert or ask; the model declares it with `plan_update(divert=…)`.
3. **New facts:** something the model read changes the best route. The model declares it.

**Who decides: the model, with one safety net.**

| The change | What happens |
|---|---|
| Your steer | Applied at once; the model re-plans; never asked back |
| Another route, app, channel or account, or a new fact | The model decides and continues; the plan card shows the change and why |
| A serious action after a diversion (send, pay, delete, permission, sign-in, post) that goes to another recipient, amount or audience than you asked for | The action's existing approval asks, with the change on top: "You asked for Instagram; this goes by WhatsApp because her DMs are closed". Nothing else waits. |

The harness only flags what is serious (a recipient, amount or audience that differs from the goal or its done
checks, the alpha.66 approval check generalised); it never blocks a route, app or channel change.

**The record:** each plan version stores `steps`, `trigger` (steer, blocked, new_facts), `serious` (flagged or not), `decidedBy` (owner,
model, harness), `why`, time and turn. The goal and done checks carry the same version number. The mission record,
Glass and the Lab read it.

## 4. The work panel

The plan card on the overlay and the Ask page:

```
Plan v2 · Changed course
✓ Read Louella's address
✓ Bike time: 22 min
✕ DM on Instagram                     ← struck through, dimmed
└→ Message on WhatsApp                ← branch line, accent
   ⓘ Her Instagram DMs are closed
☐ Confirm it's delivered
                        See v1  ›
```

- A steer shows **You changed this** instead of the reason chip. A serious action waiting for its approval shows
  **Waiting for you** on its step, like any approval.
- **See v1** shows the earlier plan. The island and the task notification show one line: "Changed course: WhatsApp
  instead of Instagram".
- **Glass run inspector:** the same branch on the step timeline, with who decided and any serious action it flagged.

## 5. Measuring it

- **Lab suite `divert`** (6 missions):
  - scripted mid-task steers ("actually send it to Keep instead");
  - blocked routes (an app force-stopped mid-run, a missing app);
  - a diversion that ends in a send to another recipient (the lab declines the approval).
- **Metrics per arm:** diversions per run, decided by the model vs steered, success after a diversion, and serious
  actions after a diversion that went out without approval (must be 0).
- **Guards:**
  - every Ask-bar option and Pause/Resume/Stop goes through Task Kit;
  - a diversion never adds its own stop, and never bypasses the approval of a serious action;
  - diversions run whatever the settings;
  - Parallel is only offered when `Crew.admit` allows it;
  - a steer always creates a goal version.

## 6. Build: alpha.68

- **Phone:**
  - **Diversion core:** `mind/divert/` with `PlanVersions`, `DiversionPolicy` (the serious-change flag), `GoalVersions`.
  - **Mind:** a real pause in `MindLoop` (a hold at the step boundary); steer creates Goal v2 plus a re-plan note;
    `plan_update` versions; blocked detection feeds the divert nudge.
  - **Task Kit:** the new commands.
  - **UI:** the Ask-bar option list (overlay and Ask page), pause/resume and the two-tap stop, the plan card with
    branches, the one-line island, the ask card.
- **Gateway and Glass:** the `divert` suite and metrics; the inspector branch.
- **Tests:**
  - the serious-change flag on approvals;
  - a steer versions the goal and re-plans;
  - pause holds at a boundary and resumes;
  - two-tap stop;
  - Parallel greyed with the right reason;
  - Task Kit routes;
  - plan version persistence and resume.

**Moves:** recipes and traps (37 W4) to alpha.69, fleet health to alpha.70.

## 7. Not in this plan

- Automatic choice without the list (D1).
- Undoing actions already done before a diversion (sent messages stay sent; the plan says so).

## 8. As built (alpha.68)

**Shipped:**
- **Diversion core:** `mind/divert/PlanVersions.kt` holds the goal versions and the plan versions, the display rows
  (dropped, branch, note), the one finish reminder after a steer and the approval note after a model's diversion.
  It lives in the toolbox, so it runs in every mission (D6). A changed number makes a step a different step
  ("5 minutes" → "3 minutes").
- **Mind:** a real pause in `MindLoop` (a hold before the next step; not working time); steers become goal versions
  with a harness note; `plan_update` has `divert` and step `app`/`why` in every run; a repeated failure suggests
  changing course.
- **Missions:** `steerTask`, `queueTask`, `parallelTask`, `parallelBlocker`, `pause`, `unpause`, `askOptions`,
  `viewedTaskId`; plan versions and history (last 5) are saved in the mission record.
- **Task Kit:** `Steer`, `Queue`, `Parallel`, `Unpause`; `Pause` is the real pause for Mind tasks (Take over stays).
- **UI:** the options list (`task/AskWhileWorking.kt`, `ui/v32/CycloneSteerViews.kt`) on the overlay and in the Ask
  page; the overlay button is Pause/Resume with the existing stop gesture; the plan card with the branch and
  "See vN"; behind-screen tasks can be selected as the steered task.
- **Lab:** the `divert` suite (6 missions), `owner.steer` with `afterTurns`, the lab's `steer` answer, and `divert`
  metrics per arm.
- **Guards:** `scripts/ci/tests/test_steer_divert_guard.py`.

**Differs from the plan:**
- There is no separate `DiversionPolicy` flag: the approval of every serious action is unchanged, and after a model's
  diversion it starts with what changed. The alpha.66 recipient check still adds its note in workspace runs.
- The stop gesture is the existing one (tap twice or hold two seconds), not a red "Tap again to stop" state.

**Moved:** done checks re-derived from a steered goal; the Glass inspector branch and Glass charts for the divert
metrics; a dedicated one-line island row (the change shows as the status line).

