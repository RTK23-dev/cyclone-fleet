# Cyclone V4 foundation contract after 3.9.6

Cyclone 3.9.6 establishes session, observation, and live-frame seams only. It does **not** add background/hidden Android execution or continuous vision.

## Runtime invariant

Cyclone keeps one `PhoneToolExecutor`, one policy authority, one Brain/memory system, and one agent runtime. `ExecutionSession`, session-scoped observations, `FrameSource`, and `LiveFrameBroker` sit underneath those systems; they are not a second automation stack.

For 3.9.6:

- missing/blank session ID resolves to `default-foreground`;
- `default-foreground` always means display `0`;
- the only executable session is `FOREGROUND_ACCESSIBILITY` on display `0`;
- synthetic/future sessions can be registered for isolation tests and future wiring but remain `executable=false`;
- input ownership is represented per session, while production controller behavior remains unchanged and single-controller;
- observation generations and frame IDs are session-local;
- session/display mismatches are rejected before storing observations or frames;
- `LiveFrameBroker.latest()` and related reads never initiate capture;
- the in-memory frame broker is bounded per session (default capacity `8`).

## Added seams

### Execution sessions

`runtime/session/ExecutionSession.kt` defines durable session identity, display identity, backend kind, target package, input ownership, executability, creation metadata, and the canonical `ExecutionContext` correlation type.

`ExecutionSessionStore` owns the stable default session, immutable snapshots, synthetic registration/removal, duplicate prevention, and session/display validation.

### Session-scoped observations

`SessionObservationStore` is the migration seam from historical global-current-observation behavior toward `currentObservation(sessionId)`. It validates a registered session/display pair and assigns generations independently per session. Missing/blank session lookup maps to the default foreground session for legacy compatibility.

The existing production foreground observation pipeline is intentionally not rewired in 3.9.6 to minimize collision with grounding/runtime changes in parallel work.

### Live frames

`ai/vision/live/LiveFrame.kt` defines `LiveFrame`, `FrameSource`, `FrameSourceStatus`, `LiveFrameBroker`, `InMemoryLiveFrameBroker`, and an isolated one-shot Accessibility screenshot adapter seam.

Frames carry session/display identity, a session-local strictly monotonic frame ID, a monotonic capture timestamp, dimensions, source type, and an opaque payload handle/correlation field. The broker stores already-acquired frames only and never acts as a capture engine.

## Explicitly not implemented yet

The following components remain future V4 work:

- **MediaProjection foreground service** — should implement a production `FrameSource` and publish acquired frames into `LiveFrameBroker`; no manifest/service permissions are added in 3.9.6.
- **Continuous Android capture** — should feed the broker through a `FrameSource`; no loop or encoder exists yet.
- **scrcpy -> LiveFrameBroker bridge** — decoded frames should be adapted to `LiveFrame` with the correct session/display identity.
- **Accessibility `setDisplayId` routing** — future executor/gesture routing should consume `ExecutionContext`; current operations remain display 0 only.
- **Multi-display window observation** — future observation collection should publish through `SessionObservationStore` for its owning `ExecutionSession`.
- **Shizuku backend** — should become an execution backend implementation beneath the existing tool/policy/runtime authority; the enum value is only an identifier today.
- **ADB shell display daemon** — not present; if added later, it must bind actions to an `ExecutionSession` and never bypass policy/tool authority.
- **Root backend** — not present; same authority and session-binding rule applies.
- **Virtual display creation** — not present. Synthetic sessions do not create displays.
- **Launching arbitrary apps on a virtual display** — not present. No `ActivityOptions.setLaunchDisplayId` production behavior is added.
- **Hidden workspace tools** — no model-visible `phone.workspace_*` tools exist in 3.9.6.
- **Workspace human handoff** — ownership is representable in `ExecutionSession`, but there is no handoff coordinator or concurrent real controller support yet.

## Integration point intentionally left unwired

The isolated `AccessibilityScreenshotFrameSource.adaptOneShot(...)` adapter demonstrates how an already-successful one-shot screenshot can become a `LiveFrame`. The existing screenshot/capture pipeline is not modified on this branch. The integrating/future agent can wire that adapter after grounding/vision changes settle without changing the broker or `FrameSource` contracts.
