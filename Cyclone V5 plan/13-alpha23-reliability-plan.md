# alpha.23 — Honest, patient, sentence-true navigation

**Why this release exists.** Three device runs on alpha.22 (2026-09-24 23:01–23:04, OpenRouter `openai/gpt-6-luna`)
failed in ways CI could not see: 5 of 5 app launches lost their after-screen, 0 steps verified, the Gmail → Facebook
sentence was compiled into one clause bound to the wrong app, and "set an alarm for 5 minutes" was reported done with
Clock merely open. alpha.23 is a reliability release: no new surfaces until these pass on a phone.

## Root causes (from the traces and the code)

| # | Symptom in the runs | Cause in code |
|---|---|---|
| R1 | `AFTER_OBSERVATION_FAILED` after every `phone.open_app` (0.75 s, never the 1.8 s window) | `GatewayV33ActionAdapter.captureAfterAction` retries only after a *successful* capture; the first capture during a launch animation throws `OBSERVATION_CHANGED_DURING_CAPTURE` (window signature changed) and returns null |
| R2 | Unverified step → recovery → free mode → screenshot → extra 15–18 s model turns | missing after-state is treated as failure instead of "not settled yet" |
| R3 | Alarm "done" with no alarm set | `GoalContract` falls back to `GENERIC_SEMANTIC_EVIDENCE`: any 2 goal words on screen (`clock`, `alarm`) pass; no alarm contract; `TIMER` only matches the word "timer" |
| R4 | Gmail task opened Chrome first and was pulled back to Chrome after Gmail | `ClauseCompiler` splits only on "then", ";" and "and open"; place = **last** destination in the text (Chrome); `FIND_SIGNED_IN_IDENTITY` bound to Chrome can never be proven; the landing branch re-opens the clause place up to twice, overriding the model's correct detour |
| R5 | Sign-up part of the sentence vanished | single unsplit clause keeps only the first capability matched |
| R6 | 2 model decisions per minute; one HTTP 429 ended the run | 14–18 s per decision on the selected route, 30 s request budget, circuit opens on 429 with no alternative route |
| R7 | `readAtMs` and page keys shown as `[PAYMENT_REDACTED]` | `TracePrivacy` card regex matches any 13–19 digit run (timestamps) |
| R8 | CI green, device red | unit tests fed ideal screens and "then"-phrased sentences; the real capture path and real phrasing were never exercised |

What worked and stays: live signed-in email read into the ledger (persona LIVE), masked facts, refusal to call the
Gmail task done without proof, approval boundaries.

## Status (built 2026-09-25, v5.0.0-alpha.23.dev1, versionCode 164)

| Item | State |
|---|---|
| W1 settle controller: refused capture = moving, classifier, evidence-based extension, launch stability | done (C1) |
| W1 deferred proof, WAIT events, progress line | done (C1) |
| W1 learned budgets per app (durations only) | done (C4) |
| W1 reasoned wait | done as a **local** evidence wait (one longer wait while loading, 30 s ceiling); a model check was not added because model turns cost 15–18 s on the owner's route |
| W2 action-goal rule, claim audit, ALARM/TIMER contracts, `phone.set_alarm` / `phone.set_timer` | done (C2) |
| W3 splitter v2, per-clause place binding, consumed facts, Dutch, once-per-clause landing, 30-sentence table | done (C3) |
| W3 model-compiled clauses with validator | **not built**; rules cover the table; next if device runs show gaps |
| W4 backup model route (settings + runtime switch on 429/502/503/circuit) | done (C4) |
| W4 NEEDS_YOU + one-tap retry when no backup is set; Glass per-route latency card | **not built** (runs still stop with the rate-limit message) |
| W5 replays (launch, alarm), phrasing table, redaction fix | done |
| W6 diagnostic header (waits, deferred proofs, model latency, backup, completion basis) | done (C5); Glass WAIT chips **not built** |
| Device gate | **not run** — see `orchestrators/HANDOFF-device-test-alpha23.md` |

## Release goal and acceptance (physical phone, this is the gate)

alpha.23 may be called stable only when, on the owner's phone with the owner's model route:

