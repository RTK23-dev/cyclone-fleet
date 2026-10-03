# Cyclone V5 Alpha 76: Fast mode

Developer alpha for owner testing. It builds on Alpha 75 (profiles: Recently deleted, backups, Cyclone Carry) and
includes it.

Versions:
- **Mobile:** `5.0.0-alpha.76.dev1` (version code 221).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.76.dev1.exe` (runtime `5.0.0-alpha.68.dev1`, unchanged).
- **Glass:** `1.0.0-alpha.40` (unchanged).

Plan 41's **parallel Pilot**. The smart model plans the whole run once. A rapid model carries the plan out, about a
second per move, and makes the low-risk decisions itself. The smart model catches up in the background. Only
irreversible moves ask you. The as-built notes are in `Cyclone V5 plan/41-fast-mode-decisions.md` §14.

## What changed

**Fast mode: Settings → Model & intelligence.** It is off by default. Turn it on to try it.

**How a run goes** (for example "message lo.06 on Instagram"):
1. **The smart model writes the whole plan:** open Instagram, open Direct messages, search, type lo.06, open the chat
   with lo.06, type the message, tap Send. The send is marked irreversible.
2. **The rapid model walks it.** For every move, one quick decision board:
   - does the screen still fit the plan?
   - does this need the smart model?
   - which move is next: a button, typing the planned text, Enter, scroll, Back, opening the step's app or link,
     waiting, or "this step is done"?
3. **The smart model catches up in parallel.**
   - When the run enters a new app, and ahead of every irreversible step, the smart model gets a short question in
     the background: does the rest of the plan still hold?
   - If it changes the plan, the rapid model switches at the next move.
4. **When the screen doesn't match,** the rapid model bumps the smart model. The smart model patches the plan and
   the run carries on, up to 3 times. Otherwise the smart model takes over from the real screen.

**Only irreversible moves ask you.**
- Cyclone's code, not a model, decides what can't be undone: send, pay, delete, post, confirm and similar.
- Such a move happens only when the smart model's plan marked it, and only after its background review is back.
- Then you approve it as always.
- Everything else (opening apps, finding, scrolling, typing the planned text) the rapid model does by itself.

**What never changes:**
- The rapid model only uses the plan's own app, link and text.
- It never fills passwords or codes, and never finishes a task.
- It never acts on a screen with a password, code or card field, or in banking, payment and authenticator apps.

**Choices in Settings:**
- **Fast decisions by:** a fast model (default `google/gemini-3.1-flash-lite`, with a screenshot when the screen's
  text isn't enough), or a decision model (default JEV, text only for now).
- **How sure before acting:** Careful (default), Balanced, Quick.
- **Screenshots when needed.**
- **Smart model checks ahead** (on by default).

## Not yet

- Watch mode, where the rapid model answers next to the smart model to measure itself first.
- The live screen ledger.
- A per-run "rapid vs smart" split on the run card.
- Lab measurements.
- OpenAI's Decisions API itself, which has no public documentation yet.

## Tests

- `PilotTest` (new, 21):
  - the board's yes and no answers;
  - bumps that patch the plan, the smart model taking over, and the bump limit;
  - an unplanned irreversible move, and a planned one waiting for the parallel review;
  - a look-ahead revision arriving mid-run;
  - tool moves, every harness boundary, and the wire formats.
- `PilotToolboxTest` (new, 7): the Pilot inside the real toolbox and act path, including opening an app and refusing
  an unplanned Send.
- Guards: `test_pilot_guard.py` (new); the workspace guard counts the new tool.

## Physical acceptance

UNVERIFIED. Not yet seen on a phone:
- Fast mode on the Instagram message and a settings change;
- a bump on a surprise dialog;
- the approval on Send;
- the time per rapid move with each route.
