# Cyclone V5 Alpha 66: the mission workspace

Developer alpha for owner testing. It builds on Alpha 65 (tasks at the same time) and includes it.
- **Mobile:** `5.0.0-alpha.66.dev1` (version code 211).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.66.dev1.exe` (runtime `5.0.0-alpha.66.dev1`).
- **Glass:** `1.0.0-alpha.40`.

A Cyclone run used to be one long conversation: every screen stayed in full until it grew very large, the app's map
was shown once and never again, and the plan and collected facts were buried in old messages. The **mission
workspace** (plan 37) keeps the bookkeeping for the AI so it can think about the task, not dig for it. It is
**guidance, not gates**: no new tools, no new refusals, nothing thrown away.

**Try it:** Settings → AI → **Mission workspace (new)**. It is off by default while the Lab measures it.

## What changed

**1. A live state at the end of every call (never stored).**
- **Shows:** the plan with the current step, what was collected (with the app and turn it came from), surprises,
  where the mission is and the way back, and the time used.
- **Light missions stay light:** a short single-app task (a timer, a toggle, a question) gets no live state at all
  and looks like a normal run.
- **The screen is the truth:** the rules say so; the live state is help, not orders.
- **Caching:** it is sent after the conversation and never stored in it, so the stored conversation only grows and
  the provider can reuse it.

**2. App stays and a journal.**
- **A stay** is the time a mission spends in one app. When it moves to another app, the old app's screens fold to
  one line each and a **JOURNAL** block keeps what it did, what it got, where it left the app and anything that
  surprised it.
- **Nothing is lost:** folded screens keep their full text; `recall` with `turn` or `stay` shows them again.
- **Not fooled by pop-ups:** share sheets, photo pickers, permission and sign-in sheets, the keyboard and in-app
  browser tabs never start a new stay. Another app must stay in front for two looks unless the mission went there on
  purpose, and a quick return is marked as coming back.
- **Long stays** fold in batches (the last three turns always stay in full), so the context stays small without
  changing every turn.

**3. The app's section on every visit.** The map, dictionary words and manual lines of an app come back each time
the mission arrives in it, not only the first time. The manual lines follow the plan step the mission is on.

**4. "What changed" and optional checks.**
- **Changed:** under each new screen, a few lines say what is new (a title, a switch that flipped, a field that
  filled, lines that appeared or went).
- **Check:** an action may say what it expects (optional). Cyclone checks it without a model call: ✓ held, ✗ missed
  (only when it is sure, and handles match exactly: `lo.06` is not `lo.06_official`), ~ nothing changed.
- **Advice, not rules:** two surprises on the same step give a note to consider another route; the model decides.

**5. Plan, done checks and diversions.**
- **Plan steps** may name their app and why (optional).
- **Done checks** (optional, with the plan): how the mission will know it is done. `task_finish` looks for them
  across the whole mission (every screen seen, collected facts, approved sends, the summary). If one is nowhere, the
  first finish gets one note; a second finish is always accepted and recorded as unverified.
- **Diversions:** when the AI changes course it can say so; you see "Mission diverted: DM on Instagram → WhatsApp —
  her DMs are closed" on the task card and in the mission's events.
- **At an approval:** when you named a recipient and the chat on screen is another one, the approval card says so
  ("You asked for lo.06; this is "lo.06_official""). No extra stop: it rides on the approval you already get.

**6. Narration.** The task card reads "Step 2 of 4 · Bike time — Tapped Bike" instead of only the raw action.

**7. Measured in the Lab.**
- **A `context` knob** on Lab arms: `classic` or `workspace` (Glass: "Mission workspace" toggle per arm). Lab runs
  without it stay classic, so older experiments measure the same thing.
- **New suites:** `multiapp` (6 missions that carry a value from one app to another, including one that leaves an app
  and comes back) and `long` (5 missions of many steps).
- **New metrics per arm:** how often a checked step went as expected, app stays per run, diversions, unverified done
  checks and prompt tokens.

## Safety

- **No new tools, no new gates.** The toolbox stays at 39 tools; the workspace only adds optional arguments and never
  refuses an action. A model that ignores it works exactly as before. (CI-guarded)
- **Approvals are unchanged.** Pay, send, delete, permission and sign-in still ask; a diversion only adds a line to
  the approval card. (CI-guarded)
- **Secrets are never collected:** a note that looks like a password, code, key or card number is refused; password
  fields are never read; the journaled workspace passes the same redaction as the mission journal; the lines of every
  screen seen are kept in memory only. (CI-guarded)
- **It can't stop a mission:** every workspace step in the loop is guarded; a failure is reported once and that turn
  runs as a classic one. (CI-guarded)

## Validation and limits

Tests that pass:
- **Phone:**
  - `MissionWorkspaceTest` (11): pop-ups and hysteresis, folding with the journal block and recall, the stored prefix
    only growing, batch folding of long stays, the live state and its light mode, conservative checks, surprises and
    advice, what changed, done checks across the mission, the approval line, resume.
  - `WorkspaceFlexibilityTest` (7): no new tools or required arguments; a model that ignores the workspace finishes as
    before; checks and changes under the screen; switching apps and `resume`; the done nudge happens once; a
    diversion never stops the mission; the loop sends the live state last, never stores it, and `recall` unfolds a
    folded turn.
  - The full phone suite: 2065 tests, 0 failures.
- **Gateway:** the `context` knob, the `multiapp` and `long` suites, and the workspace metrics per arm (3 tests). The
  full gateway suite passes.
- **Glass:** the Lab knob and arm line; 225 tests pass; the Glass guard passes.
- **CI guards:** a new `test_mission_workspace_guard.py` (8 guards).

Limits:
- **Physical: UNVERIFIED.** On the Pixel, with Mission workspace on:
  1. "Work out 12 times 12 in the calculator, put it in a Keep note, then go back and add 1": look for the journal
     block when it leaves the calculator and the plan steps on the task card.
  2. Ask a message task naming the person and watch the approval card.
  3. Run a Lab experiment `classic` vs `workspace` on `multiapp`, `long` and `core`.
- **Off by default.** It becomes the default only when the Lab shows it wins (plan 37 §11).
- **Not in this release (plan 37 W3–W4):** People & notes, recipes and traps from past runs, prefetching the next
  app's section, provider cache breakpoints, a harness-drafted done check, and the app-stays timeline in Glass's run
  inspector.