1. **Launch settle:** ≥ 95 % of `open_app` / `launch_intent` steps end with a verified after-state (target: all of them
   in the eval set), with WAIT evidence when they took longer than the fast ladder.
2. **Zero false completions** across the eval set below. A run that cannot prove its goal ends as FAILED or NEEDS_YOU
   with the missing proof named.
3. The three alpha.22 sentences and the five navigation sentences each reach their documented proof or stop at the
   documented boundary (sign-up approval, Secrets Card).
4. No model turn is spent because a screen was still loading.

Eval set (unchanged wording between builds, recorded in `12-navigation-eval.md`):
- `check my current logged in gmail and make a Facebook account with that Gmail on chrome Facebook.com`
- `open clock and set an alarm for 5 minutes`
- the five sentences already in `12-navigation-eval.md`
- stress: `open YouTube`, `open Chrome and go to nu.nl`, a slow app cold start (e.g. Instagram), a page that shows a
  spinner for > 3 s

## Workstreams

Ownership follows `AGENTS.md`: phone runtime `apps/mobile/**`, gateway `apps/device-gateway/**`, Glass `apps/glass/**`.
Every workstream lands with unit tests **and** a fixture replaying the relevant alpha.22 trace behaviour.

### W1 — Settle controller (fixes R1, R2) · highest priority

One component decides "is the screen ready?" for every page-changing action. It never taps.

1. **Fast ladder, fixed:** 300 ms, +500, +1000 (Fast Path invariant kept). A capture that throws
   `OBSERVATION_CHANGED_DURING_CAPTURE` or `FOREGROUND_REQUIRED` counts as **MOVING** and is retried; it no longer ends
   the settle. Retry cadence 120–250 ms.
2. **Screen state classifier** (`agent/settle/ScreenStateClassifier.kt`, pure, unit-tested):
   - MOVING: capture threw (window changed) or fingerprint differs between consecutive captures
   - LOADING: progress bar / busy role, "Loading", "Please wait", "Even geduld", launcher splash (logo only), empty
     WebView / only the address bar, skeleton placeholders, zero actionable controls in a non-Cyclone app
   - READY: expected package (open_app) or expected origin (launch_intent) in front, or page changed, and two
     consecutive identical fingerprints
   - BLOCKED: dialog, permission prompt, login wall → existing GATE / Secrets Card / cookie paths
   - UNCHANGED: identical to before with no loading evidence → `UNCHANGED` warning, never a second click
3. **Evidence-driven extension:** after the ladder, keep capturing **only while** MOVING or LOADING evidence is present,
   up to the budget (default 8 s). No evidence → stop early.
4. **Learned budgets** (`agent/settle/SettleBudgets.kt`): after each verified step record settle duration per
   `placeId + roomId` (numbers only — no labels, text or values). Budget = clamp(p90 × 1.5, 2 s, 15 s). Stored beside
   the Atlas, forgettable per app, exported in diagnostics as durations only.
5. **Reasoned wait** when the budget expires and the state is still LOADING/ambiguous: one bounded model check with a
   closed answer set `{wait(until, maxMs), ready, stuck(back|reopen), blocked}` — text context first, one screenshot only
   if the page has no text. `wait` becomes `phone.wait_for` with a concrete condition (fingerprint change / text gone /
   text appears). Max 2 reasoned waits per step, hard ceiling 30 s per step, cancel always wins.
6. **Deferred proof:** a step without a READY after-state is **PENDING**, not failed. It is verified on the next READY
   observation; recovery, free mode and vision escalation start only if that verification fails. Waits are counted
   separately from no-progress failures.
7. **Visible:** trace event `WAIT` {state, reason, waitedMs, budgetMs, learned: bool}; progress line
   "Waiting for Gmail to load (usually ~3 s)…"; Glass inspector shows wait time per step.

Files: `gateway/GatewayV33ActionAdapter.kt` (captureAfterAction), `fastpath/FastPathLoop.kt`,
`agent/tools/CycloneAgentEnvironment.kt` (verification status PENDING), `agent/contract/AgentSemanticVerification.kt`,
`ai/OpenRouterAdaptiveAgent.kt` (pending handling, splash waits folded into the controller), new `agent/settle/*`,
`ai/RunInsight.kt` + Glass `runPage.ts` (WAIT rendering).

