# Goal-first core — replacing the screen-driven decision chain

> **Superseded** by [`15-model-is-the-agent.md`](15-model-is-the-agent.md): this proposal still put rules around the model.

**Status:** proposed 2026-09-25 after the alpha.23 phone runs. Nothing below is built yet.

## What the alpha.23 runs showed

| Run | What the owner asked | What Cyclone did | Who decided |
|---|---|---|---|
| 01:46:38 | check which Gmail I'm logged in with, then make a facebook.com account with it in Chrome | Step 1 proven in Gmail in 4 s (good). Step 2 opened facebook.com, saw the login page and started **logging in**: takeover prompt, then taps on Chrome's password-manager chips (ambiguous target ×3), then stopped | login policy, never the model |
| 01:47:12 | set a timer for 5 minutes | The phone was still on the Facebook login page from the previous run. Cyclone asked to take over **three times**, tapped the password manager, stopped. The timer was never attempted | login policy, never the model |
| 01:47:44 | download Instagram | Opened the (already installed) Instagram app instead of the Play Store, then waited 20.5 s for the model and hit the deadline | fast-path "named app", then model timeout |

Model turns in the first two runs: **zero**. No part of Cyclone that reasons about the sentence ever ran.

## Root causes (all verified in code)

1. **The decision chain is screen-first.** `OpenRouterAdaptiveAgent.planNext` runs about 20 local shortcuts in a fixed
   order before the model: human-control check, cookie banner, photo ledger, clause/account checks, difficulty
   escalation, user.md, **login autofill**, clock intents, landings, splash waits, easy fast path, Atlas doors, compiled
   skills, learned graph. Each can end the turn by looking at the current screen. The model, the only piece that reads
   the goal, is last.
2. **Login handling ignores the goal.** `LoginAutofillPolicy.shouldHandle(goal, page)` returns true for any login form
   unless the sentence is a sign-up; the navigation work made it fire for every clause run. A leftover login page
   hijacks "set a timer".
3. **No "this screen is not mine" concept.** A task never checks whether the current screen belongs to its plan, so
   leftovers from the previous run are treated as the task's world.
4. **Two competing credential UIs.** The Secrets Card appears only if `pendingLoginAutofill` is already set *and* a
   password field is detected; otherwise the old takeover prompt appears. Declining or resuming loops back into the
   same prompt (three prompts in 11 s).
5. **Unstable targets are retried.** Autofill targets Chrome's password-manager chips (semantic supplements), which
   fail revalidation as AMBIGUOUS; the same target is tried three times until non-convergence.
6. **Intents are matched by keyword, not understood.** "make an facebook.com account" is not a sign-up (regex needs
   a plain word before "account"); "download Instagram" is "open Instagram"; there is no install intent although
   `market://` is an allowed launch scheme; an already-installed app is not checked first.
7. **The model is too slow for a turn-by-turn loop.** GPT-6 Luna on the owner's route takes 14–20 s per decision and
   the per-decision deadline is ~20 s; a task gets one or two model turns.
8. **Diagnostics hide who decided.** "Model/decision turns: 0" next to eight actions; the report does not say which
   component chose each action, or why a screen was acted on.

## The upgrade: goal → plan → step executors → proof

```
sentence ──► Goal compiler ──► Plan (steps with intent, slots, place, proof, boundaries)
                                   │
                    ┌──────────────┴──────────────┐
                    ▼                             ▼
            Step executor (per intent)     Screen advisors (cookie, login, permission,
            strategy ladder:               splash) — consulted by the executor, only
            1. system intent               when the screen is on the step's place AND
            2. learned route / Atlas       the step needs it
            3. model on the step's place
                    │
                    ▼
            Step proof (typed) ──► next step … ──► done only when every step is proven
```

### 1. Goal compiler (once, before looking at the screen)
- Output: `Plan(steps)`, each `Step(intent, slots, place, proof, boundaries, consumes/produces)`.
- Intents (typed, extensible): `SET_TIMER`, `SET_ALARM`, `INSTALL_APP`, `OPEN_APP`, `OPEN_URL`, `READ_IDENTITY`,
  `CREATE_ACCOUNT`, `SIGN_IN`, `OPEN_CONVERSATION`, `SEND_MESSAGE`, `SEARCH_IN_APP`, `SETTINGS_CHANGE`, `READ_INFO`,
  `FREEFORM`.
- Fast deterministic parser for known shapes (today's clause compiler, rewritten as intent + slot extraction with
  entity lists: apps, hosts, durations, times, people). When the parser is unsure, or the sentence has an unknown
  verb, **one** structured model call proposes the plan; a local validator accepts it only if every intent is from
  the list, every place is named in the sentence or installed, the order follows the sentence and the whole sentence
  is covered.
