# Handoff — V5 Navigation: make multi-app Asks work

**For:** a coding agent starting cold on the Cyclone repo.
**Owner's complaint (2026-09-24, after testing alpha.17/18):** agents are "still very incapable of navigating multi app
questions", e.g. *"open Gmail and check which Gmail I am logged in with, then open Chrome and make an Instagram account
with that Gmail"*.
**Goal:** a measurable jump in multi-app Ask success, built the V5 way (plans [01](../01-headset-and-laws.md) and
[07](../07-ask-compiler.md)), with every step visible in Glass's run inspector.

Read first (15 min): `AGENTS.md`, `Cyclone V5 plan/01-headset-and-laws.md`, `Cyclone V5 plan/07-ask-compiler.md`,
`Cyclone V5 plan/11-run-inspector.md`, then this file.

## Why it fails today (verified in code, not guessed)

| # | Gap | Where |
|---|---|---|
| G1 | **The Atlas is only a hint.** Mapping (Glass → Map) fills the Atlas, but Ask only gets `AtlasSketch` text in the model prompt ("Map hint only… never repeat a route"). The executor that takes known doors without a model call (`knownAppGraphAction`) uses the **older AppLearner graph**, not the Atlas. So mapping an app does not make Ask more reliable there. Law 1 of plan 01 says the opposite: *map drives, eyes confirm*. | `ai/OpenRouterAdaptiveAgent.kt` `knownAppGraphAction` (~line 1715), `atlasSketchFor` (~1805); `agent/plan/AtlasSketch.kt`; `brain/graphv2/AtlasProvider.kt` (`AtlasRetriever.findHint`) |
| G2 | **No memory between apps.** A fact read in app A (the signed-in Gmail) is not stored anywhere structured; app B only has whatever survived in the model context. Only a narrow login helper (`DestinationAuthority.visibleEmails` / `AccountSighting`) exists. | `agent/plan/DestinationAuthority.kt` |
| G3 | **Coarse splitting.** HARD tasks get one LLM call (`HorizonPlanner`) returning ≤ 8 waypoints with coarse until-conditions (`app_foreground`, `account_observed`, `goal_contract`). A waypoint can be "done" while the clause it serves is not. | `agent/plan/HorizonPlanner.kt`, `TaskTrajectory.kt`, `TaskDifficulty*.kt` |
| G4 | **No re-routing.** Off-route (wrong room) the agent improvises. Glass already detects `wrong-room` / `door-missing` after the fact (`gateway/GatewayV5RunsAdapter.withMapCause`), but the agent never uses the Atlas to find its way back during the run. | same |
| G5 | **Plan 07 not built:** capability binding (`FIND_SIGNED_IN_IDENTITY`, `OPEN_DM`, `SEARCH_PERSON`, `CREATE_ACCOUNT`) and **People memory** (Louella). `grep -r PeopleMemory` → nothing. | — |

Paths are under `apps/mobile/app/src/main/java/com/cyclone/mobile/`.

## What to build (in this order)

### N1 — The map drives, eyes confirm (plan 01 law 1)
- On a place with a mapped route to the room the current clause needs, when the **live screen's structural room**
  (`mapping/crawl/AndroidMapperPorts.kt` `StepLocation.here()`) equals the expected room, take the Atlas door directly
  (no model call), then check the room after. Record `decisionSource=map` (action prefix `atlas:` → add it next to
  `graph:` in `ai/RunInsight.kt decisionSource`) and write `expectRoom=<id>` in the trace detail so Glass can show
  expected vs actual.
- Mismatch, failed door or stale door → drop to see-think-act for that step (never replay blind; Unchanged is not a
  second click). Doors marked `danger` are never taken by the map path.
- Door → action: the Atlas edge carries a selector hint (`AtlasEdgeMetadata.selectorKey`, last-seen target). Resolve it
  against the **current** observation's elements through `PhoneToolExecutor` (the only mutation engine). If it doesn't
  resolve uniquely, it's a miss → model.
- Update `AtlasSketch.RULE` wording to match: "the map drives when the screen matches; otherwise look".

### N2 — Task ledger (memory between apps)
- New `agent/nav/TaskLedger.kt`: per run, ordered facts `{key, value, sourcePlace, sourceRoom, persona, readAtMs}`
  (e.g. `signed-in-email`, `found-username`, `thread-with`). Values are read **now** from the screen (law 3), live
  persona only (law 8).
- **Never** store secrets: reuse `automation/skill/SkillSecrets.kt` as the sieve; password/OTP/payment fields are never
  ledger values (AGENTS.md invariant). Emails may be stored but must be masked in traces (`j***@gmail.com`).
- The ledger is included in the next clause's model context and in the trace (masked), so Glass can show it.

