# Authoritative observation contract

Sprint 2 implements proposal 15 from the Artemis comparison. The architectural source is
`codex/artemis-upgrade-research` at `15ba8cfe6449609b8864dd3fe58339b473d7012b`,
`docs/artemis-upgrades/15_COHERENT_OBSERVATION_SNAPSHOTS.md`.

## Ownership and projections

`GatewayObservationAdapter.capture` owns the semantic capture. It traverses the task's Accessibility
tree once, validates its capture boundary, and publishes a session-scoped envelope. The envelope
assigns the evidence ID and generation. `ObservationProjections` contains pure derivations of
PageContext, Page Card, executable evidence, prompt JSON and the learning snapshot. Projection
functions receive captured data and have no capture port. The retained SHADOW mode compares the
old and proposed card from the same capture using fixed difference codes, without another read.

The page's historical key is not its evidence ID. Learned controls and old preview paths remain
knowledge; they are excluded from the current PageContext. Source generation is preserved across
gateway controls, the current card, legacy page, prompt, learning snapshot and associated image.
Mutation of a projected JSON object must not overwrite captured evidence.

Future perception, grounding and memory work must consume this envelope and identity. It must
not reconstruct current state from learned history, global foreground state or another capture
inside a projection. Shadow checks include complete capture identity, actionability, control bounds, state and capabilities; matching IDs/labels alone are insufficient. A new capture is an explicit generation boundary. Page-changing actions still
require a fresh observation, and PhoneToolExecutor remains the canonical mutation authority.

## Identity and availability

The identity includes evidence ID, generation, session, display, capture wall time, monotonic capture
start/end and clock name, window IDs/signature, display geometry/rotation, semantic event revision,
workspace ID/generation and profile when the workspace supplies it. Missing values are JSON null
with explicit unavailable field state. They are never filled from a previous observation. Unknown
foreground/background profile identity is not guessed. Historical nonactionable projections mark
identity stale. Generation is scoped to the session store, not a persistent global sequence.

Monotonic capture and screenshot times use Android `uptimeMillis`, matching live-frame and Android
[screenshot timestamps](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.ScreenshotResult#getTimestamp()). Wall time is for reporting, not skew arithmetic. Window signatures contain
IDs, types, layers, focus/activity and bounds, but no titles or screen text. Accessibility overlay
windows and Cyclone's synthetic overlay ID are excluded. Semantic revision counts observed events
on the requested display; removed/unidentified windows invalidate observed scopes conservatively.

## Capture boundary and images

Lightweight window/scope/geometry/event samples bracket one semantic traversal. A changed sample
or incompatible returned snapshot rejects publication and clears the previous current observation.
The bridge reports CAPTURE_CHANGED and uses Sprint 1's two-attempt, 500ms cooldown recovery.
No alternate display, model or executor is selected.

An explicit visual escalation captures one new semantic generation and one screenshot through
PhoneToolExecutor, with metadata checks after both. It does not bracket the image with two extra
semantic traversals. Pixels are associated only when scope and geometry match, the frame timestamp
falls inside the measured image interval, and total capture skew is at most 1,500ms. Cropped window
images must map to valid in-display bounds matching their pixel dimensions. Rotation, scope or
window changes invalidate the entire bundle. Failed, cached, delayed or incompatible pixels are
unavailable; a stable semantic tree remains usable. Observation image requests pass a minimum frame
capture time to the live producer. The producer uses the same FrameSelection rules for request freshness,
post-action boundaries and scope, waiting at most 800ms for an eligible frame instead of immediately
returning a healthy but pre-request buffered image. A stopped/replaced source cannot satisfy that wait.
Visual capture attempts are separate from usable evidence: at most two attempts with a 500ms pause,
and at most one usable visual capture until verified progress or handoff. Failed pixels are not recorded
as inspected screenshot evidence. Image bytes are ephemeral and excluded from
Page Cards, prompt-context diagnostics and learning projections. Image failure details use fixed
codes, without raw paths or exception text.

Ordinary observation: one semantic traversal, zero screenshot calls. Visual refresh: one semantic
traversal, one screenshot call, three lightweight surface samples. An initial semantic observation
followed by a visual escalation therefore uses two explicit generations overall. Repeated pure
projections use zero additional captures. Executor revalidation is a separate explicit fresh capture.

## Evidence and limits

JVM fixtures exercise page/event changes, overlay animation, screenshot failure, delayed/stale
frames, rotation including 180 degrees, window/display/profile/scope changes, generation equality
and zero dispatch after a failed capture. Injected timing establishes accounting and rejection rules,
not real phone latency. The cumulative sprint ledger records exact suite results and commit SHAs.

Accessibility events may be delayed or absent; Android offers no atomic screenshot/tree transaction.
The contract is a conservative measured association, not proof that the physical screen cannot change
after capture. Existing current-target revalidation and ownership/approval gates remain necessary.
Physical-device acceptance, multi-display race timing and the 1,500ms false-rejection rate are UNVERIFIED.