- The plan is shown to the owner at the start ("1. Read your Gmail account · 2. Create a Facebook account with it —
  stops before submitting") and recorded in the run report.

### 2. Step executors with a strategy ladder
Each intent has an executor that owns its step until the step is proven:

| Intent | 1. System intent | 2. Known route | 3. Model on the step's place |
|---|---|---|---|
| SET_TIMER / SET_ALARM | AlarmClock intent (exists) | Clock map | Clock screen |
| INSTALL_APP | if installed: done ("already installed"); else `market://details?id=` | — | Play Store page (Install needs approval) |
| OPEN_APP / OPEN_URL | open_app / VIEW intent | — | — |
| READ_IDENTITY | Gmail landing (exists) | account menu door | Gmail screen |
| CREATE_ACCOUNT | site sign-up URL when known | Atlas "sign up" scenario | sign-up form; approval before submit |
| SIGN_IN | — | Atlas "sign in" scenario | Secrets Card for credentials |
| FREEFORM | — | learned route | model loop on the step |

**Place first:** before acting, the executor checks the screen is on its step's place. An off-plan screen (the
leftover Facebook login during a timer task) is never interacted with; the executor goes to its place (intent,
landing, or Home) and says so in the trace ("ignored: Facebook login — not part of Set timer").

### 3. Screen advisors, gated by the step
Cookie, login, permission-dialog and splash handling stop being turn-enders in a global chain. The executor asks them
when its screen is on-plan:
- **Login** only when the step is `SIGN_IN`, or a step needs a signed-in session and the owner allowed it. Never for
  timers, alarms, installs, identity reads or sign-ups (sign-up follows "Create account", not "Log in").
- **Cookie / permission / splash** as today, but only on the step's place.

### 4. One handoff model
- Credentials: always the **Secrets Card**, never the old takeover prompt; the login takeover is retired for
  credential walls.
- Consequential actions (pay, send, delete, create account, install): one approval card with the exact action.
- At most **one** prompt per boundary per step. Decline → the step fails with that reason; resume → continue from
  the step, never re-prompt for the same boundary.
- Unstable targets (password-manager chips, IME suggestions) are excluded from executor targets; a target that fails
  revalidation twice is abandoned for that step, not retried.

### 5. Model use
- Planner call only when the parser is unsure (one per task).
- Step calls get the step, its proof and the current screen — not the whole run context — and only when strategies 1–2
  cannot act.
- Per-task budget scales with steps; the backup model takes over on rate limits (exists); Glass shows per-route
  latency so a slow route is visible.

### 6. Proof and reporting
- Proofs stay typed (exists) and attach to steps.
- The run report starts with the plan, then per step: strategy used, screens ignored as off-plan, handoffs, proof.
- "Decided by" on every action: system intent / route / model / advisor(login|cookie|…) — no more "0 model turns"
  next to eight actions.

## What is replaced vs kept

| Replaced | Kept |
|---|---|
| The `planNext` shortcut chain (ordering of 20 local branches) | `CycloneLocalAgent` observe → plan → execute → verify loop |
| Goal-agnostic `LoginAutofillPolicy.shouldHandle` as a turn-ender | `PhoneToolExecutor`, GATE, approval boundaries, Secrets Card, Vault |
| Clause compiler as keyword splitting | Settle controller, deferred proof, typed proofs, ledger, Atlas, learned routes |
| Login takeover prompt for credential walls | Diagnostics format (extended), Glass inspector (extended) |

## Build order (alpha.24)

| Checkpoint | Contents | Proven by |
|---|---|---|
| K1 | Plan/Step/Intent model, deterministic goal compiler (intent + slots), plan in trace and on the phone | table of ≥60 sentences (EN/NL) → plans; the three alpha.23 sentences |
| K2 | Orchestrator: place-first check, executor dispatch; clock, install, open, identity, sign-up executors; model step executor wraps today's page decision | replay: timer on a leftover login page → timer set, zero login prompts; "download Instagram" → already installed / Play Store |
| K3 | Advisors gated by step; Secrets Card as the only credential handoff; one-prompt rule; unstable-target exclusion | replay of the two login-hijack runs |
| K4 | Model planner fallback with validator; budget per plan; "decided by" in reports and Glass | unknown-phrasing sentences; report fixtures |
| K5 | Device gate: the three alpha.23 sentences, the five navigation sentences, installs, a leftover-screen start | owner's phone |

## Non-goals for alpha.24
New Glass pages, mapping features, Wi-Fi share phase 2. The terminal command's overview ships alongside because it is
already built (`ed6bdb71`).