### N3 — One clause at a time, each with its proof
- Split the sentence into ordered **clauses** bound to a **place** (app or Chrome origin; native missing → Chrome
  origin, plan 07 step 2) and a **capability**, each with a checkable `doneWhen` (e.g. "signed-in email is in the
  ledger", "Instagram sign-up form shows that email"). Keep the sentence as law: clauses never rewrite the user's verb.
- Replace/extend `HorizonPlanner` output: waypoints become clauses; `until` becomes `doneWhen` checked against the
  ledger + live screen. A clause is done only when its proof holds; failure names the clause (new cause kind
  `clause-failed` with the clause text) so Runs/Goals show where the chain broke.
- HUD/stage copy stays per clause (plan 07 "Louella sketch").

### N4 — Re-route with the Atlas
- When the room after an action is not the expected one and both are mapped, compute the shortest route from the
  current room to the clause's target room (same BFS as `gateway/GatewayV5KnowledgeAdapter.shortestRoutes`; extract it
  to a shared helper) and continue on the map path (N1). No route → model.

### N5 — People memory (plan 06 / 07)
- `brain/people/PeopleMemory.kt`: live persona only; display name, aliases, last place + thread landmark. Written when a
  run verifiably opens a person's thread; read by `SEARCH_PERSON` / `OPEN_DM` clauses. Dummy/mapping runs must never
  write people.

### N6 — Make it visible in Glass (small, but do it)
- Run record gains optional `clauses` (text, place, status, proof) and masked `ledger` entries. Gateway validators are
  strict: add them as **optional** keys in `apps/device-gateway/cyclone_device_gateway/desktop_runtime/v5_contract.py`
  (see how `kind` / `guarded` were added) and parse defensively in `apps/glass/src/services/runs.ts`; show a "Clauses"
  card in `apps/glass/src/pages/runPage.ts`.

## Measure it (do this first, keep it running)

Create `apps/mobile/app/src/test/.../agent/nav/MultiAppScenarioTest.kt` with the fixed sentence set below, driven by a
fake observation/executor (see existing agent tests for fakes), and a short `Cyclone V5 plan/12-navigation-eval.md`
with the same sentences for physical runs from Glass (Ask → Runs → Goals shows success rate per sentence):

1. "open Gmail and tell me which Gmail I am logged in with"
2. "open Gmail and check which Gmail I am logged in with, then open Chrome and go to instagram.com sign-up with that email"
3. "open the clock app and set a timer for 5 minutes" (single app control)
4. "find the DM of Louella on Facebook" (native missing → Chrome origin; login wall → secrets card, not failure)
5. "open Settings, then Wi-Fi, then tell me the connected network name"

Each must: keep the sentence, produce the right clauses, carry ledger facts, stop at GATE for anything consequential
(account creation **submit**, send, pay → approval on the phone), and never type a secret outside the Secrets Card.

## Rules (non-negotiable, from AGENTS.md)

- `PhoneToolExecutor` is the only mutation engine. One screen-changing mutation per decision turn; re-observe after.
- Approval boundaries stay (pay / send / delete / permission / auth). Creating an account = **stop_human** before submit.
- Never persist passwords, OTPs, API keys, payment data or raw typed secrets (ledger, Atlas, traces, Glass).
- Glass stays intelligence-free (`python scripts/ci/glass_guard.py`).
- No model identifiers in commits/PRs.

## Ownership and coordination

- **Your paths:** `ai/OpenRouterAdaptiveAgent.kt`, `ai/RunInsight.kt` (decision source only), `agent/plan/**`, new
  `agent/nav/**`, new `brain/people/**`, their tests; plus the small N6 edits in `v5_contract.py`, Glass `runs.ts` /
  `runPage.ts`.
- **Another session is building Wi-Fi screen sharing (AnyDesk-style) at the same time** in `capture/**`,
  `gateway/GatewayCapture*`, `apps/device-gateway/.../desktop_runtime/video.py` + the video route in `api.py`, and Glass
  `ui/liveView.ts` / `video/**` / `pages/phonePage.ts`. Don't edit those.
- Work on branch **`v5/navigation`** from `claude/cyclone-v5-handoff-review-9qrs40`. **Do not bump versions or publish
  releases**; the release owner merges your branch and cuts the alpha. Push checkpoints often.

## Validate before every push

```bash
cd apps/mobile && ./gradlew :app:testDebugUnitTest
python -m pytest apps/device-gateway/tests -q          # if you touched v5_contract.py
cd apps/glass && npm test && npm run build && python ../../scripts/ci/glass_guard.py   # if you touched Glass
python scripts/ci/mobile_product_guard.py
```

## Definition of done

- N1–N3 built with tests; N4–N6 built or explicitly deferred in the return file.
- The 5 scenario tests pass with fakes; the eval doc lists them for physical runs.
- A mapped app's route shows `decisionSource=map` steps in Glass with expected vs actual room.
- Return file `Cyclone V5 plan/orchestrators/returns/RETURN-navigation-multi-app.md`: what changed, test results,
  what is honestly unverified on a physical phone, and open risks.
