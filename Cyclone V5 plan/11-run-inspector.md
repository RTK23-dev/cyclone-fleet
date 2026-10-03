# 11 — Run inspector (the autopsy)

Open any Cyclone run in Glass and see exactly what happened, step by step. For a failed run, see the **cause of death** and what would fix it.

This is the part of Artemis the owner wants in Cyclone: surgical insight into a run. The data comes from the **phone**; Glass only displays it.

## Page layout

```text
┌ Run  "find the dm of Louella"  ✕ failed at step 9 · 1m 42s · Facebook in Chrome ───────────┐
│ Cause of death: expected room "DM list", phone was on "Log in"; no password slot → stopped   │
│ Fix: set the Facebook password slot, or map the Log in room's doors            [Open map]   │
├──────────────┬──────────────────────────────────────────────────┬──────────────────────────┤
│ Timeline     │ Step 9                                           │ Route on the map         │
│ 1 ● Gmail    │ Room: Log in (expected: DM list)                 │  Home ─► Menu ─► ✕ Log in │
│   map step   │ Before / after frame (redacted)                  │  planned: Home ─► DMs     │
│ 2 ● …        │ What Cyclone saw: page card summary              │                          │
│ …            │ Decision: open DMs — source: map (instant)       │                          │
│ 9 ✕ Log in   │ Tool: phone.click "Messages"  → accepted         │                          │
│              │ Verification: screen changed, room ≠ expected    │                          │
│              │ Recovery: none — needs secret                    │                          │
└──────────────┴──────────────────────────────────────────────────┴──────────────────────────┘
```

### Header

Goal sentence (unchanged), outcome (done / failed / stopped / needs-secret), duration, apps, device, session plane, model, and the split
**map steps vs model steps** (how much of the run Cyclone already knew).

### Cause of death

For every run that did not finish, one line naming the **first step that went wrong** and the class of failure:

| Class | Meaning | Typical fix shown |
|---|---|---|
| `wrong-room` | After a door, the phone was not in the expected room | Remap this room / door |
| `door-missing` | The map has no door from here toward the end result | Map this room |
| `stale-door` | The door exists on the map but no longer works on this app version | Remap for version X |
| `element-not-found` | The target on screen could not be located | Teach this door |
| `unchanged` | The action produced no screen change (not a second click) | Inspect the target |
| `needs-secret` | A login or secret wall with no slot set | Set the slot on the phone |
| `gate` | Pay / send / delete / permission approval stopped the run | Expected; approve on the phone |
| `human-took-control` | The user took the phone | — |
| `model-gave-up` | The model stopped without reaching the end result | Open the steps before |
| `verification-failed` | Completion check rejected the claimed result | Open the proof step |
| `timeout` / `budget` | Out of time or steps | Look for loops |
| `transport` | Gateway / accessibility / overlay unavailable | Check the phone |

The classifier runs **on the phone** from the recorded events, so the phone's own diagnostics and Glass agree.

**Built in Glass alpha.2** (`apps/mobile/**/ai/RunInsight.kt`): `needs-secret`, `gate`, `human-took-control`, `transport`,
`timeout`, `unchanged`, `element-not-found`, `wrong-room`, `verification-failed`, `model-gave-up`, plus three the trace needs
today: `cancelled` (stopped by the user), `provider-error` (model provider failed) and `blocked` (other hard blockers), and
`unknown` with the last failed step.

**Built in Glass alpha.4** (run record v2): each decision turn records the structural room (`room=`), the app (`place=package:…`)
and its installed version (`appv=`); each check records `roomAfter=`. `decisionSource` is `map` for `graph:` / `compiled-skill:`
actions and `model` otherwise. `stale-door` = a map-chosen step failed on the target or screen (named with the app version).
Still to come: `expectedRoomId` recorded by the agent per step, redacted frames.

