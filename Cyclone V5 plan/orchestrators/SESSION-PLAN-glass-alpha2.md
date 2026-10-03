# Session plan — Glass 1.0.0-alpha.2 (Runs + run inspector)

**Plan:** [`09` Glass web cuts](../09-cuts-and-milestones.md#glass-web-cuts-owner-charter-2026-09-23) → alpha.2 · spec [`11-run-inspector.md`](../11-run-inspector.md) · charter [`03`](../03-glass-v1.md)
**Base:** Glass 1.0.0-alpha.1 on `claude/cyclone-v5-handoff-review-9qrs40` @ `c86712aa` · **Written:** 2026-09-23

## Goal

Open any Cyclone run in the browser and see what happened, step by step, and for a run that did not finish, the
**cause of death** and what would fix it. The phone decides the cause; Glass only shows it (law 9, law 11).

## What the phone already records (verified in code)

- `AgentTraceStore` (`cyclone_ai_history.db`): one session per run (goal, model, status RUNNING/SUSPENDED/COMPLETED/FAILED/CANCELLED,
  started/ended, result, decisions) and ordered events (kind, display text, code, ok, detail), already cleaned by `TracePrivacy`.
- Event kinds used by the agent: `START`, `TOOL_REQUESTED` / `ACTION_REQUESTED` / `TOOL_CALL` (turn boundary), `TOOL_RESULT`,
  `ANDROID_EXECUTION`, `ACTION_REJECTED`, `VERIFICATION`, `VERIFY` (`completion.*`, `verify.*`), `AFTER_OBSERVATION`,
  `RECOVERY_CLASSIFIED`, `REPLAN`, `VISION`, `GATE_SUSPEND` / `GATE_RESUME`, `HARD_BLOCKER`, `NON_CONVERGENCE`
  (`convergence.task_timeout`, `.repeated_action`, `.stale_target`, `.backtrack`, `.mutations_without_verified_progress`,
  `.malformed_model`, `.recovery_without_evidence`, `completion.ambiguous_after_recheck`, `classifier.non_convergence`),
  `CANCELLED`, `DONE` / `STOPPED`, `ERROR`. Local-agent events carry `page=<fingerprint>` and `action=<signature>` in `detail`.
- `AgentRunDiagnosticV39.metrics()` already counts tool calls, failures, verification failures, recoveries and vision checks.

Not recorded yet (alpha.3): Atlas room per step, map-vs-model decision source, redacted frames.

## Checkpoints

| # | Checkpoint | Paths | Done when |
|---|---|---|---|
| **A** | Phone `RunInsight`: steps from the trace (turn boundaries), per-step result/verification/recovery, run metrics, and the **cause-of-death classifier** (one place; classes from 11) | `apps/mobile/**/ai/RunInsight.kt`, `gateway/GatewayV5RunsAdapter.kt` (`runs.list`, `runs.get`), protocol lists | JVM tests over realistic traces: login wall → `needs-secret`, timeout, repeated action → `unchanged`, stale target, GATE, cancelled, provider error, completed has no cause; sizes capped; no secret-looking values |
| **B** | Gateway `runs.*`: allowlist, strict response validation, routes `GET /v1/devices/{id}/runs`, `GET /v1/devices/{id}/runs/{runId}` | `apps/device-gateway/**` | contract tests; full suite green |
| **C** | Glass **Runs** page: every run with outcome, goal, when, duration, steps, failures, cause chip; filters (All / Failed / Finished / Stopped), search | `apps/glass/src/pages/runsPage.ts`, `services/runs.ts` | DOM tests with the fake gateway; honest states for old phones |
| **D** | Glass **Run inspector**: header + metric tiles, cause-of-death card with the failing step and the fix, step timeline, step detail (events, codes, verification, recovery), download redacted report | `apps/glass/src/pages/runPage.ts`, `styles/runs.css` | DOM tests; visual check through the real gateway |
| **E** | Identity + docs: Glass `1.0.0-alpha.2`, Mobile `5.0.0-alpha.8.dev1` (149), gateway/MCP alpha.8; 11 + STATUS updated | versions, docs | all guards and suites green |

Push after every checkpoint.

## Out of scope (alpha.3+)

Rooms per step and the route on the map, map-vs-model steps, redacted frames, mapping runs in the same list, Runs tab on
each app, Scenarios, Versions.
