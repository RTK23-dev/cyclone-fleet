# Plan 46: Skill Studio, see and shape skills like a flow

Status: research and design (2026-10-01). Nothing here is built yet. Visual explainer: the "Cyclone Skill Studio"
artifact. Builds on plan 45 (Run Ports), plan 43 (Sign-up Maps), Stage 3 (Skill Compiler) and plan 33 (vault).

## 0. What a skill is today

A **skill capsule** (`automation/skill/SkillCapsuleModels.kt`) has:
- an app, a goal and a start page (`whenPage`);
- steps, each a `when / then / check` with:
  - an action;
  - ranked selectors with confidence;
  - verifiers (the page after, a text, a control gone);
  - params;
- evidence (the traces it was compiled from);
- a status: **Draft → Review → Verified**, or **Quarantined**.

Rule (`SkillPromotion`): workers and the PC can't flip a capsule to Verified; a person on the phone does.

The owner can't see a skill as a whole, change one step, add a guard or swap a fragile AI step for a dependable tool.

## 1. The Studio (Glass)

A canvas like n8n. A skill is a flow of nodes, left to right.
- **Palette (left):** node types to drag in.
- **Canvas:** nodes, edges, branches. Selecting a node opens its **inspector (right)**: selectors and their
  confidence, the check, timeouts, retries, where a failure goes, and privacy.
- **Run overlay:** the last runs painted on the flow. Each node shows its success rate and median time, and the edge
  where most runs fail is marked.
- **Versions:** every save is a new version with a diff. Publishing follows the capsule rule (§4).

## 2. Node types

| Group | Nodes | Notes |
|---|---|---|
| Start | Ask, Button, Schedule, Table row, Webhook | what starts the skill |
| Phone | Open app, Go to page (map), Tap, Type, Fill from row, Scroll, Wait for page | each carries selectors and a check |
| Logic | Check, Branch (page variant, field error), Loop (max n), Merge | deterministic |
| Ports (plan 45) | Send (event, screenshot, fields, file), Wait (file, value), Secret out, Secret in | plugin I/O |
| **Resolvers** | SMS code, Email link, TOTP, Username picker, Date wheel, Cookie banner, Photo picker | specialized tools, no AI (§3) |
| Gates | Approval, Restriction, Handover (Verification desk) | the owner's boundaries |
| AI | Mind fallback (scoped) | only where a step drifts; limits set here |

## 3. Resolvers: specialized tools instead of an AI session

A resolver is a small deterministic tool that solves one recurring problem completely. It is:
- **faster and cheaper:** no model turns;
- **safer:** values never pass through a model;
- **testable:** it has fixed inputs, outputs and failure routes.

**SMS code resolver:**
- **Inputs:**
  - an SMS source (a number the owner registered and confirmed);
  - a sender match ("Instagram");
  - the code pattern (`\b\d{6}\b`, per app template);
  - the target field (a ref from the Sign-up Map);
  - a window: received after the step started, within 180 s.
- **Flow:** `code.in` from the SMS plugin → match → the code sealed to the phone (plan 33 sealed delivery) → filled
  into the field → the check (the page advances).
- **Failure routes:**
  - timeout → tap "Resend code" once → wait again → Handover;
  - wrong code → Handover.
- **Never:** stored, logged, shown to a model, or taken from a number the owner didn't register.

**Others:**
- **Email link:** `link.in` from the owner's inbox.
- **TOTP:** a vault seed → code, sealed to the phone.
- **Username picker:** rules from the row (prefix, digits) and a loop on "taken".
- **Date wheel:** sets a date picker exactly.
- **Cookie banner:** reject non-essential.
- **Photo picker:** `file.in` → the gallery → the picker.
- **CAPTCHA, selfie and ID checks** have no resolver. They always go to a person (Handover).

## 4. Changing what the next run does

- **Restrictions** attach to the whole skill or one node:
  - which profiles and phones;
  - allowed hours;
  - a cap per day;
  - apps never to touch;
  - "approval before submit";
  - "secrets sealed only";
  - a budget (model turns, tokens).
  
  The run enforces them in code; a model can't lift them.
- **Mind fallback** is a node with its own scope:
  - which pages it may handle;
  - max turns;
  - which tools (for example read, tap and type, no new apps);
  - what to do when the scope runs out (Handover or stop).
  
  A run only enters it when a step's check fails (drift).
- **Publishing:**
  1. **Save:** a new Draft version.
  2. **Test:** a dry run in the Lab or on a spare profile; its result is attached to the version.
  3. **Review:** the version goes to the phone.
  4. **The owner confirms on the phone,** and it becomes Verified and is used by the next run.
  
  The PC can't skip the phone step (`SkillPromotion`). A version that fails in real runs can be **quarantined** from
  either side.

## 5. Sync between the phone run and the dashboard

- Every node in a run reports into the run timeline (plan 45 `run.event`). Glass shows the run live on the flow:
  the current node pulses and finished ones are ticked.
- Resolver results sync as metadata only ("code received 14:04:02, sealed, filled").
- Rows, accounts and the vault update from the same events (handle, status, sealed password).

## 6. Build plan (estimate)

| Run | Scope |
|---|---|
| S1 | Read-only Studio: render existing capsules and Sign-up Maps as flows; the inspector; the run overlay from history |
| S2 | Editing: move, add and remove nodes, branches, restrictions; versions and diffs; the publish flow with phone confirmation |
| S3 | Resolvers: SMS code (with plan 45 `code.in`), Email link, TOTP, Username picker, Date wheel, Cookie banner, Photo picker |
| S4 | Mind fallback scoping, live run view on the canvas, quarantine from Glass, skill cards to share |