Tests: capture throws N times then succeeds → verified; spinner for 4 s → one extension, no model call; dead screen →
UNCHANGED in < 2 s; learned budget grows and is capped; reasoned wait capped at 2; replay of the alpha.22 alarm launch
(0.75 s null after-state) now verifies.

### W2 — Honest completion (fixes R3)

1. **Action goals cannot pass on words.** If the goal has an action verb (set, create, add, send, turn on/off, enable,
   disable, book, schedule, save, delete, pay, buy, sign up, register, change, rename, play) the generic contract may
   not complete on page words alone. It additionally needs a verified mutation **after** landing in the target app
   and, when a typed contract exists, that contract.
2. **Done-claim audit:** when the model says `done`, its own summary is checked against the goal's verb. A summary that
   only reports navigation ("X is open", "on the Alarms screen") for an action goal is rejected as
   `completion.claim_is_navigation` and the run continues (or fails honestly at the budget).
3. **Typed contracts for common phone intents** (with live proof):
   - ALARM: an enabled alarm row at the resolved time is visible in Clock. "alarm for 5 minutes" / "alarm in 5
     minutes" resolves to now + 5 min (shown to the user as "Alarm at 23:09"); "alarm at 7" to 07:00.
   - TIMER (exists): accept "5 minute timer", "timer of 5 minutes", h:mm:ss.
   - OPEN_PLACE (exists), WIFI/NETWORK (exists), IDENTITY/DM/SIGN-UP (exist).
4. **Intent routes first for Clock** (prefer intents over tapping, per `AGENTS.md`): add canonical executor tools
   `phone.set_alarm {hour, minute, label?}` and `phone.set_timer {seconds, label?}` using Android `AlarmClock` intents
   (`ACTION_SET_ALARM` / `ACTION_SET_TIMER`, UI shown, never `SKIP_UI` on the first use), manifest
   `com.android.alarm.permission.SET_ALARM`. Proof still comes from the live Clock screen, not from the intent result.