**Built since (alpha.7–alpha.16):** `door-missing` (gave up in a mapped room with no door onward) and `wrong-room` (a
map-chosen step left a mapped room for a room none of its doors lead to), both computed on the phone from the Atlas after the
run. The inspector lists the **scenarios a run reached**, **compares** a failed run with the last good run of the same goal
(where the routes split), and offers **Ask again**. Runs has a **Goals** view (success rate per sentence).

**Built in Glass alpha.6:** mapping passes are written to the same trace (door steps with a `mapper:` action, neither map nor
model), so Runs lists them and the classifier explains how they ended. **Mark as expected** (`runs.mark`) keeps a run in Runs but
out of scenario health. The cause card links to the failing room on the map and, for `stale-door`, the app's Versions tab.
Mapping from today's trace: GATE_SUSPEND without a later GATE_RESUME → `gate` / `needs-secret`; HARD_BLOCKER text → login wall,
locked phone or lost accessibility; NON_CONVERGENCE codes (`convergence.task_timeout`, `.repeated_action`, `.stale_target`,
`.backtrack`, `.mutations_without_verified_progress`, `completion.*`, `.malformed_model`, `.recovery_without_evidence`,
`classifier.non_convergence`) → the classes above.

### Timeline

One row per decision turn. Each step shows:

- **Room**: which Atlas room the phone believed it was in, and the expected room when following a route.
- **Frames**: before / after, redacted on the phone (vault fields and typed secrets removed). Optional per run; bounded size; kept on the phone with a retention limit.
- **What Cyclone saw**: the model-visible page card summary (not the hidden chain-of-thought).
- **Decision** and its **source**: `map` (took a known door without asking the model) or `model` (the model chose).
- **Tool call** and result (accepted / rejected / GATE).
- **Verification**: settle, fingerprint, room match, completion checks.
- **Recovery** steps, if any.

### Route on the map

The run's path drawn on the app's Map board: planned route (from the scenario or Atlas sketch) vs actual path, with the failing door marked.
One click jumps from a failed step to that room in the Map tab to fix it.

### Actions

- **Open map at this room** / **Remap this room** / **Teach this door** (take control, show the door, give back).
- **Mark as expected** (for example a GATE stop the developer wanted), so it stops counting against scenario health.
- **Download report**: redacted JSON + readable text, the same content the phone's run diagnostic exports.

## What the phone must record (run record v2)

The phone already stores a user-visible trace per run (`AgentTraceStore`: sessions and events such as `TOOL_REQUESTED`,
`ANDROID_EXECUTION`, `VERIFICATION`, `ACTION_REJECTED`) and exports `cyclone-run-diagnostic-v39`. Extend it, do not replace it:

| Add | Why |
|---|---|
| `roomId` / `expectedRoomId` per step (Atlas screen id) | Draw the route; detect `wrong-room` |
| `decisionSource: map \| model` per step | Show how much Cyclone already knew; measure speed-ups |
| `scenarioId` when a known route was used | Tie runs to scenario health |
| `appVersion` per app entered | Tie failures to map versions |
| `causeOfDeath { stepIndex, class, detail }` on non-successful runs | One classifier, same answer on phone and Glass |
| Redacted frame refs (optional, bounded, TTL) | Before / after images in the inspector |
| Mapping runs recorded as runs too | One Runs page for Ask, mapping and automations |

Never recorded: passwords, OTPs, API keys, payment data, raw typed secret values, hidden provider reasoning.

## Gateway ops

```text
runs.list(filter, cursor)          → summaries (outcome, apps, duration, map/model split, cause class)
runs.get(runId)                    → header + steps + cause of death + route
runs.frame(runId, stepIndex, which) → redacted JPEG (before | after), 404 when not kept
```

Read-only. Available to the local Glass session; not exposed to remote MCP by default.

## Tests that must exist

- A run that stops on a login wall reports `needs-secret` at the right step, on the phone and in Glass.
- A route that lands in the wrong room reports `wrong-room` with expected and actual rooms.
- No secret value appears in `runs.get` output or in a redacted frame (vault fields masked).
- `decisionSource` is `map` only when a known door was taken without a model call.
- Downloaded report equals the phone's own diagnostic export for the same run.
