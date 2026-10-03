# Human Gesture Diagnostics V1

Status: V0.3 core contract

## Purpose

Human Gesture diagnostics provide bounded deterministic evidence about planned/synthesized motion without attaching full traces or app content to every action result. The gesture core remains a synthesis/evidence layer only; `PhoneToolExecutor`, GATE, ownership, stale-observation policy, confirmations, duplicate suppression, Session Contract routing, Layer2 and named virtual-display behavior remain above it.

## Version identities

- control: `cyclone.human_gesture.control.v1`
- normalized trace: `cyclone.human_gesture.trace.v1`
- canonical hash: `cyclone.human_gesture.trace_hash.v1`
- engine: `human-gesture`
- engine version: `1`

## Core API

`HumanGestureDiagnostics.forSwipe(plan, viewport)` and `HumanGestureDiagnostics.forTap(plan, viewport, target)` create `HumanGesturePlanDiagnostics` from an already-resolved production plan.

Plan diagnostics contain only:

- control / trace / hash version
- engine name/version
- gesture type
- resolved `HumanizeProfile`
- lowercase SHA-256 trace hash

`HumanGestureExecutionDiagnostics` is a bounded projection shape that Agent 2 may populate after actual execution. Runtime-owned fields are:

- requested `HumanizePreference` when known
- resolved profile when applicable
- execution mode
- bounded backend class
- optional plan diagnostics
- optional synthesis CPU nanoseconds
- optional bounded downgrade reason

There are intentionally no arbitrary text fields for page content, selectors, screenshots, typed values, session IDs, display IDs, request IDs or secrets.

## Canonical trace hash

`HumanGestureTraceHasher` hashes canonical binary bytes rather than JSON so replay identity does not depend on serializer formatting or language-specific decimal rendering.

Encoding for `cyclone.human_gesture.trace_hash.v1`:

1. big-endian scalar order
2. UTF-8 strings prefixed by signed 32-bit byte length
3. `Double` values encoded as exact IEEE-754 raw 64-bit bits
4. `Long` values encoded as signed 64-bit
5. point count encoded as signed 32-bit
6. optional target encoded as one boolean byte followed by four doubles when present

Fields, in exact order:

1. hash version
2. trace schema
3. engine name
4. engine version
5. gesture type
6. resolved profile name
7. viewport width
8. viewport height
9. duration ms
10. optional normalized target bounds: left, top, right, bottom
11. normalized point count
12. every `(u, v, t)` point in order

`seed` and `source` are deliberately excluded. They are provenance/replay metadata, not executed motion. Changing them without changing normalized motion therefore keeps the same hash.

For plan diagnostics, swipes always use 24 trace segments (`HumanGestureDiagnostics.CANONICAL_SWIPE_SEGMENTS`) so a caller cannot accidentally change plan identity by selecting a debug sampling resolution. Taps use the single normalized tap point plus visible/clipped target bounds.

Golden binary fixture:

- trace: `docs/fixtures/human-gesture-v03/production_tap_trace_v1.json`
- trace-hash metadata: `docs/fixtures/human-gesture-v03/production_tap_trace_v1.tracehash`
- expected canonical trace hash: `7a6f03e5084e2bc89bc17bedc5ca5f475bf53aae7e69eb4144441fd48c653d31`

The `.tracehash` value is the SHA-256 of the canonical trace-hash bytes defined above; it is intentionally **not** the byte digest of the JSON fixture file. The fixture is valid `cyclone.human_gesture.trace.v1` and is reproduced in JVM tests through the real production `HumanGestureTraceAdapter`.

## Agent 2 runtime seam

After Agent 2 has resolved a `TapPlan` or `StrokePlan`, it may call the corresponding `HumanGestureDiagnostics.for*` function once diagnostics are requested/needed. Do not re-plan and do not re-randomize.

Agent 2 must supply runtime truth itself: requested preference, actual execution mode, actual backend, downgrade reason and measured synthesis CPU time. Semantic native actions normally have no synthesized plan/hash. Full normalized traces should remain explicit diagnostic/lab output rather than ordinary action-result payloads.

## Agent 3 / Gateway seam

Gateway/MCP may project the bounded fields above, but must not recompute Android motion or accept PC-authored paths. The phone-produced trace hash is authoritative for synthesized foreground motion.

For cross-language verification, reproduce the binary format above exactly. Do not use the older Python lab `stable_hash()` JSON serialization as the V0.3 authoritative production hash; it includes provenance fields and has different canonicalization semantics. The lab may retain that value as a lab-local metric, but should add/compare `cyclone.human_gesture.trace_hash.v1` when ingesting production evidence.

## Safety and performance

Normal `HumanGestureEngine.plan*()` does not invoke trace adaptation or hashing. Diagnostics remain opt-in and therefore do not add SHA-256/serialization work to the planning hot path.

Existing planner and trace-adapter performance guards remain unchanged. V0.3 adds a separate hash/combined-diagnostics performance guard and reports p50/p95/p99 in the Agent 1 handoff.

## Motion parameters

V0.3 Agent 1 does not tune LIGHT/NORMAL geometry or timing constants. V0.2 production distributions remain the current evidence. Parameter changes should wait for reproducible physical-device or real-human trace evidence.