5. **Show the proof:** the result card and Glass run header state the completion basis ("Verified: alarm 23:09 enabled
   in Clock"). Weak or missing basis cannot be labelled COMPLETED.

Files: `agent/contract/GoalContract.kt`, `agent/nav/TaskClauses.kt` + `ClauseProof.kt` (ALARM), `PhoneToolExecutor.kt`,
`PhoneToolRegistry`, `AndroidManifest.xml`, `ai/OpenRouterAdaptiveAgent.kt` (claim audit), result UI + Glass.

Tests: replay of the alpha.22 alarm run → not completed; alarm proof accepts only an enabled row at the resolved time;
"open YouTube" still completes on landing; every existing contract test stays green.

### W3 — Sentence-true clause planner (fixes R4, R5)

1. **Splitter v2:** besides "then" / ";" split on `and <action verb>` and `, <action verb>` when the two sides differ in
   place or capability ("check … gmail **and make** a Facebook account"). Keep a single clause when both halves are in
   one app ("open clock and set an alarm").
2. **Per-clause place binding:** a clause's place is the destination named **inside that clause** (first mention), then
   the capability's home (identity → Gmail/Google account; alarm/timer → Clock; Wi-Fi → Settings), then the previous
   clause's place. Never the last app of the whole sentence.
3. **Carry facts forward explicitly:** "with that Gmail / that email / it" marks the next clause as consuming the
   ledger fact of the previous clause (sign-up uses `signed-in-email`).
4. **Model-compiled clauses with a local validator** (for sentences the rules cannot parse confidently): one structured
   planning call returns `[{text, place, capability, consumes}]`; the phone validates it (capabilities from the
   whitelist, places named in the sentence or installed, order preserved, every word of the sentence covered by some
   clause) and falls back to the rules when validation fails. No click scripts, destinations only.
5. **The planner does not fight the model:** a landing is issued once at clause start. If the model deliberately
   leaves the clause place toward a place named in the sentence, the landing does not drag it back; the clause is
   re-bound or a new clause is inserted, and the trace records why.

Files: `agent/nav/TaskClauses.kt`, `agent/plan/TaskDifficulty.kt`, `ai/OpenRouterAdaptiveAgent.kt` (landing branch,
compile call), tests `MultiAppScenarioTest` + new natural-phrasing table (≥ 30 sentences, EN + NL, without "then").

### W4 — Model budget and resilience (fixes R6)

1. **No model turn to find out a screen loaded** (W1) and **no model turn after a verified landing** when the next
   clause step is deterministic.
2. **Latency-aware budgets:** measure p50/p90 per route; task budget scales with the number of clauses; a single slow
   request no longer ends a multi-clause run while the task budget remains.
3. **429 handling:** honour `Retry-After`; after bounded retries switch to the owner's configured **secondary route**
   (Settings → Models → "When the main model is busy") instead of stopping; if none is set, stop with NEEDS_YOU and a
   one-tap "retry" rather than FAILED.
4. **Route advice, not a silent switch:** Glass Home shows per-route latency and rate-limit counts from real runs and
   recommends the faster route or an own OpenRouter key. The model is never changed without the owner.

Files: `ai/ProviderRequestLifecycle.kt`, `ai/model/ModelRegistry.kt`, provider circuit, settings UI, Glass Home card.

### W5 — Evidence that matches the phone (fixes R7, R8)

1. **Trace-replay fixtures:** the three alpha.22 runs become JVM tests driving the agent loop with a fake environment
   whose capture throws during launches and whose provider is slow / returns 429.
2. **Natural-phrasing table** (W3) and **stress screens** (spinner, splash, dead screen) as fixtures.
3. **Redaction fix:** card numbers require a Luhn-valid 13–19 digit run and never match inside known numeric fields
   (`readAtMs`, `generation`, page keys, observation ids); tests on the alpha.22 diagnostics.
4. **Device gate:** `HANDOFF-device-test-alpha23.md` runs the eval set; the release notes state the device result; the
   word "stable" is used only after it passes.

### W6 — Diagnostics the owner can read

- Diagnostic header gains: completion basis, pending/verified steps, waits (count, total ms), model turns with latency.
- Clause rows show place, capability, consumes/produces facts, proof.
- Glass inspector: WAIT chips on steps, "completion basis" in the run header, per-route latency on Home.

## Order of work and checkpoints

| Checkpoint | Contents | Must pass |
|---|---|---|
| C1 | W1 steps 1–3 + deferred proof + W5 replay of launches | replay: 5/5 launches verified; no recovery on loading |
| C2 | W2 (action-goal rule, claim audit, ALARM contract, set_alarm/set_timer tools) | replay: alarm run not completed; contract suite green |
| C3 | W3 splitter v2 + place binding + landing rule; model-compiled clauses behind the validator | natural-phrasing table; Gmail→Facebook compiles to identity@Gmail → sign-up@facebook.com |
| C4 | W1 learned budgets + reasoned wait; W4 budgets + 429 secondary route | stress fixtures; 429 fixture ends NEEDS_YOU or continues on the secondary route |
| C5 | W5 redaction, W6 diagnostics + Glass, docs, versions (`5.0.0-alpha.23.dev1`, versionCode 163+) | full mobile / gateway / Glass / companion suites, release guards |
| C6 | Device gate on the owner's phone | acceptance above; results recorded in `12-navigation-eval.md` |

C1 and C2 ship value on their own; if time runs short they may go out as an intermediate dev build clearly labelled
"reliability part 1".

## Guardrails (unchanged, restated because waiting and new tools touch them)

- Waiting never taps. Unchanged is never answered with a second click.
- `PhoneToolExecutor` stays the only mutation engine; the new alarm/timer tools live there and go through GATE.
- Approval boundaries for pay/send/delete/permission/authentication/account creation are unchanged.
- Learned budgets store durations only. No passwords, OTPs, keys, payment data or typed secrets anywhere.
- The model route is the owner's choice; Cyclone recommends, never switches silently.
- Diagnostics keep model-visible context, decisions, tools, verification and recovery — never hidden reasoning.

## Out of scope for alpha.23

Wi-Fi share phase 2, new Glass pages, mapping features, people-memory expansion. They wait until the device gate passes.
