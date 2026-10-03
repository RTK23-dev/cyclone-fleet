# Cyclone V5 Alpha 51: Command Center C0

Developer alpha for owner testing. It builds on Alpha 50 (Drive: conversations) and includes it.
- **Mobile:** `5.0.0-alpha.51.dev1` (version code 195).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.51.dev1.exe`. The runtime has the Command Center's store and
  job loop.
- **Glass:** `1.0.0-alpha.28`.

This is the first Command Center release (plan 33, **C0: the shell**). One place in Glass for:
- your accounts;
- tasks;
- routines;
- results;
- the questions and approvals of every connected phone.

There is no vault yet (C1). Passwords and codes are still typed on the phone when it asks.

## What changed

**Command Center in Glass.** A new sidebar item, **Command Center**, with five tabs:

| Tab | What it does |
|---|---|
| **Approvals** | Every open question, details request and approval from Command Center tasks, on any phone. A send shows the **exact message**, who it goes to and in which app. **Approve and send**, **Decline**, answer a question (or pick one of its options), or fill in details. |
| **Tasks** | Write what a phone should do, or start from a phone's recipe (**Load recipes**). Pick a phone (or any ready phone), an account, and optionally a start time. The status shows live: scheduled, waiting for a phone, running, needs you, done or failed. **Cancel** stops it on the phone. |
| **Routines** | A task on a schedule: at a time on chosen weekdays, or every N minutes (15 minutes or more). Pick several phones to run it on each one. **Run now**, **Pause**, **Delete** and **Pause all**. |
| **Results** | Every run: phone, outcome, what happened (the phone's own summary), steps, time and cost. |
| **Accounts** | The accounts you own: app or website, name, **whose** it is (mine, my company, client under contract), two-step method, and which phones may use it. **No password field.** |

A row of counts at the top shows: needs you, running, open tasks, routines, done today and failed today.

**How a task runs.**
1. The runtime on your PC checks every 5 seconds for tasks that are due.
2. It picks a phone that is paired and ready, allowed for the task's account, and not busy with another Command
   Center task.
3. It starts the task there as an ordinary Cyclone mission. It is the same Mind with the same GATE, Owner Moments and
   Secrets Card as a task you type on the phone.
4. When the phone reports back, the task is marked done or failed. "Done" is what the phone verified.

When the phone is busy, locked by you, has phone control off, or is offline, the task waits and tries again every
30 seconds. After 6 hours without a phone it fails.

**One phone per account at a time.** While a task uses an account, other tasks for that account wait. The phone that
last succeeded with an account gets its next task.

**Routines never replay.** If the PC was off at the scheduled time, that run is skipped and noted in the audit log.
The next run is always in the future.

## Safety

- **No secret values in the Command Center.**
  - Accounts, tasks and routines hold metadata and goal text only.
  - Anything shaped like a secret (`password: …`, `code=…`, and similar) is refused three times: in Glass, in the
    gateway and on the phone.
  - The store has no secret columns, which a CI guard checks.
- **Approvals stay yours, and exact.**
  - Glass approves only the request the phone showed, named by its request id. If the phone has moved on,
    nothing is approved and the item is withdrawn.
  - A send can be approved in Glass only when every word is shown unredacted. Otherwise Glass says to approve on the
    phone.
  - **Secure input and taking over the phone are never answered from the PC.**
  - Nothing is approved automatically, and nothing times out into an approval.
- **Only you, in Glass.** The Command Center routes use the gateway's loopback bearer. The agent MCP servers (Codex,
  ChatGPT tunnel) do not call them, which a CI guard checks, so an AI cannot approve.
- **Every answer goes through Task Kit** to the mission, like the phone's own buttons.
- **Audit.** Every change (accounts, tasks, routines, starts, finishes, approvals opened and answered) is appended to
  a SHA-256 hash chain in the local store.

## Validation and limits

Tests that pass:
- **Phone** (JVM, `GatewayV5CommandAdapterTest`):
  - a task starts an ordinary mission, and secrets and busy phones are refused;
  - status carries the exact send and, when finished, the summary;
  - only the shown request id can be approved;
  - redacted sends and secure input are answered on the phone only;
  - replies, details, declines and stop go through Task Kit.
- **Gateway** (`test_command_center.py`, 14 tests):
  - accounts hold metadata only;
  - tasks run on a ready phone, and a busy phone means wait, not fail;
  - one phone per account;
  - approvals mirror the phone and are never duplicated;
  - routines fan out to phones and do not replay missed runs;
  - pause all, run now, cancel, idempotent creation;
  - schedules;
  - routes need the bearer;
  - the phone's replies are validated.
  - The full gateway suite passes.
- **Glass:**
  - routes, parsers, the approval card (exact message, approve by id);
  - redacted and secure items, task and routine creation, and accounts with no password field.
  - All 156 Glass tests pass.
  - The page was checked in Chromium against a real gateway: desktop and phone width.
- **CI guard** (`test_command_center_guard.py`):
  - no secret columns;
  - every route authenticated;
  - agent MCP never calls the Command Center;
  - no password fields in Glass;
  - the phone approves only the shown request.

Limits:
- **Physical: UNVERIFIED.** The C0 exit test is one routine running on two phones on schedule for 3 days, with
  results listed and approvals answered from Glass. It has not run yet on real phones and a real PC.
- **One Command Center task per phone at a time.** Tasks you start on the phone yourself are not in the inbox; only
  Command Center tasks are.
- **The runtime must be running.** Routines fire only while `cyclone` is open on the PC.
- **A task is not retried once its mission started.** A task whose phone restarted mid-way ends as failed, with the
  cause, so nothing is done twice.
- **Schedules** are "at a time on weekdays" or "every N minutes"; triggers (a notification, an email) come later.
- **No vault yet (C1).** Next: the zero-knowledge vault in Glass, then C2 sealed delivery of a password to one phone
  for one task.
